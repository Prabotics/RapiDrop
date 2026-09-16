using System.Collections.Concurrent;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Interop;
using System.Windows.Media.Imaging;
using RapiDrop.Core.Models;
using RapiDrop.Core.Network;
using WpfApplication = System.Windows.Application;
using WpfClipboard = System.Windows.Clipboard;
using WpfDataObject = System.Windows.DataObject;
using WpfTextDataFormat = System.Windows.TextDataFormat;

namespace RapiDrop.UI.Clipboard;

public sealed class ClipboardMonitor : IDisposable
{
    public event Action<ClipItem>? ClipCaptured;

    private HwndSource? _hwndSource;
    private IntPtr _hwnd = IntPtr.Zero;
    private readonly ConcurrentDictionary<string, DateTimeOffset> _suppressionHashes = new();
    private bool _isDisposed;
    private readonly object _writeLock = new();

    private static readonly uint FormatExcludeFromProcessing = Win32Clipboard.RegisterClipboardFormat("ExcludeClipboardContentFromMonitorProcessing");
    private static readonly uint FormatCanIncludeInHistory = Win32Clipboard.RegisterClipboardFormat("CanIncludeInClipboardHistory");
    private static readonly uint FormatViewerIgnore = Win32Clipboard.RegisterClipboardFormat("Clipboard Viewer Ignore");

    public ClipboardMonitor()
    {
    }

    public void Start()
    {
        if (_hwnd != IntPtr.Zero) return;

        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            return;
        }

        var parameters = new HwndSourceParameters("RapiDropClipboardListener")
        {
            HwndSourceHook = WndProc,
            WindowStyle = 0
        };

        _hwndSource = new HwndSource(parameters);
        _hwnd = _hwndSource.Handle;

