using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace RapiDrop.UI.Views;

public sealed class TrayIconManager : IDisposable
{
    private const int WM_USER = 0x0400;
    public const int WM_TRAYICON = WM_USER + 101;
    public const int WM_ACTIVATE_INSTANCE = WM_USER + 201;

    private const int NIM_ADD = 0x00000000;
    private const int NIM_MODIFY = 0x00000001;
    private const int NIM_DELETE = 0x00000002;
    private const int NIM_SETVERSION = 0x00000004;

    private const int NOTIFYICON_VERSION_4 = 4;

    private const int NIF_MESSAGE = 0x00000001;
    private const int NIF_ICON = 0x00000002;
    private const int NIF_TIP = 0x00000004;
    private const int NIF_STATE = 0x00000008;
    private const int NIF_INFO = 0x00000010;
    private const int NIF_SHOWTIP = 0x00000080;

    private const int NIIF_INFO = 0x00000001;

    private const int NIN_SELECT = WM_USER;
    private const int NIN_KEYSELECT = WM_USER + 1;
    private const int NIN_BALLOONUSERCLICK = 0x0400 + 5;

    private const int WM_LBUTTONDOWN = 0x0201;
    private const int WM_LBUTTONUP = 0x0202;
    private const int WM_LBUTTONDBLCLK = 0x0203;
    private const int WM_RBUTTONDOWN = 0x0204;
    private const int WM_RBUTTONUP = 0x0205;
    private const int WM_CONTEXTMENU = 0x007B;
    public event Action? TrayLeftClicked;
    public event Action? TrayRightClicked;
    public event Action? NotificationClicked;
    public event Action? InstanceActivationRequested;

    private HwndSource? _hwndSource;
    private IntPtr _hwnd = IntPtr.Zero;
    private IntPtr _hIcon = IntPtr.Zero;
    private Icon? _drawingIcon;
    private bool _isAdded;
    private long _lastLeftClickTime = 0;
    private long _lastRightClickTime = 0;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NOTIFYICONDATA
    {
        public int cbSize;
        public IntPtr hWnd;
        public int uID;
        public int uFlags;
        public int uCallbackMessage;
        public IntPtr hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string szTip;
        public int dwState;
        public int dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
        public string szInfo;
        public int uTimeoutOrVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
        public string szInfoTitle;
        public int dwInfoFlags;
        public Guid guidItem;
        public IntPtr hBalloonIcon;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern bool Shell_NotifyIcon(int dwMessage, ref NOTIFYICONDATA lpdata);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr hIcon);

