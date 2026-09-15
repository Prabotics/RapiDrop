using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows;
using RapiDrop.Core.Network;
using RapiDrop.Core.Security;
using RapiDrop.UI.Clipboard;
using RapiDrop.UI.Theme;
using RapiDrop.UI.Views;
using WpfApplication = System.Windows.Application;
namespace RapiDrop.UI;

public partial class App : WpfApplication
{
    private CredentialStore? _store;
    private NetworkEngine? _network;
    private ClipboardMonitor? _clipboard;
    private TrayIconManager? _tray;
    private TrayFlyoutWindow? _window;
    private static Mutex? _singleInstanceMutex;
    private const string MutexName = @"Global\RapiDrop_SingleInstance_Mutex";

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr FindWindow(string? lpClassName, string? lpWindowName);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool PostMessage(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);

    private static void LogFatalError(string source, Exception? ex)
    {
        try
        {
            string msg = $"[{DateTime.UtcNow:O}] FATAL {source}: {ex}\n";
            string appData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string dir = System.IO.Path.Combine(appData, "RapiDrop");
            System.IO.Directory.CreateDirectory(dir);
            System.IO.File.AppendAllText(System.IO.Path.Combine(dir, "crash.log"), msg);
            try
            {
                System.IO.File.AppendAllText(System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "rapidrop_error.log"), msg);
            }
            catch { }
            System.Windows.MessageBox.Show(
                $"RapiDrop encountered an error:\n\n{ex?.Message}\n\nDetails have been logged to:\n{System.IO.Path.Combine(dir, "crash.log")}",
                "RapiDrop Error",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
        }
        catch { }
    }

    protected override void OnStartup(StartupEventArgs e)
    {
        AppDomain.CurrentDomain.UnhandledException += (s, args) =>
        {
            LogFatalError("AppDomain.UnhandledException", args.ExceptionObject as Exception);
        };
        DispatcherUnhandledException += (s, args) =>
        {
            LogFatalError("DispatcherUnhandledException", args.Exception);
            args.Handled = true;
        };
        TaskScheduler.UnobservedTaskException += (s, args) =>
        {
            LogFatalError("TaskScheduler.UnobservedTaskException", args.Exception);
            args.SetObserved();
        };

        bool isNewInstance = false;
        try
        {
            _singleInstanceMutex = new Mutex(true, MutexName, out isNewInstance);
        }
        catch (AbandonedMutexException)
        {
            isNewInstance = true;
        }
        catch
        {
            isNewInstance = true;
        }

        if (!isNewInstance)
        {
            int currentPid = Environment.ProcessId;
            var otherInstances = System.Diagnostics.Process.GetProcessesByName("RapiDrop")
                .Where(p => p.Id != currentPid)
                .ToList();

            if (otherInstances.Count > 0)
            {
                IntPtr existingHwnd = FindWindow(null, "RapiDropTrayIconHost");
                if (existingHwnd == IntPtr.Zero)
                {
                    existingHwnd = FindWindow("RapiDropTrayIconHost", null);
                }
                if (existingHwnd != IntPtr.Zero)
                {
                    PostMessage(existingHwnd, TrayIconManager.WM_ACTIVATE_INSTANCE, IntPtr.Zero, IntPtr.Zero);
                }
                Shutdown(0);
                return;
            }
        }

        try
        {
            base.OnStartup(e);

            _store = new CredentialStore();
            ThemeManager.Initialize(_store.Config.Theme);

        byte[]? sessionKey = !string.IsNullOrEmpty(_store.Config.PairedSessionKeyHex)
            ? Convert.FromHexString(_store.Config.PairedSessionKeyHex)
            : (!string.IsNullOrEmpty(_store.Config.PairingPin) ? CryptoEngine.DeriveKeyFromPin(_store.Config.PairingPin) : null);
        _network = new NetworkEngine();
        _clipboard = new ClipboardMonitor();
        _tray = new TrayIconManager();

        _tray.Initialize();
        _clipboard.Start();

        _network.Start(
            _store.Config.PairedPeerName,
            _store.Config.PairedPeerHost,
            _store.Config.PairedPeerPort > 0 ? _store.Config.PairedPeerPort : null,
            sessionKey,
            _store.Config.PairedPeerId);
        _window = new TrayFlyoutWindow(_store, _network, _clipboard, _tray);

        _tray.InstanceActivationRequested += () =>
        {
            Dispatcher.Invoke(() =>
            {
                if (_window != null)
                {
                    _window.Show();
                    _window.Activate();
                }
            });
        };

        _tray.NotificationClicked += () =>
        {
            Dispatcher.Invoke(() =>
            {
                if (_window != null)
                {
                    _window.Show();
                    _window.Activate();
                }
            });
        };
        _tray.TrayRightClicked += () =>
        {
            Dispatcher.Invoke(() =>
            {
                var menu = new System.Windows.Controls.ContextMenu
                {
                    Placement = System.Windows.Controls.Primitives.PlacementMode.MousePoint
                };

                var itemOpen = new System.Windows.Controls.MenuItem { Header = "Open RapiDrop" };
                itemOpen.Click += (s, ev) =>
                {
                    if (_window != null)
                    {
                        _window.Show();
                        _window.Activate();
                    }
                };
                menu.Items.Add(itemOpen);

                menu.Items.Add(new System.Windows.Controls.Separator());

                var itemQuit = new System.Windows.Controls.MenuItem { Header = "Quit RapiDrop" };
                itemQuit.Click += (s, ev) =>
                {
                    TrayFlyoutWindow.PerformApplicationExit();
                };
                menu.Items.Add(itemQuit);

                menu.IsOpen = true;
            });
        };

        bool isSilent = e.Args.Any(a => string.Equals(a, "--silent", StringComparison.OrdinalIgnoreCase) ||
                                        string.Equals(a, "--minimized", StringComparison.OrdinalIgnoreCase) ||
                                        string.Equals(a, "--autostart", StringComparison.OrdinalIgnoreCase));

        if (!isSilent)
        {
            _window.Show();
            _window.Activate();
            _ = Task.Delay(2500).ContinueWith(_ => TrayFlyoutWindow.TrimWorkingSet());
        }
        else
        {
            _ = Task.Delay(1000).ContinueWith(_ => TrayFlyoutWindow.TrimWorkingSet());
        }

        if (!_store.Config.HasShownWelcomeNotification)
        {
            _tray.ShowNotification("RapiDrop is Running", "RapiDrop is active in your system tray. Click the icon anytime to open, or right-click to exit.");
            _store.Config.HasShownWelcomeNotification = true;
            _store.Save(_store.Config);
        }
    }
    catch (Exception ex)
    {
        LogFatalError("OnStartup", ex);
        Shutdown(1);
    }
}
    protected override void OnExit(ExitEventArgs e)
    {
        _network?.Dispose();
        _clipboard?.Dispose();
        _tray?.Dispose();
        if (_singleInstanceMutex != null)
        {
            try
            {
                _singleInstanceMutex.ReleaseMutex();
            }
            catch { }
            _singleInstanceMutex.Dispose();
            _singleInstanceMutex = null;
        }
        base.OnExit(e);
    }
}