        Win32Clipboard.AddClipboardFormatListener(_hwnd);
    }

    public void Stop()
    {
        if (_hwnd != IntPtr.Zero)
        {
            Win32Clipboard.RemoveClipboardFormatListener(_hwnd);
            _hwnd = IntPtr.Zero;
        }

        _hwndSource?.Dispose();
        _hwndSource = null;
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == Win32Clipboard.WM_CLIPBOARDUPDATE)
        {
            handled = true;
            OnClipboardUpdate();
        }

        return IntPtr.Zero;
    }

    private void OnClipboardUpdate()
    {
        CleanExpiredSuppressionHashes();

        try
        {
            if (IsSensitivePasswordManagerContent())
            {
                return;
            }

            ClipItem? item = ReadPrimaryClip();
            if (item == null) return;

            string hash = ComputeClipHash(item);
            if (_suppressionHashes.TryRemove(hash, out _))
            {
                return;
            }

            _suppressionHashes[hash] = DateTimeOffset.UtcNow.AddSeconds(5);
            ClipCaptured?.Invoke(item);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Error processing clipboard update: {ex.Message}");
        }
    }

    private static bool IsSensitivePasswordManagerContent()
    {
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows)) return false;

        if (FormatExcludeFromProcessing != 0 && Win32Clipboard.IsClipboardFormatAvailable(FormatExcludeFromProcessing))
            return true;
        if (FormatViewerIgnore != 0 && Win32Clipboard.IsClipboardFormatAvailable(FormatViewerIgnore))
            return true;

        if (FormatCanIncludeInHistory != 0 && Win32Clipboard.IsClipboardFormatAvailable(FormatCanIncludeInHistory))
        {
            if (Win32Clipboard.OpenClipboard(IntPtr.Zero))
            {
                try
                {
                    IntPtr hData = Win32Clipboard.GetClipboardData(FormatCanIncludeInHistory);
                    if (hData != IntPtr.Zero)
                    {
                        byte val = Marshal.ReadByte(hData);
                        if (val == 0) return true;
                    }
                }
                finally
                {
                    Win32Clipboard.CloseClipboard();
                }
            }
        }

        return false;
    }

    public void WriteToClipboard(ClipItem item)
    {
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows)) return;

        string hash = ComputeClipHash(item);
        _suppressionHashes[hash] = DateTimeOffset.UtcNow.AddSeconds(5);

        lock (_writeLock)
        {
            WpfApplication.Current?.Dispatcher.Invoke(() =>
            {
                int maxRetries = 5;
                for (int i = 0; i < maxRetries; i++)
                {
                    try
                    {
                        var dataObject = new WpfDataObject();

                        switch (item.Type)
                        {
                            case ClipContentType.Text:
                            case ClipContentType.Url:
                                if (!string.IsNullOrEmpty(item.TextContent))
                                {
                                    dataObject.SetText(item.TextContent, WpfTextDataFormat.UnicodeText);
                                }
                                break;

                            case ClipContentType.Image:
                                if (item.RawData != null && item.RawData.Length > 0)
                                {
                                    using var ms = new MemoryStream(item.RawData);
                                    var bitmap = new BitmapImage();
                                    bitmap.BeginInit();
                                    bitmap.CacheOption = BitmapCacheOption.OnLoad;
                                    bitmap.StreamSource = ms;
                                    bitmap.EndInit();
                                    bitmap.Freeze();

                                    dataObject.SetImage(bitmap);
                                }
                                break;

                            case ClipContentType.File:
                                if (item.RawData != null && !string.IsNullOrEmpty(item.FileName))
                                {
                                    string tempDir = Path.Combine(Path.GetTempPath(), "RapiDrop");
                                    Directory.CreateDirectory(tempDir);
                                    string safeName = Path.GetFileName(item.FileName);
                                    if (string.IsNullOrWhiteSpace(safeName))
                                    {
                                        safeName = "synced_file.bin";
                                    }
                                    string filePath = Path.Combine(tempDir, safeName);
                                    File.WriteAllBytes(filePath, item.RawData);

                                    var files = new System.Collections.Specialized.StringCollection { filePath };
                                    dataObject.SetFileDropList(files);
                                }
                                break;
                        }

                        WpfClipboard.SetDataObject(dataObject, copy: true);
                        break;
                    }
                    catch (COMException)
                    {
                        if (i == maxRetries - 1) throw;
                        Thread.Sleep(25);
                    }
                }
            });
        }
    }

    public static ClipItem? ReadPrimaryClip()
    {
        for (int attempt = 0; attempt < 3; attempt++)
        {
            try
            {
                if (WpfClipboard.ContainsText())
                {
                string text = WpfClipboard.GetText(WpfTextDataFormat.UnicodeText);
                if (string.IsNullOrWhiteSpace(text)) return null;

                bool isUrl = text.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                             text.StartsWith("https://", StringComparison.OrdinalIgnoreCase);

                return new ClipItem(isUrl ? ClipContentType.Url : ClipContentType.Text, textContent: text);
            }

            if (WpfClipboard.ContainsImage())
            {
                var image = WpfClipboard.GetImage();
                if (image != null)
                {
                    var encoder = new PngBitmapEncoder();
                    encoder.Frames.Add(BitmapFrame.Create(image));
                    using var ms = new MemoryStream();
                    encoder.Save(ms);
                    byte[] pngBytes = ms.ToArray();

                    if (pngBytes.Length <= WireFrame.MaxPayloadSize)
                    {
                        return new ClipItem(ClipContentType.Image, rawData: pngBytes, fileName: "Clipboard Image.png");
                    }
                }
            }

            if (WpfClipboard.ContainsFileDropList())
            {
                var files = WpfClipboard.GetFileDropList();
                if (files.Count > 0 && !string.IsNullOrEmpty(files[0]))
                {
                    string filePath = files[0]!;
                    if (File.Exists(filePath))
                    {
                        var fileInfo = new FileInfo(filePath);
                        if (fileInfo.Length <= WireFrame.MaxPayloadSize)
                        {
                            byte[] fileBytes = File.ReadAllBytes(filePath);
                            return new ClipItem(ClipContentType.File, rawData: fileBytes, fileName: Path.GetFileName(filePath));
                        }
                    }
                }
            }
        }
            catch (COMException)
            {
                if (attempt == 2) break;
                Thread.Sleep(50 * (attempt + 1));
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to read primary clip: {ex.Message}");
                break;
            }
        }

        return null;
    }

    public static string ComputeClipHash(ClipItem item)
    {
        using var sha = SHA256.Create();
        var stream = new MemoryStream();
        using var writer = new BinaryWriter(stream, Encoding.UTF8, leaveOpen: true);

        writer.Write((int)item.Type);
        writer.Write(item.TextContent ?? string.Empty);
        writer.Write(item.FileName ?? string.Empty);
        if (item.RawData != null)
        {
            writer.Write(item.RawData.Length);
            writer.Write(item.RawData);
        }

        stream.Position = 0;
        byte[] hash = sha.ComputeHash(stream);
        return Convert.ToHexString(hash);
    }

    private void CleanExpiredSuppressionHashes()
    {
        var now = DateTimeOffset.UtcNow;
        foreach (var (key, expiry) in _suppressionHashes)
        {
            if (now > expiry)
            {
                _suppressionHashes.TryRemove(key, out _);
            }
        }
    }

    public void Dispose()
    {
        if (_isDisposed) return;
        _isDisposed = true;
        Stop();
    }
}