    public void Initialize()
    {
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows)) return;

        var parameters = new HwndSourceParameters("RapiDropTrayIconHost")
        {
            HwndSourceHook = WndProc,
            WindowStyle = 0
        };

        _hwndSource = new HwndSource(parameters);
        _hwnd = _hwndSource.Handle;

        CreateDefaultIcon();
        AddTrayIcon();
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WM_TRAYICON)
        {
            int evt = (short)(lParam.ToInt64() & 0xFFFF);
            if (evt == WM_LBUTTONUP || evt == NIN_SELECT || evt == NIN_KEYSELECT || evt == WM_LBUTTONDBLCLK)
            {
                handled = true;
                long now = Environment.TickCount64;
                if (now - _lastLeftClickTime > 250)
                {
                    _lastLeftClickTime = now;
                    TrayLeftClicked?.Invoke();
                }
            }
            else if (evt == WM_RBUTTONUP || evt == WM_CONTEXTMENU)
            {
                handled = true;
                long now = Environment.TickCount64;
                if (now - _lastRightClickTime > 250)
                {
                    _lastRightClickTime = now;
                    TrayRightClicked?.Invoke();
                }
            }
            else if (evt == NIN_BALLOONUSERCLICK)
            {
                handled = true;
                NotificationClicked?.Invoke();
            }
        }
        else if (msg == WM_ACTIVATE_INSTANCE)
        {
            handled = true;
            InstanceActivationRequested?.Invoke();
        }
        return IntPtr.Zero;
    }

    private void CreateDefaultIcon()
    {
        try
        {
            var asm = typeof(TrayIconManager).Assembly;
            var resourceNames = asm.GetManifestResourceNames();
            string? match = resourceNames.FirstOrDefault(n => n.EndsWith("RapiDrop_tray.ico", StringComparison.OrdinalIgnoreCase))
                         ?? resourceNames.FirstOrDefault(n => n.EndsWith("RapiDrop.ico", StringComparison.OrdinalIgnoreCase));
            if (match != null)
            {
                using var stream = asm.GetManifestResourceStream(match);
                if (stream != null)
                {
                    if (_hIcon != IntPtr.Zero) DestroyIcon(_hIcon);
                    _drawingIcon?.Dispose();
                    _drawingIcon = new Icon(stream, 32, 32);
                    _hIcon = _drawingIcon.Handle;
                    return;
                }
            }

            string? procPath = Environment.ProcessPath;
            if (!string.IsNullOrEmpty(procPath) && System.IO.File.Exists(procPath))
            {
                var exeIcon = Icon.ExtractAssociatedIcon(procPath);
                if (exeIcon != null)
                {
                    if (_hIcon != IntPtr.Zero) DestroyIcon(_hIcon);
                    _drawingIcon?.Dispose();
                    _drawingIcon = exeIcon;
                    _hIcon = _drawingIcon.Handle;
                    return;
                }
            }

            string baseDir = AppDomain.CurrentDomain.BaseDirectory;
            string trayIconPath = System.IO.Path.Combine(baseDir, "RapiDrop_tray.ico");
            if (!System.IO.File.Exists(trayIconPath))
            {
                trayIconPath = System.IO.Path.Combine(baseDir, "RapiDrop.ico");
            }
            if (System.IO.File.Exists(trayIconPath))
            {
                if (_hIcon != IntPtr.Zero) DestroyIcon(_hIcon);
                _drawingIcon?.Dispose();
                _drawingIcon = new Icon(trayIconPath, 32, 32);
                _hIcon = _drawingIcon.Handle;
                return;
            }

            using var bmp = new Bitmap(32, 32);
            using (var g = Graphics.FromImage(bmp))
            {
                g.Clear(System.Drawing.Color.Transparent);
                g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                g.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.HighQuality;

                bool isDark = Theme.ThemeManager.IsWindowsDarkTheme();
                var primaryColor = isDark
                    ? System.Drawing.Color.FromArgb(16, 185, 129)
                    : System.Drawing.Color.FromArgb(5, 150, 105);
                var whiteColor = System.Drawing.Color.White;

                using var pChassis = new Pen(primaryColor, 1.5f);
                using var bChassis = new SolidBrush(primaryColor);
                using var bWhite = new SolidBrush(whiteColor);

                g.FillRectangle(bChassis, 2, 8, 16, 11);
                g.FillRectangle(bWhite, 4, 10, 12, 7);
                g.FillRectangle(bChassis, 1, 19, 18, 2);

                g.FillRectangle(bChassis, 19, 9, 11, 19);
                g.FillRectangle(bWhite, 21, 11, 7, 13);
                g.FillRectangle(bChassis, 23, 25, 3, 2);

                g.FillRectangle(bWhite, 10, 8, 11, 15);
                g.DrawRectangle(pChassis, 10, 8, 11, 15);
                g.FillRectangle(bChassis, 13, 6, 5, 3);
                g.FillRectangle(bChassis, 12, 11, 7, 1);
                g.FillRectangle(bChassis, 12, 14, 7, 1);
                g.FillRectangle(bChassis, 12, 17, 5, 1);
            }
            if (_hIcon != IntPtr.Zero) DestroyIcon(_hIcon);
            _drawingIcon?.Dispose();
            _drawingIcon = Icon.FromHandle(bmp.GetHicon());
            _hIcon = _drawingIcon.Handle;
        }
        catch
        {
            _hIcon = IntPtr.Zero;
        }
    }

    private void AddTrayIcon()
    {
        if (_hwnd == IntPtr.Zero || _hIcon == IntPtr.Zero) return;

        var data = new NOTIFYICONDATA
        {
            cbSize = Marshal.SizeOf<NOTIFYICONDATA>(),
            hWnd = _hwnd,
            uID = 1,
            uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP,
            uCallbackMessage = WM_TRAYICON,
            hIcon = _hIcon,
            szTip = "RapiDrop"
        };

        _isAdded = Shell_NotifyIcon(NIM_ADD, ref data);
        if (_isAdded)
        {
            data.uTimeoutOrVersion = NOTIFYICON_VERSION_4;
            Shell_NotifyIcon(NIM_SETVERSION, ref data);
        }
    }
    public void ShowNotification(string title, string message)
    {
        if (!_isAdded || _hwnd == IntPtr.Zero) return;

        var data = new NOTIFYICONDATA
        {
            cbSize = Marshal.SizeOf<NOTIFYICONDATA>(),
            hWnd = _hwnd,
            uID = 1,
            uFlags = NIF_INFO,
            szInfoTitle = title,
            szInfo = message,
            dwInfoFlags = NIIF_INFO
        };

        Shell_NotifyIcon(NIM_MODIFY, ref data);
    }

    public void Dispose()
    {
        if (_isAdded && _hwnd != IntPtr.Zero)
        {
            var data = new NOTIFYICONDATA
            {
                cbSize = Marshal.SizeOf<NOTIFYICONDATA>(),
                hWnd = _hwnd,
                uID = 1
            };
            Shell_NotifyIcon(NIM_DELETE, ref data);
            _isAdded = false;
        }

        if (_hIcon != IntPtr.Zero)
        {
            DestroyIcon(_hIcon);
            _hIcon = IntPtr.Zero;
        }

        _drawingIcon?.Dispose();
        _hwndSource?.Dispose();
    }
}
