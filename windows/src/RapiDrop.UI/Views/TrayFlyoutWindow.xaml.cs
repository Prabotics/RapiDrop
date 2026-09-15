using System.IO;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Animation;
using System.Windows.Media;
using System.Windows.Shapes;
using RapiDrop.Core.Models;
using RapiDrop.Core.Network;
using RapiDrop.Core.Security;
using RapiDrop.UI.Clipboard;
using RapiDrop.UI.Theme;
using Microsoft.Win32;
using WpfApplication = System.Windows.Application;
using WpfBrush = System.Windows.Media.Brush;
using WpfButton = System.Windows.Controls.Button;
using WpfPath = System.Windows.Shapes.Path;
using IoPath = System.IO.Path;
namespace RapiDrop.UI.Views;

public partial class TrayFlyoutWindow : Window
{
    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, IntPtr lpdwProcessId);

    [DllImport("user32.dll")]
    private static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct APPBARDATA
    {
        public int cbSize;
        public IntPtr hWnd;
        public int uCallbackMessage;
        public int uEdge;
        public RECT rc;
        public IntPtr lParam;
    }

    [DllImport("shell32.dll")]
    private static extern IntPtr SHAppBarMessage(int dwMessage, ref APPBARDATA pData);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct MONITORINFO
    {
        public int cbSize;
        public RECT rcMonitor;
        public RECT rcWork;
        public int dwFlags;
    }

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromWindow(IntPtr hwnd, int dwFlags);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO lpmi);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetProcessWorkingSetSize(IntPtr proc, IntPtr min, IntPtr max);

    [DllImport("psapi.dll", SetLastError = true)]
    private static extern bool EmptyWorkingSet(IntPtr hProcess);
    private const int ABM_GETTASKBARPOS = 0x00000005;
    private const int MONITOR_DEFAULTTONEAREST = 0x00000002;

    private static void ForceForegroundWindow(IntPtr hWnd)
    {
        try
        {
            IntPtr fg = GetForegroundWindow();
            if (fg == hWnd) return;

            uint fgThread = GetWindowThreadProcessId(fg, IntPtr.Zero);
            uint curThread = GetCurrentThreadId();

            if (fgThread != 0 && fgThread != curThread)
            {
                AttachThreadInput(curThread, fgThread, true);
                SetForegroundWindow(hWnd);
                AttachThreadInput(curThread, fgThread, false);
            }
            else
            {
                SetForegroundWindow(hWnd);
            }
        }
        catch
        {
            SetForegroundWindow(hWnd);
        }
    }
    private readonly CredentialStore _store;
    private readonly NetworkEngine _network;
    private readonly ClipboardMonitor _clipboard;
    private readonly TrayIconManager _tray;
    private readonly List<ClipItem> _recentClips = new();
    private bool _showHistory = true;
    private string _searchQuery = "";
    private string _selectedFilter = "All";

    private (DiscoveredDevice Device, string? Pin)? _incomingPairInvite;
    private DiscoveredDevice? _selectedDeviceForPairing;
    private string? _outgoingPairPin;
    private List<DiscoveredDevice> _discoveredDevices = new();
    private string _lastRenderedDevicesSignature = "";
    private CancellationTokenSource? _pairingTimeoutCts;
    private bool _isMenuOpen = false;
    private CancellationTokenSource? _searchDebounceCts;
    private bool _isClosing = false;
    private bool _isPinned = false;
    private bool _isDragOver = false;
    private int _dragEnterCounter = 0;
    private bool _isViewingHistory = false;
    private long _lastShowTicks = 0;
    private long _lastDeactivatedTicks = 0;
    private long _lastToggleTicks = 0;
    private int _animationToken = 0;
    private readonly System.Threading.Timer? _memoryTrimTimer;
    private readonly struct SpeedSample
    {
        public long Bytes { get; }
        public long TimestampMs { get; }
        public SpeedSample(long bytes, long timestampMs)
        {
            Bytes = bytes;
            TimestampMs = timestampMs;
        }
    }

    private readonly Queue<SpeedSample> _transferSpeedSamples = new();
    private string? _currentTrackingTransferId;

    private (double SpeedMbPerSec, string? EtaText) CalculateRollingSpeedAndEta(string transferId, long bytes, long totalBytes)
    {
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (_currentTrackingTransferId != transferId)
        {
            _currentTrackingTransferId = transferId;
            _transferSpeedSamples.Clear();
        }

        _transferSpeedSamples.Enqueue(new SpeedSample(bytes, now));

        while (_transferSpeedSamples.Count > 2 && (now - _transferSpeedSamples.Peek().TimestampMs) > 2000)
        {
            _transferSpeedSamples.Dequeue();
        }

        if (_transferSpeedSamples.Count < 2)
        {
            return (0.0, null);
        }

        var oldest = _transferSpeedSamples.Peek();
        long timeDiffMs = now - oldest.TimestampMs;
        long bytesDiff = bytes - oldest.Bytes;

        if (timeDiffMs < 200 || bytesDiff <= 0)
        {
            return (0.0, null);
        }

        double speedBytesPerSec = (double)bytesDiff / (timeDiffMs / 1000.0);
        double speedMbPerSec = speedBytesPerSec / 1_048_576.0;

        long remainingBytes = Math.Max(0, totalBytes - bytes);
        if (remainingBytes <= 0 || speedBytesPerSec <= 0)
        {
            return (speedMbPerSec, null);
        }

        long etaSeconds = (long)Math.Ceiling(remainingBytes / speedBytesPerSec);
        string etaText;
        if (etaSeconds < 60)
        {
            etaText = $"{etaSeconds}s remaining";
        }
        else if (etaSeconds < 3600)
        {
            long minutes = etaSeconds / 60;
            long seconds = etaSeconds % 60;
            etaText = $"{minutes}m {seconds}s remaining";
        }
        else
        {
            long hours = etaSeconds / 3600;
            long minutes = (etaSeconds % 3600) / 60;
            etaText = $"{hours}h {minutes}m remaining";
        }

        return (speedMbPerSec, etaText);
    }

    private void ResetTransferSpeedTracking()
    {
        _transferSpeedSamples.Clear();
        _currentTrackingTransferId = null;
    }
    public TrayFlyoutWindow(
        CredentialStore store,
        NetworkEngine network,
        ClipboardMonitor clipboard,
        TrayIconManager tray)
    {
        InitializeComponent();

        _store = store;
        _network = network;
        _clipboard = clipboard;
        _network.LocalDeviceId = _store.Config.DeviceId;
        _tray = tray;

        _showHistory = _store.Config.ShowSyncHistory;
        UpdateHistoryVisibility();

        _network.ConnectionStateChanged += (connected, peerName) =>
        {
            Dispatcher.Invoke(() =>
            {
                if (connected)
                {
                    try { System.Media.SystemSounds.Asterisk.Play(); } catch { }
                    _pairingTimeoutCts?.Cancel();
                    _pairingTimeoutCts?.Dispose();
                    _pairingTimeoutCts = null;

                    if (_selectedDeviceForPairing != null && _outgoingPairPin != null)
                    {
                        _store.Config.PairedPeerName = peerName ?? _selectedDeviceForPairing.Name;
                        if (!string.IsNullOrEmpty(_selectedDeviceForPairing.Id))
                        {
                            _store.Config.PairedPeerId = _selectedDeviceForPairing.Id;
                        }
                        _store.Config.PairedPeerHost = _selectedDeviceForPairing.Host;
                        _store.Config.PairedPeerPort = _selectedDeviceForPairing.Port;
                        _store.Config.PairingPin = _outgoingPairPin;
                        _selectedDeviceForPairing = null;
                        _outgoingPairPin = null;
                    }
                    else if (!string.IsNullOrEmpty(peerName) && string.IsNullOrEmpty(_store.Config.PairedPeerName))
                    {
                        _store.Config.PairedPeerName = peerName;
                        _store.Save(_store.Config);
                    }
                }
                else
                {
                    CardTransferProgress.Visibility = Visibility.Collapsed;
                }
                UpdateUI();
            });
        };

        _network.DiscoveredDevicesChanged += devices =>
        {
            Dispatcher.InvokeAsync(() => UpdateDiscoveredDevices(devices));
        };

        _network.PairInviteReceived += (device, pin) =>
        {
            Dispatcher.Invoke(() => HandleIncomingPairInvite(device, pin));
        };

        _network.PairOfferReceived += (device, sas) =>
        {
            Dispatcher.Invoke(() =>
            {
                if (_selectedDeviceForPairing != null)
                {
                    _outgoingPairPin = sas;
                    UpdateUI();
                }
            });
        };

        _network.PairAcceptReceived += (target, sas) =>
        {
            Dispatcher.Invoke(() => HandlePairAccept(target, sas));
        };

        _network.PairDeclinedReceived += reason =>
        {
            Dispatcher.Invoke(() => HandlePairDeclined(reason));
        };

        _network.RemoteUnpairedReceived += () =>
        {
            Dispatcher.Invoke(() =>
            {
                _pairingTimeoutCts?.Cancel();
                _pairingTimeoutCts?.Dispose();
                _pairingTimeoutCts = null;
                _store.ClearPairing();
                _network.DisconnectAll();
                _selectedDeviceForPairing = null;
                _outgoingPairPin = null;
                _incomingPairInvite = null;
                UpdateUI();
            });
        };

        _network.PrivacyModeReceived += _ =>
        {
        };

        _network.ClipReceived += clip =>
        {
            Dispatcher.Invoke(() =>
            {
                try { System.Media.SystemSounds.Asterisk.Play(); } catch { }
                _clipboard.WriteToClipboard(clip);
                AddRecentClip(clip);
            });
        };
        _network.TransferProgressUpdated += (id, name, bytes, total, index, totalFiles, isComplete) =>
        {
            Dispatcher.Invoke(() =>
            {
                if (isComplete)
                {
                    ResetTransferSpeedTracking();
                    CardTransferProgress.Visibility = Visibility.Collapsed;
                }
                else
                {
                    CardTransferProgress.Visibility = Visibility.Visible;
                    TxtTransferFileName.Text = name;
                    TxtTransferCounter.Text = totalFiles > 1 ? $"{index}/{totalFiles}" : "";
                    double fraction = total > 0 ? (double)bytes / total * 100.0 : 0;
                    ProgressTransfer.Value = fraction;
                    double mbTransferred = bytes / 1_048_576.0;
                    double mbTotal = total / 1_048_576.0;
                    TxtTransferBytes.Text = $"{mbTransferred:0.0} / {mbTotal:0.0} MB";

                    var (speedMb, eta) = CalculateRollingSpeedAndEta(id, bytes, total);
                    if (speedMb > 0)
                    {
                        TxtTransferSpeed.Text = !string.IsNullOrEmpty(eta) ? $"{speedMb:0.0} MB/s · {eta}" : $"{speedMb:0.0} MB/s";
                    }
                    else
                    {
                        TxtTransferSpeed.Text = total > 0 ? "Calculating..." : "";
                    }
                }
            });
        };
        _network.TransferCancelled += id =>
        {
            Dispatcher.Invoke(() =>
            {
                ResetTransferSpeedTracking();
                CardTransferProgress.Visibility = Visibility.Collapsed;
                ShowTransferError("Transfer cancelled");
            });
        };

        _clipboard.ClipCaptured += clip =>
        {
            Dispatcher.Invoke(() =>
            {
                if (_network.IsConnected)
                {
                    _network.SendClip(clip);
                }
                AddRecentClip(clip);
            });
        };

        _tray.TrayLeftClicked += () =>
        {
            Dispatcher.Invoke(ToggleVisibility);
        };

        Deactivated += (s, e) =>
        {
            if (_isMenuOpen || _isPinned || _isDragOver) return;
            if (Environment.TickCount64 - _lastShowTicks < 250) return;
            _lastDeactivatedTicks = Environment.TickCount64;
            Hide();
        };
        Loaded += (s, e) => PositionAtTaskbarCorner();
        SizeChanged += (s, e) =>
        {
            if (!IsVisible || _isClosing) return;
            PositionAtTaskbarCorner();
        };
        UpdateUI();

        _memoryTrimTimer = new System.Threading.Timer(_ =>
        {
            try
            {
                bool isVis = false;
                Dispatcher.Invoke(() => isVis = IsVisible);
                if (!isVis)
                {
                    TrimWorkingSet();
                }
            }
            catch { }
        }, null, TimeSpan.FromSeconds(10), TimeSpan.FromMinutes(2.5));
    }
    public static void PerformApplicationExit()
    {
        try
        {
            WpfApplication.Current?.Shutdown(0);
        }
        catch { }
    }

    private void BtnPinWindow_Click(object sender, RoutedEventArgs e)
    {
        _isPinned = !_isPinned;
        IconPinPath.SetResourceReference(WpfPath.FillProperty, _isPinned ? "BrushPulse" : "BrushTextSecondary");
        BtnPinWindow.ToolTip = _isPinned ? "Unpin window" : "Pin window open";
    }

    protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
    {
        e.Cancel = true;
        Hide();
    }

    private void BtnQuit_Click(object sender, RoutedEventArgs e)
    {
        PerformApplicationExit();
    }

    public new void Hide()
    {
        if (!IsVisible || _isClosing)
        {
            base.Hide();
            Task.Run(TrimWorkingSet);
            return;
        }

        int token = Interlocked.Increment(ref _animationToken);
        _isClosing = true;

        var workArea = SystemParameters.WorkArea;
        double endY = workArea.Top > 0 ? -6.0 : 6.0;

        var fadeOut = new DoubleAnimation(Opacity, 0.0, new Duration(TimeSpan.FromMilliseconds(90)))
        {
            EasingFunction = new QuarticEase { EasingMode = EasingMode.EaseIn }
        };
        var slideOut = new DoubleAnimation(FlyoutTransform.Y, endY, new Duration(TimeSpan.FromMilliseconds(90)))
        {
            EasingFunction = new QuarticEase { EasingMode = EasingMode.EaseIn }
        };

        fadeOut.Completed += (s, e) =>
        {
            if (token == _animationToken)
            {
                _isClosing = false;
                BeginAnimation(OpacityProperty, null);
                FlyoutTransform.BeginAnimation(TranslateTransform.YProperty, null);
                base.Hide();
                Task.Run(TrimWorkingSet);
            }
        };

        BeginAnimation(OpacityProperty, fadeOut);
        FlyoutTransform.BeginAnimation(TranslateTransform.YProperty, slideOut);
    }

    public new void Show()
    {
        int token = Interlocked.Increment(ref _animationToken);
        _isClosing = false;
        _lastShowTicks = Environment.TickCount64;

        BeginAnimation(OpacityProperty, null);
        FlyoutTransform.BeginAnimation(TranslateTransform.YProperty, null);

        ScrollClips.ScrollToTop();
        RenderClipsList();

        Measure(new System.Windows.Size(Width > 0 ? Width : 320, SystemParameters.WorkArea.Height));
        UpdateLayout();

        PositionAtTaskbarCorner();

        Opacity = 0.0;
        var workArea = SystemParameters.WorkArea;
        double startY = workArea.Top > 0 ? -8.0 : 8.0;
        FlyoutTransform.Y = startY;

        Topmost = true;
        base.Show();
        Activate();
        Focus();

        var handle = new System.Windows.Interop.WindowInteropHelper(this).Handle;
        if (handle != IntPtr.Zero)
        {
            ForceForegroundWindow(handle);
        }

        var fadeIn = new DoubleAnimation(0.0, 1.0, new Duration(TimeSpan.FromMilliseconds(130)))
        {
            EasingFunction = new QuarticEase { EasingMode = EasingMode.EaseOut }
        };
        var slideIn = new DoubleAnimation(startY, 0.0, new Duration(TimeSpan.FromMilliseconds(130)))
        {
            EasingFunction = new QuarticEase { EasingMode = EasingMode.EaseOut }
        };
        BeginAnimation(OpacityProperty, fadeIn);
        FlyoutTransform.BeginAnimation(TranslateTransform.YProperty, slideIn);
    }

    public static void TrimWorkingSet()
    {
        try
        {
            GC.Collect(2, GCCollectionMode.Aggressive, blocking: true, compacting: true);
            GC.WaitForPendingFinalizers();
            GC.Collect(2, GCCollectionMode.Aggressive, blocking: true, compacting: true);

            if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
            {
                using var proc = System.Diagnostics.Process.GetCurrentProcess();
                EmptyWorkingSet(proc.Handle);
                SetProcessWorkingSetSize(proc.Handle, (IntPtr)(-1), (IntPtr)(-1));
            }
        }
        catch { }
    }

    public void ToggleVisibility()
    {
        long now = Environment.TickCount64;
        if (now - _lastToggleTicks < 120) return;
        _lastToggleTicks = now;

        if (now - _lastDeactivatedTicks < 200) return;

        if (IsVisible && !_isClosing && Opacity > 0.1)
        {
            Hide();
        }
        else
        {
            Show();
        }
    }

    private void PositionAtTaskbarCorner()
    {
        var helper = new System.Windows.Interop.WindowInteropHelper(this);
        IntPtr hwnd = helper.Handle;

        double dpiScaleX = 1.0;
        double dpiScaleY = 1.0;
        var source = PresentationSource.FromVisual(this);
        if (source?.CompositionTarget != null)
        {
            dpiScaleX = source.CompositionTarget.TransformToDevice.M11;
            dpiScaleY = source.CompositionTarget.TransformToDevice.M22;
        }

        double wDips = ActualWidth > 50 ? ActualWidth : (DesiredSize.Width > 50 ? DesiredSize.Width : (Width > 0 ? Width : 320));
        double hDips = ActualHeight > 50 ? ActualHeight : (DesiredSize.Height > 50 ? DesiredSize.Height : 400);
        double maxHDips = MaxHeight > 0 ? MaxHeight : 500;
        hDips = Math.Min(hDips, maxHDips);

        double wPixels = wDips * dpiScaleX;
        double hPixels = hDips * dpiScaleY;

        RECT workRectPixels = new RECT();
        bool gotMonitor = false;

        if (hwnd != IntPtr.Zero)
        {
            IntPtr hMon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
            if (hMon != IntPtr.Zero)
            {
                var mi = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
                if (GetMonitorInfo(hMon, ref mi))
                {
                    workRectPixels = mi.rcWork;
                    gotMonitor = true;
                }
            }
        }

        if (!gotMonitor)
        {
            var pWork = SystemParameters.WorkArea;
            workRectPixels = new RECT
            {
                Left = (int)(pWork.Left * dpiScaleX),
                Top = (int)(pWork.Top * dpiScaleY),
                Right = (int)(pWork.Right * dpiScaleX),
                Bottom = (int)(pWork.Bottom * dpiScaleY)
            };
        }

        var abd = new APPBARDATA { cbSize = Marshal.SizeOf<APPBARDATA>() };
        IntPtr result = SHAppBarMessage(ABM_GETTASKBARPOS, ref abd);

        double targetLeftPixels;
        double targetTopPixels;
        int marginPixels = (int)(12 * dpiScaleX);

        if (result != IntPtr.Zero)
        {
            switch (abd.uEdge)
            {
                case 3:
                    targetLeftPixels = workRectPixels.Right - wPixels - marginPixels;
                    targetTopPixels = abd.rc.Top - hPixels - marginPixels;
                    break;

                case 1:
                    targetLeftPixels = workRectPixels.Right - wPixels - marginPixels;
                    targetTopPixels = abd.rc.Bottom + marginPixels;
                    break;

                case 0:
                    targetLeftPixels = abd.rc.Right + marginPixels;
                    targetTopPixels = workRectPixels.Bottom - hPixels - marginPixels;
                    break;

                case 2:
                    targetLeftPixels = abd.rc.Left - wPixels - marginPixels;
                    targetTopPixels = workRectPixels.Bottom - hPixels - marginPixels;
                    break;

                default:
                    targetLeftPixels = workRectPixels.Right - wPixels - marginPixels;
                    targetTopPixels = workRectPixels.Bottom - hPixels - marginPixels;
                    break;
            }
        }
        else
        {
            targetLeftPixels = workRectPixels.Right - wPixels - marginPixels;
            targetTopPixels = workRectPixels.Bottom - hPixels - marginPixels;
        }

        Left = targetLeftPixels / dpiScaleX;
        Top = targetTopPixels / dpiScaleY;
    }
    private static string NormalizeDeviceName(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) return string.Empty;
        string n = name.Replace('’', '\'')
            .Replace('‘', '\'')
            .Replace("\"", "")
            .Replace("\\", "")
            .Trim();
        n = System.Text.RegularExpressions.Regex.Replace(n, @"\s*\(\d+\)$", "");
        n = System.Text.RegularExpressions.Regex.Replace(n, @"\s*-\s*\d+$", "");
        return n.Trim().ToLowerInvariant();
    }

    private void UpdateDiscoveredDevices(IReadOnlyList<DiscoveredDevice> devices)
    {
        string localId = _store.Config.DeviceId;
        string normLocal = NormalizeDeviceName(Environment.MachineName);

        var devicesById = new Dictionary<string, DiscoveredDevice>(StringComparer.OrdinalIgnoreCase);
        var devicesByNameWithoutId = new Dictionary<string, DiscoveredDevice>(StringComparer.OrdinalIgnoreCase);
        foreach (var d in devices)
        {
            if (string.IsNullOrEmpty(d.Name)) continue;

            bool isSelf;
            if (!string.IsNullOrEmpty(d.Id) && !string.IsNullOrEmpty(localId))
            {
                isSelf = string.Equals(d.Id, localId, StringComparison.OrdinalIgnoreCase);
            }
            else
            {
                isSelf = !string.IsNullOrEmpty(normLocal) && NormalizeDeviceName(d.Name) == normLocal;
            }
            if (isSelf) continue;

            if (!string.IsNullOrEmpty(d.Id))
            {
                if (!devicesById.ContainsKey(d.Id))
                {
                    devicesById[d.Id] = d;
                }
            }
            else
            {
                string normName = NormalizeDeviceName(d.Name);
                if (!devicesByNameWithoutId.ContainsKey(normName))
                {
                    devicesByNameWithoutId[normName] = d;
                }
            }
        }

        var deduped = new List<DiscoveredDevice>(devicesById.Values);
        var seenNamesWithId = new HashSet<string>(deduped.Select(d => NormalizeDeviceName(d.Name)), StringComparer.OrdinalIgnoreCase);

        foreach (var kvp in devicesByNameWithoutId)
        {
            if (!seenNamesWithId.Contains(kvp.Key))
            {
                deduped.Add(kvp.Value);
            }
        }

        deduped.Sort((a, b) => string.Compare(a.Name, b.Name, StringComparison.CurrentCultureIgnoreCase));

        if (_discoveredDevices.Count == deduped.Count)
        {
            bool identical = true;
            for (int i = 0; i < deduped.Count; i++)
            {
                var cur = _discoveredDevices[i];
                var next = deduped[i];
                if (cur.Id != next.Id || cur.Name != next.Name || cur.Host != next.Host || cur.Port != next.Port || cur.DeviceType != next.DeviceType)
                {
                    identical = false;
                    break;
                }
            }
            if (identical)
            {
                return;
            }
        }

        _discoveredDevices = deduped;
        UpdateUI();
    }
    private static bool IsSelfDevice(DiscoveredDevice device, string localId)
    {
        if (!string.IsNullOrEmpty(device.Id) && !string.IsNullOrEmpty(localId))
        {
            return string.Equals(device.Id, localId, StringComparison.OrdinalIgnoreCase);
        }
        string normLocal = NormalizeDeviceName(Environment.MachineName);
        string normDev = NormalizeDeviceName(device.Name);
        return !string.IsNullOrEmpty(normLocal) && normDev == normLocal;
    }

    private void HandleIncomingPairInvite(DiscoveredDevice device, string? pin)
    {
        _incomingPairInvite = (device, pin);
        UpdateUI();
    }

    private void HandlePairAccept(DiscoveredDevice target, string sas)
    {
        _pairingTimeoutCts?.Cancel();
        _pairingTimeoutCts?.Dispose();
        _pairingTimeoutCts = null;
        string pinToStore = !string.IsNullOrEmpty(sas) ? sas : (_outgoingPairPin ?? "");
        string peerName = _selectedDeviceForPairing?.Name ?? target.Name;
        string peerHost = target.Host;
        int peerPort = target.Port;

        _store.Config.PairedPeerName = peerName;
        if (!string.IsNullOrEmpty(target.Id))
        {
            _store.Config.PairedPeerId = target.Id;
        }
        _store.Config.PairedPeerHost = peerHost;
        _store.Config.PairedPeerPort = peerPort;
        _store.Config.PairingPin = pinToStore;
        if (_network.SessionKey != null)
        {
            _store.Config.PairedSessionKeyHex = Convert.ToHexString(_network.SessionKey);
        }
        _store.Save(_store.Config);

        var connectTarget = new DiscoveredDevice(peerName, peerHost, peerPort);
        _network.InitiatePairing(connectTarget, pinToStore);
        _selectedDeviceForPairing = null;
        _outgoingPairPin = null;
        UpdateUI();
    }

    private void HandlePairDeclined(string reason)
    {
        _pairingTimeoutCts?.Cancel();
        _pairingTimeoutCts?.Dispose();
        _pairingTimeoutCts = null;
        _selectedDeviceForPairing = null;
        _outgoingPairPin = null;
        UpdateUI();
    }

    private void UpdateUI()
    {
        bool isConnected = _network.IsConnected;
        bool isPaired = !string.IsNullOrEmpty(_store.Config.PairedPeerName);

        if (_incomingPairInvite != null)
        {
            var (device, pin) = _incomingPairInvite.Value;
            TxtIncomingDeviceName.Text = FormatFriendlyDeviceName(device.Name);
            TxtIncomingSubtitle.Text = "wants to connect with this PC";
            if (!string.IsNullOrEmpty(pin))
            {
                TxtIncomingPin.Text = pin.Length == 6 ? $"{pin[..3]} {pin[3..]}" : pin;
                BorderIncomingPin.Visibility = Visibility.Visible;
            }
            else
            {
                BorderIncomingPin.Visibility = Visibility.Collapsed;
            }
            CardIncomingInvite.Visibility = Visibility.Visible;
            CardOutgoingPair.Visibility = Visibility.Collapsed;
            CardConnectedDevice.Visibility = Visibility.Collapsed;
            SectionDiscoveredDevices.Visibility = Visibility.Collapsed;
        }
        else if (isPaired)
        {
            CardIncomingInvite.Visibility = Visibility.Collapsed;
            CardOutgoingPair.Visibility = Visibility.Collapsed;
            SectionDiscoveredDevices.Visibility = Visibility.Collapsed;

            if (_isViewingHistory)
            {
                CardConnectedDevice.Visibility = Visibility.Collapsed;
            }
            else
            {
                CardConnectedDevice.Visibility = Visibility.Visible;
                string peerName = FormatFriendlyDeviceName(_network.ConnectedPeerName ?? _store.Config.PairedPeerName ?? "Connected Device");
                TxtConnectedDeviceName.Text = peerName;
                TxtSendMediaSub.Text = "Share files, photos, or documents";
                TxtPushClipboardSub.Text = $"Send current clipboard to {peerName}";

                if (isConnected)
                {
                    DotConnectedStatus.Fill = (WpfBrush)FindResource("BrushStatusConnected");
                    TxtConnectedStatus.Text = "Connected";
                    TxtConnectedStatus.Foreground = (WpfBrush)FindResource("BrushStatusConnected");
                    BtnSendMedia.IsEnabled = true;
                    BtnPushClipboard.IsEnabled = true;
                }
                else
                {
                    DotConnectedStatus.Fill = (WpfBrush)FindResource("BrushStatusDisconnected");
                    TxtConnectedStatus.Text = "Offline";
                    TxtConnectedStatus.Foreground = (WpfBrush)FindResource("BrushTextSecondary");
                    BtnSendMedia.IsEnabled = false;
                    BtnPushClipboard.IsEnabled = false;
                }
            }
        }
        else if (_selectedDeviceForPairing != null)
        {
            TxtOutgoingDeviceName.Text = FormatFriendlyDeviceName(_selectedDeviceForPairing.Name);
            if (!string.IsNullOrEmpty(_outgoingPairPin))
            {
                string formattedPin = _outgoingPairPin.Length == 6
                    ? $"{_outgoingPairPin[..3]} {_outgoingPairPin[3..]}"
                    : _outgoingPairPin;
                TxtOutgoingPin.Text = formattedPin;
                BorderOutgoingPin.Visibility = Visibility.Visible;
                TxtOutgoingStatus.Text = $"Compare code with {FormatFriendlyDeviceName(_selectedDeviceForPairing.Name)}...";
            }
            else
            {
                BorderOutgoingPin.Visibility = Visibility.Collapsed;
                TxtOutgoingStatus.Text = $"Waiting for approval on {FormatFriendlyDeviceName(_selectedDeviceForPairing.Name)}...";
            }
            CardIncomingInvite.Visibility = Visibility.Collapsed;
            CardOutgoingPair.Visibility = Visibility.Visible;
            CardConnectedDevice.Visibility = Visibility.Collapsed;
            SectionDiscoveredDevices.Visibility = Visibility.Collapsed;
        }
        else
        {
            _isViewingHistory = false;
            CardIncomingInvite.Visibility = Visibility.Collapsed;
            CardOutgoingPair.Visibility = Visibility.Collapsed;
            CardConnectedDevice.Visibility = Visibility.Collapsed;
            SectionDiscoveredDevices.Visibility = Visibility.Visible;
            RenderDiscoveredDevicesList();
        }
        UpdateHistoryVisibility();
    }
    private void RenderDiscoveredDevicesList()
    {
        PanelDiscoveredDevices.Children.Clear();

        string? pairedPeer = _store.Config.PairedPeerName;
        var unpaired = _discoveredDevices
            .Where(d => string.IsNullOrEmpty(pairedPeer) || !IsSameDeviceName(d.Name, pairedPeer))
            .ToList();
        if (unpaired.Count == 0)
        {
            _lastRenderedDevicesSignature = "";
            PanelDiscoveredDevices.Children.Clear();
            TxtDiscoveredHeader.Text = "Nearby devices";
            CardSearchingDevices.Visibility = Visibility.Visible;
            return;
        }

        string signature = string.Join("|", unpaired.Select(d => $"{d.Id}:{d.Name}:{d.DeviceType}:{d.Host}:{d.Port}"));
        if (signature == _lastRenderedDevicesSignature && PanelDiscoveredDevices.Children.Count == unpaired.Count)
        {
            TxtDiscoveredHeader.Text = "Nearby devices";
            CardSearchingDevices.Visibility = Visibility.Collapsed;
            return;
        }
        _lastRenderedDevicesSignature = signature;
        PanelDiscoveredDevices.Children.Clear();
        TxtDiscoveredHeader.Text = "Nearby devices";
        CardSearchingDevices.Visibility = Visibility.Collapsed;
        bool isInitialMount = string.IsNullOrEmpty(_lastRenderedDevicesSignature);
        _lastRenderedDevicesSignature = signature;
        PanelDiscoveredDevices.Children.Clear();
        TxtDiscoveredHeader.Text = "Nearby devices";
        CardSearchingDevices.Visibility = Visibility.Collapsed;

        foreach (var device in unpaired)
        {
            var card = new Border
            {
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(8),
                Margin = new Thickness(0, 0, 0, 6)
            };
            card.SetResourceReference(Border.BackgroundProperty, "BrushCanvas");
            card.SetResourceReference(Border.BorderBrushProperty, "BrushBorder");

            if (!isInitialMount)
            {
                card.Opacity = 0.0;
                var cardAnim = new DoubleAnimation(0.0, 1.0, new Duration(TimeSpan.FromMilliseconds(180)))
                {
                    EasingFunction = new QuarticEase { EasingMode = EasingMode.EaseOut }
                };
                card.BeginAnimation(UIElement.OpacityProperty, cardAnim);
            }

            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var iconBorder = new Border
            {
                Width = 32,
                Height = 32,
                CornerRadius = new CornerRadius(6),
                Margin = new Thickness(0, 0, 8, 0)
            };
            iconBorder.SetResourceReference(Border.BackgroundProperty, "BrushSquircle");

            var iconPath = new WpfPath
            {
                Data = (Geometry)FindResource(device.DeviceType == "mac" ? "IconLaptop" : "IconMobile"),
                Width = 15,
                Height = 15,
                Stretch = Stretch.Uniform,
                HorizontalAlignment = System.Windows.HorizontalAlignment.Center,
                VerticalAlignment = System.Windows.VerticalAlignment.Center
            };
            iconPath.SetResourceReference(WpfPath.FillProperty, "BrushTextPrimary");
            iconBorder.Child = iconPath;
            Grid.SetColumn(iconBorder, 0);
            grid.Children.Add(iconBorder);

            var infoPanel = new StackPanel
            {
                VerticalAlignment = VerticalAlignment.Center
            };

            var nameText = new TextBlock
            {
                Text = FormatFriendlyDeviceName(device.Name),
                FontSize = 12.5,
                FontWeight = FontWeights.SemiBold,
                TextTrimming = TextTrimming.CharacterEllipsis
            };
            nameText.SetResourceReference(TextBlock.ForegroundProperty, "BrushTextPrimary");

            var subText = new TextBlock
            {
                Text = GetDeviceSubtitle(device.Name),
                FontSize = 10,
                Margin = new Thickness(0, 1, 0, 0)
            };
            subText.SetResourceReference(TextBlock.ForegroundProperty, "BrushTextSecondary");

            infoPanel.Children.Add(nameText);
            infoPanel.Children.Add(subText);
            Grid.SetColumn(infoPanel, 1);
            grid.Children.Add(infoPanel);

            var pairBtn = new WpfButton
            {
                Content = "Connect",
                Style = (Style)FindResource("BtnOutline"),
                Height = 26,
                FontSize = 11,
                FontWeight = FontWeights.Medium,
                Padding = new Thickness(12, 3, 12, 3),
                VerticalAlignment = System.Windows.VerticalAlignment.Center
            };
            var targetDevice = device;
            pairBtn.Click += (s, e) =>
            {
                InitiatePairingWithDevice(targetDevice);
            };

            Grid.SetColumn(pairBtn, 2);
            grid.Children.Add(pairBtn);

            card.Child = grid;
            PanelDiscoveredDevices.Children.Add(card);
        }
    }

    private void InitiatePairingWithDevice(DiscoveredDevice device)
    {
        _pairingTimeoutCts?.Cancel();
        _pairingTimeoutCts?.Dispose();
        _pairingTimeoutCts = new CancellationTokenSource();
        var ct = _pairingTimeoutCts.Token;

        string pin = Random.Shared.Next(100000, 999999).ToString("D6");
        _selectedDeviceForPairing = device;
        _outgoingPairPin = pin;
        UpdateUI();

        _network.SendPairInvite(device, pin);

        Task.Run(async () =>
        {
            try
            {
                await Task.Delay(30000, ct);
                Dispatcher.Invoke(() =>
                {
                    if (_selectedDeviceForPairing != null)
                    {
                        _selectedDeviceForPairing = null;
                        _outgoingPairPin = null;
                        UpdateUI();
                    }
                });
            }
            catch (OperationCanceledException) { }
        });
    }

    private void BtnCancelOutgoingPair_Click(object sender, RoutedEventArgs e)
    {
        _pairingTimeoutCts?.Cancel();
        _pairingTimeoutCts?.Dispose();
        _pairingTimeoutCts = null;
        if (_selectedDeviceForPairing != null)
        {
            _network.SendPairDecline(_selectedDeviceForPairing.Host, _selectedDeviceForPairing.Port, "CANCELLED");
            _selectedDeviceForPairing = null;
            _outgoingPairPin = null;
        }
        UpdateUI();
    }

    private void BtnDeclineInvite_Click(object sender, RoutedEventArgs e)
    {
        if (_incomingPairInvite == null) return;
        var (device, _) = _incomingPairInvite.Value;
        _incomingPairInvite = null;
        _network.SendPairDecline(device.Host, device.Port, "DECLINED");
        UpdateUI();
    }

    private void BtnAcceptInvite_Click(object sender, RoutedEventArgs e)
    {
        if (_incomingPairInvite == null) return;
        var (device, pin) = _incomingPairInvite.Value;
        _incomingPairInvite = null;

        string actualPin = !string.IsNullOrWhiteSpace(pin) ? pin : Random.Shared.Next(100000, 999999).ToString("D6");
        _store.Config.PairedPeerName = device.Name;
        if (!string.IsNullOrEmpty(device.Id))
        {
            _store.Config.PairedPeerId = device.Id;
        }
        _store.Config.PairedPeerHost = device.Host;
        _store.Config.PairedPeerPort = device.Port;
        _store.Config.PairingPin = actualPin;
        _network.SendPairAccept(device.Host, device.Port);
        if (_network.SessionKey != null)
        {
            _store.Config.PairedSessionKeyHex = Convert.ToHexString(_network.SessionKey);
        }
        _store.Save(_store.Config);
        _network.InitiatePairing(device, actualPin);
        UpdateUI();
    }

    private void AddRecentClip(ClipItem item)
    {
        if (!_showHistory) return;

        _recentClips.RemoveAll(c => c.Equals(item));
        _recentClips.Insert(0, item);
        if (_recentClips.Count > 20)
        {
            _recentClips.RemoveRange(20, _recentClips.Count - 20);
        }

        RenderClipsList();
    }

    private void RenderClipsList()
    {
        PanelClips.Children.Clear();
        if (_recentClips.Count == 0)
        {
            CardEmptyHistory.Visibility = _isViewingHistory ? Visibility.Visible : Visibility.Collapsed;
            return;
        }

        CardEmptyHistory.Visibility = Visibility.Collapsed;
        var filtered = _recentClips.Where(clip =>
        {
            bool matchesFilter = _selectedFilter switch
            {
                "Text" => clip.Type == ClipContentType.Text,
                "Url" => clip.Type == ClipContentType.Url,
                "Media" => clip.Type is ClipContentType.Image or ClipContentType.File,
                _ => true
            };
            if (!matchesFilter) return false;
            if (string.IsNullOrEmpty(_searchQuery)) return true;
            string query = _searchQuery.ToLowerInvariant();
            return clip.PreviewText.ToLowerInvariant().Contains(query) ||
                   (clip.TextContent?.ToLowerInvariant().Contains(query) == true);
        }).ToList();

        var pinned = filtered.Where(c => _store.Config.PinnedClipIds.Contains(c.Id.ToString())).ToList();
        var unpinned = filtered.Where(c => !_store.Config.PinnedClipIds.Contains(c.Id.ToString())).ToList();
        var ordered = pinned.Concat(unpinned).ToList();

        if (ordered.Count == 0)
        {
            var noMatchBorder = new Border
            {
                Background = (WpfBrush)FindResource("BrushSurfaceSubtle"),
                BorderBrush = (WpfBrush)FindResource("BrushBorder"),
                BorderThickness = new Thickness(1),
                CornerRadius = (CornerRadius)FindResource("RadiusCard"),
                Padding = new Thickness(16, 20, 16, 20),
                Margin = new Thickness(0, 4, 0, 8)
            };
            var stack = new StackPanel { HorizontalAlignment = System.Windows.HorizontalAlignment.Center };
            var icon = new WpfPath
            {
                Data = (Geometry)FindResource("IconSearch"),
                Fill = (WpfBrush)FindResource("BrushTextMuted"),
                Width = 18,
                Height = 18,
                Stretch = Stretch.Uniform,
                HorizontalAlignment = System.Windows.HorizontalAlignment.Center,
                Margin = new Thickness(0, 0, 0, 8)
            };
            stack.Children.Add(icon);
            var title = new TextBlock
            {
                Text = "No matching clips",
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                HorizontalAlignment = System.Windows.HorizontalAlignment.Center,
                Margin = new Thickness(0, 0, 0, 2)
            };
            title.SetResourceReference(TextBlock.ForegroundProperty, "BrushTextPrimary");
            stack.Children.Add(title);
            var sub = new TextBlock
            {
                Text = "Try clearing your search or filter tags.",
                FontSize = 10,
                HorizontalAlignment = System.Windows.HorizontalAlignment.Center
            };
            sub.SetResourceReference(TextBlock.ForegroundProperty, "BrushTextSecondary");
            stack.Children.Add(sub);
            noMatchBorder.Child = stack;
            PanelClips.Children.Add(noMatchBorder);
            return;
        }

        foreach (var clip in ordered)
        {
            PanelClips.Children.Add(CreateClipCard(clip));
        }
    }

    private Border CreateClipCard(ClipItem clip)
    {
        bool isPinned = _store.Config.PinnedClipIds.Contains(clip.Id.ToString());

        var card = new Border
        {
            CornerRadius = new CornerRadius(8),
            Background = (WpfBrush)FindResource("BrushSurfaceSubtle"),
            BorderBrush = (WpfBrush)FindResource("BrushBorder"),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(8, 6, 8, 6),
            Margin = new Thickness(0, 0, 0, 5),
            Cursor = System.Windows.Input.Cursors.Hand,
            SnapsToDevicePixels = true
        };

        card.MouseEnter += (s, e) =>
        {
            card.SetResourceReference(Border.BackgroundProperty, "BrushSquircle");
            card.SetResourceReference(Border.BorderBrushProperty, "BrushPulse");
        };
        card.MouseLeave += (s, e) =>
        {
            card.SetResourceReference(Border.BackgroundProperty, "BrushSurfaceSubtle");
            card.SetResourceReference(Border.BorderBrushProperty, "BrushBorder");
        };

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        string iconKey = clip.Type switch
        {
            ClipContentType.Text => "IconText",
            ClipContentType.Url => "IconLink",
            ClipContentType.Image => "IconImage",
            ClipContentType.File => "IconFile",
            _ => "IconText"
        };

        var iconContainer = new Border
        {
            Width = 28,
            Height = 28,
            CornerRadius = new CornerRadius(6),
            Background = (WpfBrush)FindResource("BrushCard"),
            BorderBrush = (WpfBrush)FindResource("BrushBorder"),
            BorderThickness = new Thickness(1),
            Margin = new Thickness(0, 0, 8, 0),
            VerticalAlignment = VerticalAlignment.Center
        };

        var iconPath = new WpfPath
        {
            Width = 13,
            Height = 13,
            Stretch = Stretch.Uniform,
            HorizontalAlignment = System.Windows.HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        iconPath.SetResourceReference(WpfPath.DataProperty, iconKey);
        iconPath.SetResourceReference(WpfPath.FillProperty, "BrushTextPrimary");
        iconContainer.Child = iconPath;
        Grid.SetColumn(iconContainer, 0);
        grid.Children.Add(iconContainer);

        var contentStack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        var titleText = new TextBlock
        {
            Text = clip.PreviewText,
            FontSize = 11,
            TextTrimming = TextTrimming.CharacterEllipsis
        };
        titleText.SetResourceReference(TextBlock.ForegroundProperty, "BrushTextPrimary");
        contentStack.Children.Add(titleText);

        var timeText = new TextBlock
        {
            Text = GetRelativeTimeSpan(clip.Timestamp),
            FontSize = 9.5,
            Margin = new Thickness(0, 1, 0, 0)
        };
        timeText.SetResourceReference(TextBlock.ForegroundProperty, "BrushTextSecondary");
        contentStack.Children.Add(timeText);

        Grid.SetColumn(contentStack, 1);
        grid.Children.Add(contentStack);

        var actionsStack = new StackPanel
        {
            Orientation = System.Windows.Controls.Orientation.Horizontal,
            VerticalAlignment = VerticalAlignment.Center
        };

        var btnPin = new WpfButton
        {
            Style = (Style)FindResource("BtnGhost"),
            Padding = new Thickness(4, 2, 4, 2),
            Margin = new Thickness(0, 0, 2, 0)
        };
        var pinIcon = new WpfPath
        {
            Width = 10,
            Height = 10,
            Stretch = Stretch.Uniform
        };
        pinIcon.SetResourceReference(WpfPath.DataProperty, "IconPin");
        pinIcon.SetResourceReference(WpfPath.FillProperty, isPinned ? "BrushPulseText" : "BrushTextSecondary");
        btnPin.Content = pinIcon;
        btnPin.Click += (s, e) =>
        {
            e.Handled = true;
            TogglePinClip(clip);
        };
        actionsStack.Children.Add(btnPin);

        var btnDelete = new WpfButton
        {
            Style = (Style)FindResource("BtnGhost"),
            Padding = new Thickness(4, 2, 4, 2)
        };
        var trashIcon = new WpfPath
        {
            Width = 10,
            Height = 10,
            Stretch = Stretch.Uniform
        };
        trashIcon.SetResourceReference(WpfPath.DataProperty, "IconTrash");
        trashIcon.SetResourceReference(WpfPath.FillProperty, "BrushTextSecondary");
        btnDelete.Content = trashIcon;
        btnDelete.Click += (s, e) =>
        {
            e.Handled = true;
            DeleteClip(clip);
        };
        actionsStack.Children.Add(btnDelete);

        Grid.SetColumn(actionsStack, 2);
        grid.Children.Add(actionsStack);

        card.Child = grid;

        card.MouseLeftButtonUp += (s, e) =>
        {
            if (clip.Type == ClipContentType.File && !string.IsNullOrEmpty(clip.FileName))
            {
                string fullPath = System.IO.Path.Combine(_network.DownloadFolderPath, clip.FileName);
                if (File.Exists(fullPath))
                {
                    try
                    {
                        Process.Start(new ProcessStartInfo(fullPath) { UseShellExecute = true });
                        titleText.Text = "Opened!";
                    }
                    catch
                    {
                        _clipboard.WriteToClipboard(clip);
                        titleText.Text = "Copied!";
                    }
                }
                else
                {
                    _clipboard.WriteToClipboard(clip);
                    titleText.Text = "Copied!";
                }
            }
            else
            {
                _clipboard.WriteToClipboard(clip);
                titleText.Text = "Copied!";
            }

            Task.Delay(1000).ContinueWith(_ =>
            {
                Dispatcher.Invoke(() => titleText.Text = clip.PreviewText);
            });
        };

        var clipMenu = new System.Windows.Controls.ContextMenu();
        if (clip.Type == ClipContentType.File && !string.IsNullOrEmpty(clip.FileName))
        {
            string fullPath = System.IO.Path.Combine(_network.DownloadFolderPath, clip.FileName);
            var itemOpen = new System.Windows.Controls.MenuItem { Header = "Open File" };
            itemOpen.Click += (s, e) =>
            {
                if (File.Exists(fullPath))
                {
                    try { Process.Start(new ProcessStartInfo(fullPath) { UseShellExecute = true }); } catch { }
                }
            };
            clipMenu.Items.Add(itemOpen);

            var itemReveal = new System.Windows.Controls.MenuItem { Header = "Reveal in File Explorer" };
            itemReveal.Click += (s, e) =>
            {
                if (File.Exists(fullPath))
                {
                    try { Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{fullPath}\"") { UseShellExecute = true }); } catch { }
                }
                else if (Directory.Exists(_network.DownloadFolderPath))
                {
                    try { Process.Start(new ProcessStartInfo("explorer.exe", $"\"{_network.DownloadFolderPath}\"") { UseShellExecute = true }); } catch { }
                }
            };
            clipMenu.Items.Add(itemReveal);
            clipMenu.Items.Add(new System.Windows.Controls.Separator());
        }

        var itemCopy = new System.Windows.Controls.MenuItem { Header = "Copy to Clipboard" };
        itemCopy.Click += (s, e) => _clipboard.WriteToClipboard(clip);
        clipMenu.Items.Add(itemCopy);

        var itemPinMenu = new System.Windows.Controls.MenuItem { Header = isPinned ? "Unpin" : "Pin to Top" };
        itemPinMenu.Click += (s, e) => TogglePinClip(clip);
        clipMenu.Items.Add(itemPinMenu);

        var itemDelMenu = new System.Windows.Controls.MenuItem { Header = "Delete" };
        itemDelMenu.Click += (s, e) => DeleteClip(clip);
        clipMenu.Items.Add(itemDelMenu);

        card.ContextMenu = clipMenu;

        return card;
    }

    private void TogglePinClip(ClipItem item)
    {
        string idStr = item.Id.ToString();
        if (_store.Config.PinnedClipIds.Contains(idStr))
        {
            _store.Config.PinnedClipIds.Remove(idStr);
        }
        else
        {
            _store.Config.PinnedClipIds.Add(idStr);
        }
        _store.Save(_store.Config);
        RenderClipsList();
    }

    private void DeleteClip(ClipItem item)
    {
        _recentClips.RemoveAll(c => c.Id == item.Id);
        _store.Config.PinnedClipIds.Remove(item.Id.ToString());
        _store.Save(_store.Config);
        RenderClipsList();
    }

    private static string GetRelativeTimeSpan(long timestamp)
    {
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        long elapsedMs = Math.Max(0, now - timestamp);
        long seconds = elapsedMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (seconds < 60) return "Just now";
        if (minutes < 60) return $"{minutes}m ago";
        if (hours < 24) return $"{hours}h ago";
        if (days < 7) return $"{days}d ago";
        return $"{days / 7}w ago";
    }

    private void TxtSearchClips_TextChanged(object sender, TextChangedEventArgs e)
    {
        _searchQuery = TxtSearchClips.Text.Trim();
        TxtSearchPlaceholder.Visibility = string.IsNullOrEmpty(TxtSearchClips.Text) ? Visibility.Visible : Visibility.Collapsed;
        BtnClearSearch.Visibility = string.IsNullOrEmpty(_searchQuery) ? Visibility.Collapsed : Visibility.Visible;
        _searchDebounceCts?.Cancel();
        _searchDebounceCts?.Dispose();
        var cts = new CancellationTokenSource();
        _searchDebounceCts = cts;
        Task.Delay(120, cts.Token).ContinueWith(t =>
        {
            if (!t.IsCanceled)
            {
                Dispatcher.Invoke(RenderClipsList);
            }
        }, TaskScheduler.Default);
    }

    private void BtnClearSearch_Click(object sender, RoutedEventArgs e)
    {
        _searchDebounceCts?.Cancel();
        _searchDebounceCts?.Dispose();
        _searchDebounceCts = null;
        TxtSearchClips.Text = string.Empty;
        TxtSearchPlaceholder.Visibility = Visibility.Visible;
        _searchQuery = "";
        RenderClipsList();
    }

    private void BtnFilter_Click(object sender, RoutedEventArgs e)
    {
        if (sender is WpfButton btn && btn.Tag is string filter)
        {
            _selectedFilter = filter;
            SetFilterBtnStyle(BtnFilterAll, _selectedFilter == "All");
            SetFilterBtnStyle(BtnFilterText, _selectedFilter == "Text");
            SetFilterBtnStyle(BtnFilterLinks, _selectedFilter == "Url");
            SetFilterBtnStyle(BtnFilterMedia, _selectedFilter == "Media");
            ScrollClips.ScrollToTop();
            RenderClipsList();
        }
    }

    private void SetFilterBtnStyle(WpfButton btn, bool isSelected)
    {
        btn.SetResourceReference(WpfButton.StyleProperty, isSelected ? "BtnPill" : "BtnGhost");
    }

    private void Window_DragEnter(object sender, System.Windows.DragEventArgs e)
    {
        _dragEnterCounter++;
        _isDragOver = true;
        if (e.Data.GetDataPresent(System.Windows.DataFormats.FileDrop))
        {
            e.Effects = System.Windows.DragDropEffects.Copy;
            e.Handled = true;
            TxtDropPrompt.Text = _network.IsConnected
                ? $"Drop to send to {_network.ConnectedPeerName ?? "device"}"
                : "Connect a device to send files";
            OverlayDropTarget.Visibility = Visibility.Visible;
        }
        else
        {
            e.Effects = System.Windows.DragDropEffects.None;
        }
    }

    private void Window_DragOver(object sender, System.Windows.DragEventArgs e)
    {
        if (e.Data.GetDataPresent(System.Windows.DataFormats.FileDrop))
        {
            e.Effects = System.Windows.DragDropEffects.Copy;
            e.Handled = true;
        }
        else
        {
            e.Effects = System.Windows.DragDropEffects.None;
        }
    }

    private void Window_DragLeave(object sender, System.Windows.DragEventArgs e)
    {
        _dragEnterCounter--;
        if (_dragEnterCounter <= 0)
        {
            _dragEnterCounter = 0;
            _isDragOver = false;
            OverlayDropTarget.Visibility = Visibility.Collapsed;
        }
    }

    private void Window_Drop(object sender, System.Windows.DragEventArgs e)
    {
        _dragEnterCounter = 0;
        _isDragOver = false;
        OverlayDropTarget.Visibility = Visibility.Collapsed;
        if (e.Data.GetDataPresent(System.Windows.DataFormats.FileDrop))
        {
            string[] files = (string[])e.Data.GetData(System.Windows.DataFormats.FileDrop);
            if (files != null && files.Length > 0)
            {
                SendFiles(files);
            }
        }
    }

    private void SendFiles(string[] paths)
    {
        if (!_network.IsConnected)
        {
            ShowTransferError("No device connected");
            return;
        }
        Task.Run(async () =>
        {
            try
            {
                await _network.SendStreamingFilesAsync(paths, (id, name, sent, total, idx, count) =>
                {
                    Dispatcher.Invoke(() =>
                    {
                        CardTransferProgress.Visibility = Visibility.Visible;
                        TxtTransferFileName.Text = name;
                        TxtTransferCounter.Text = count > 1 ? $"{idx}/{count}" : "";
                        double fraction = total > 0 ? (double)sent / total * 100.0 : 0;
                        ProgressTransfer.Value = fraction;
                        double mbTransferred = sent / 1_048_576.0;
                        double mbTotal = total / 1_048_576.0;
                        TxtTransferBytes.Text = $"{mbTransferred:0.0} / {mbTotal:0.0} MB";

                        var (speedMb, eta) = CalculateRollingSpeedAndEta(id, sent, total);
                        if (speedMb > 0)
                        {
                            TxtTransferSpeed.Text = !string.IsNullOrEmpty(eta) ? $"{speedMb:0.0} MB/s · {eta}" : $"{speedMb:0.0} MB/s";
                        }
                        else
                        {
                            TxtTransferSpeed.Text = total > 0 ? "Calculating..." : "";
                        }
                    });
                });

                Dispatcher.Invoke(() =>
                {
                    CardTransferProgress.Visibility = Visibility.Collapsed;
                    TxtSendMedia.Text = "Sent!";
                    TxtSendMedia.SetResourceReference(TextBlock.ForegroundProperty, "BrushPulseText");
                    IconSendMediaPath.SetResourceReference(WpfPath.FillProperty, "BrushPulseText");
                    Task.Delay(1200).ContinueWith(_ =>
                    {
                        Dispatcher.Invoke(() =>
                        {
                            TxtSendMedia.Text = "Send Media";
                            TxtSendMedia.SetResourceReference(TextBlock.ForegroundProperty, "BrushTextPrimary");
                            IconSendMediaPath.SetResourceReference(WpfPath.FillProperty, "BrushBadgeMediaFg");
                        });
                    });
                });
            }
            catch (OperationCanceledException)
            {
                Dispatcher.Invoke(() =>
                {
                    ResetTransferSpeedTracking();
                    CardTransferProgress.Visibility = Visibility.Collapsed;
                    ShowTransferError("Transfer cancelled");
                });
            }
            catch
            {
                Dispatcher.Invoke(() =>
                {
                    ResetTransferSpeedTracking();
                    CardTransferProgress.Visibility = Visibility.Collapsed;
                    ShowTransferError("Failed to send files");
                });
            }
            finally
            {
                Dispatcher.Invoke(() => ResetTransferSpeedTracking());
            }
        });
    }

    private void SendFile(string path)
    {
        SendFiles(new[] { path });
    }

    private void ShowTransferError(string message)
    {
        TxtTransferError.Text = message;
        BannerTransferError.Opacity = 0.0;
        BannerTransferError.Visibility = Visibility.Visible;
        var anim = new DoubleAnimation(0.0, 1.0, new Duration(TimeSpan.FromMilliseconds(150)))
        {
            EasingFunction = new QuarticEase { EasingMode = EasingMode.EaseOut }
        };
        BannerTransferError.BeginAnimation(UIElement.OpacityProperty, anim);
    }

    private void BtnDismissTransferError_Click(object sender, RoutedEventArgs e)
    {
        var anim = new DoubleAnimation(BannerTransferError.Opacity, 0.0, new Duration(TimeSpan.FromMilliseconds(120)))
        {
            EasingFunction = new QuarticEase { EasingMode = EasingMode.EaseIn }
        };
        anim.Completed += (s, ev) =>
        {
            BannerTransferError.Visibility = Visibility.Collapsed;
        };
        BannerTransferError.BeginAnimation(UIElement.OpacityProperty, anim);
    }

    private void UpdateHistoryVisibility()
    {
        if (!_isViewingHistory)
        {
            SectionHistoryHeader.Visibility = Visibility.Collapsed;
            CardEmptyHistory.Visibility = Visibility.Collapsed;
            CardDirectSync.Visibility = Visibility.Collapsed;
            ScrollClips.Visibility = Visibility.Collapsed;
            BorderSearchClips.Visibility = Visibility.Collapsed;
            GridFilters.Visibility = Visibility.Collapsed;
            return;
        }

        SectionHistoryHeader.Visibility = Visibility.Visible;
        bool isDirectSync = !_showHistory;
        bool hasClips = _recentClips.Count > 0;

        CardDirectSync.Visibility = isDirectSync ? Visibility.Visible : Visibility.Collapsed;
        CardEmptyHistory.Visibility = (!isDirectSync && !hasClips) ? Visibility.Visible : Visibility.Collapsed;
        ScrollClips.Visibility = (!isDirectSync && hasClips) ? Visibility.Visible : Visibility.Collapsed;
        BorderSearchClips.Visibility = (!isDirectSync && hasClips) ? Visibility.Visible : Visibility.Collapsed;
        GridFilters.Visibility = (!isDirectSync && hasClips) ? Visibility.Visible : Visibility.Collapsed;
        BtnClearHistory.Visibility = (!isDirectSync && hasClips) ? Visibility.Visible : Visibility.Collapsed;
        RenderClipsList();
    }

    private void BtnBackToPairedDevice_Click(object sender, RoutedEventArgs e)
    {
        _isViewingHistory = false;
        UpdateUI();
        InvalidateMeasure();
        UpdateLayout();
        PositionAtTaskbarCorner();
    }

    private void BtnOpenSyncHistory_Click(object sender, RoutedEventArgs e)
    {
        _isViewingHistory = true;
        UpdateUI();
        InvalidateMeasure();
        UpdateLayout();
        PositionAtTaskbarCorner();
    }

    private void BtnClearHistory_Click(object sender, RoutedEventArgs e)
    {
        _recentClips.RemoveAll(c => !_store.Config.PinnedClipIds.Contains(c.Id.ToString()));
        RenderClipsList();
        InvalidateMeasure();
        UpdateLayout();
        PositionAtTaskbarCorner();
    }

    private void BtnCancelTransfer_Click(object sender, RoutedEventArgs e)
    {
        _network.CancelActiveTransfer();
        ResetTransferSpeedTracking();
        CardTransferProgress.Visibility = Visibility.Collapsed;
        ShowTransferError("Transfer cancelled");
    }

    private void BtnSendMedia_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFileDialog
        {
            Title = "Select Media or Files to Send",
            Multiselect = true
        };
        if (dlg.ShowDialog() == true && dlg.FileNames.Length > 0)
        {
            SendFiles(dlg.FileNames);
        }
    }

    private void BtnPushClipboard_Click(object sender, RoutedEventArgs e)
    {
        var clip = ClipboardMonitor.ReadPrimaryClip();
        if (clip != null)
        {
            _network.SendClip(clip);
            AddRecentClip(clip);
            TxtPushClipboard.Text = "Synced!";
            TxtPushClipboard.SetResourceReference(TextBlock.ForegroundProperty, "BrushPulseText");
            IconPushClipboardPath.SetResourceReference(WpfPath.FillProperty, "BrushPulseText");
            Task.Delay(1200).ContinueWith(_ =>
            {
                Dispatcher.Invoke(() =>
                {
                    TxtPushClipboard.Text = "Push Clipboard";
                    TxtPushClipboard.SetResourceReference(TextBlock.ForegroundProperty, "BrushTextPrimary");
                    IconPushClipboardPath.SetResourceReference(WpfPath.FillProperty, "BrushBadgeClipFg");
                });
            });
        }
    }

    private void BtnUnpair_Click(object sender, RoutedEventArgs e)
    {
        _network.SendDisconnect();
        _pairingTimeoutCts?.Cancel();
        _pairingTimeoutCts?.Dispose();
        _pairingTimeoutCts = null;
        _store.ClearPairing();
        _network.DisconnectAll();
        _selectedDeviceForPairing = null;
        _outgoingPairPin = null;
        _incomingPairInvite = null;
        UpdateUI();
    }

    private void BtnSettings_Click(object sender, RoutedEventArgs e)
    {
        var menu = new System.Windows.Controls.ContextMenu
        {
            PlacementTarget = BtnSettings,
            Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom,
            HorizontalOffset = -130
        };

        _isMenuOpen = true;
        menu.Closed += (s, ev) =>
        {
            _isMenuOpen = false;
            if (!IsKeyboardFocusWithin && !IsActive)
            {
                Hide();
            }
        };
        var itemHistory = new System.Windows.Controls.MenuItem
        {
            Header = "Clip History",
            IsCheckable = true,
            IsChecked = _showHistory
        };
        itemHistory.Click += (s, ev) =>
        {
            _showHistory = !_showHistory;
            itemHistory.IsChecked = _showHistory;
            _store.Config.ShowSyncHistory = _showHistory;
            _store.Save(_store.Config);
            if (!_showHistory)
            {
                _recentClips.RemoveAll(c => !_store.Config.PinnedClipIds.Contains(c.Id.ToString()));
            }
            _network.SendConfigSync(!_showHistory);
            UpdateUI();
        };
        menu.Items.Add(itemHistory);

        var itemTheme = new System.Windows.Controls.MenuItem { Header = "Theme" };
        var itemThemeSystem = new System.Windows.Controls.MenuItem
        {
            Header = "System Default",
            IsCheckable = true,
            IsChecked = _store.Config.Theme == "system"
        };
        var itemThemeLight = new System.Windows.Controls.MenuItem
        {
            Header = "Light",
            IsCheckable = true,
            IsChecked = _store.Config.Theme == "light"
        };
        var itemThemeDark = new System.Windows.Controls.MenuItem
        {
            Header = "Dark",
            IsCheckable = true,
            IsChecked = _store.Config.Theme == "dark"
        };

        void SetTheme(string theme)
        {
            _store.Config.Theme = theme;
            _store.Save(_store.Config);
            itemThemeSystem.IsChecked = theme == "system";
            itemThemeLight.IsChecked = theme == "light";
            itemThemeDark.IsChecked = theme == "dark";
            ThemeManager.ApplyTheme(theme, animate: true);
        }

        itemThemeSystem.Click += (s, ev) => SetTheme("system");
        itemThemeLight.Click += (s, ev) => SetTheme("light");
        itemThemeDark.Click += (s, ev) => SetTheme("dark");

        itemTheme.Items.Add(itemThemeSystem);
        itemTheme.Items.Add(itemThemeLight);
        itemTheme.Items.Add(itemThemeDark);
        menu.Items.Add(itemTheme);

        var itemStartup = new System.Windows.Controls.MenuItem
        {
            Header = "Start with Windows",
            IsCheckable = true,
            IsChecked = IsStartWithWindowsEnabled()
        };
        itemStartup.Click += (s, ev) =>
        {
            bool target = !IsStartWithWindowsEnabled();
            SetStartWithWindows(target);
            itemStartup.IsChecked = target;
        };
        menu.Items.Add(itemStartup);

        menu.Items.Add(new System.Windows.Controls.Separator());
        var itemUpdate = new System.Windows.Controls.MenuItem { Header = "Check for Updates..." };
        itemUpdate.Click += async (s, ev) =>
        {
            ShowTransferError("Checking for updates...");
            var res = await RapiDrop.Core.Network.UpdateChecker.CheckForUpdatesAsync();
            if (res.IsUpdateAvailable)
            {
                if (!string.IsNullOrEmpty(res.AssetUrl))
                {
                    ShowTransferError($"Downloading v{res.LatestVersion}...");
                    string? installer = await RapiDrop.Core.Network.UpdateChecker.DownloadInstallerAsync(res.AssetUrl, res.LatestVersion, (read, total, speed) =>
                    {
                        if (total > 0)
                        {
                            double mbRead = read / 1_048_576.0;
                            double mbTotal = total / 1_048_576.0;
                            double mbSpeed = speed / 1_048_576.0;
                            Dispatcher.Invoke(() => ShowTransferError($"Downloading: {mbRead:F1}/{mbTotal:F1} MB ({mbSpeed:F1} MB/s)"));
                        }
                    });
                    if (!string.IsNullOrEmpty(installer) && File.Exists(installer))
                    {
                        try
                        {
                            Process.Start(new ProcessStartInfo(installer) { UseShellExecute = true });
                            System.Windows.Application.Current.Shutdown();
                        }
                        catch
                        {
                            ShowTransferError("Failed to launch installer");
                        }
                    }
                    else
                    {
                        try { Process.Start(new ProcessStartInfo(res.ReleaseUrl) { UseShellExecute = true }); } catch { }
                    }
                }
                else
                {
                    try { Process.Start(new ProcessStartInfo(res.ReleaseUrl) { UseShellExecute = true }); } catch { }
                    ShowTransferError($"Update {res.LatestVersion} available");
                }
            }
            else if (res.ErrorMessage != null)
            {
                ShowTransferError("Unable to check for updates");
            }
            else
            {
                ShowTransferError("RapiDrop is up to date (v1.0.0)");
            }
        };
        menu.Items.Add(itemUpdate);

        menu.Items.Add(new System.Windows.Controls.Separator());

        var itemGithub = new System.Windows.Controls.MenuItem { Header = "GitHub Repository" };
        itemGithub.Click += (s, ev) =>
        {
            try { Process.Start(new ProcessStartInfo("https://github.com/Prabotics/RapiDrop") { UseShellExecute = true }); } catch { }
        };
        menu.Items.Add(itemGithub);

        var itemIssue = new System.Windows.Controls.MenuItem { Header = "Report an Issue" };
        itemIssue.Click += (s, ev) =>
        {
            try { Process.Start(new ProcessStartInfo("https://github.com/Prabotics/RapiDrop/issues") { UseShellExecute = true }); } catch { }
        };
        menu.Items.Add(itemIssue);

        menu.Items.Add(new System.Windows.Controls.Separator());

        var itemAbout = new System.Windows.Controls.MenuItem
        {
            Header = "About RapiDrop",
            InputGestureText = "v1.0.0"
        };
        itemAbout.Click += (s, ev) =>
        {
            ShowTransferError("RapiDrop v1.0.0 — P2P Clipboard Sync");
        };
        menu.Items.Add(itemAbout);

        var itemQuit = new System.Windows.Controls.MenuItem
        {
            Header = "Quit RapiDrop",
            InputGestureText = "Alt+F4"
        };
        itemQuit.Click += (s, ev) =>
        {
            PerformApplicationExit();
        };
        menu.Items.Add(itemQuit);

        menu.IsOpen = true;
    }

    private void BtnTurnOnHistory_Click(object sender, RoutedEventArgs e)
    {
        _showHistory = true;
        _store.Config.ShowSyncHistory = true;
        _store.Save(_store.Config);
        _network.SendConfigSync(!_showHistory);
        UpdateUI();
    }
    private const string RunRegistryKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunAppName = "RapiDrop";

    private static bool IsStartWithWindowsEnabled()
    {
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows)) return false;
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunRegistryKey, false);
            return key?.GetValue(RunAppName) != null;
        }
        catch
        {
            return false;
        }
    }

    private static void SetStartWithWindows(bool enable)
    {
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows)) return;
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunRegistryKey, true);
            if (key == null) return;
            if (enable)
            {
                string exePath = Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule?.FileName ?? "";
                if (!string.IsNullOrEmpty(exePath))
                {
                    key.SetValue(RunAppName, $"\"{exePath}\" --autostart");
                }
            }
            else
            {
                key.DeleteValue(RunAppName, false);
            }
        }
        catch
        {
        }
    }

    public static string FormatFriendlyDeviceName(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) return "Device";
        return name.Trim().Replace("\"", "").Replace("\\", "").Trim();
    }

    private static bool IsSameDeviceName(string? name1, string? name2)
    {
        if (string.IsNullOrWhiteSpace(name1) || string.IsNullOrWhiteSpace(name2)) return false;
        string n1 = FormatFriendlyDeviceName(name1).ToLowerInvariant().Trim();
        string n2 = FormatFriendlyDeviceName(name2).ToLowerInvariant().Trim();
        return n1 == n2 || n1.Contains(n2) || n2.Contains(n1);
    }

    private static string GetDeviceSubtitle(string name)
    {
        string friendly = FormatFriendlyDeviceName(name);
        string lower = friendly.ToLowerInvariant();
        if (lower.Contains("mac") || lower.Contains("book")) return "Mac";
        if (lower.Contains("pc") || lower.Contains("windows") || lower.Contains("desktop")) return "Windows PC";
        if (lower.Contains("ipad") || lower.Contains("tablet")) return "iPad";
        if (lower.Contains("pixel")) return "Pixel Phone";
        if (lower.Contains("redmi") || lower.Contains("galaxy") || lower.Contains("oneplus")) return "Android Phone";
        return "Nearby Device";
    }
}
