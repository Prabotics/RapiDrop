using System.Runtime.InteropServices;
using Microsoft.Win32;
using System.Windows.Media;
using System.Windows.Media.Animation;
using WpfApplication = System.Windows.Application;
using WpfColor = System.Windows.Media.Color;

namespace RapiDrop.UI.Theme;

public static class ThemeManager
{
    public static string CurrentThemeMode { get; private set; } = "system";
    public static event Action<bool>? ThemeChanged;
    private static bool _isInitialized = false;

    public static void Initialize(string initialTheme)
    {
        CurrentThemeMode = initialTheme;
        ApplyTheme(initialTheme, animate: false);

        if (!_isInitialized)
        {
            _isInitialized = true;
            if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
            {
                try
                {
                    SystemEvents.UserPreferenceChanged += (s, e) =>
                    {
                        if (CurrentThemeMode == "system" &&
                            (e.Category == UserPreferenceCategory.General || e.Category == UserPreferenceCategory.Color))
                        {
                            WpfApplication.Current?.Dispatcher.InvokeAsync(() =>
                            {
                                if (CurrentThemeMode == "system")
                                {
                                    ApplyTheme("system", animate: true);
                                }
                            });
                        }
                    };
                }
                catch { }
            }
        }
    }

    public static bool IsWindowsDarkTheme()
    {
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows)) return true;
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            var val = key?.GetValue("AppsUseLightTheme");
            if (val is int intVal)
            {
                return intVal == 0;
            }
        }
        catch { }
        return true;
    }

    public static readonly Dictionary<string, (WpfColor Dark, WpfColor Light)> ThemeColors = new()
    {
        ["BrushCanvas"] = (WpfColor.FromRgb(0x0F, 0x10, 0x15), WpfColor.FromRgb(0xF8, 0xFA, 0xFC)),
        ["BrushCard"] = (WpfColor.FromRgb(0x16, 0x17, 0x1D), WpfColor.FromRgb(0xFF, 0xFF, 0xFF)),
        ["BrushBorder"] = (WpfColor.FromRgb(0x23, 0x25, 0x2E), WpfColor.FromRgb(0xE2, 0xE8, 0xF0)),
        ["BrushSurfaceSubtle"] = (WpfColor.FromRgb(0x21, 0x22, 0x2A), WpfColor.FromRgb(0xF1, 0xF5, 0xF9)),
        ["BrushSquircle"] = (WpfColor.FromRgb(0x21, 0x22, 0x2A), WpfColor.FromRgb(0xF1, 0xF5, 0xF9)),
        ["BrushTextPrimary"] = (WpfColor.FromRgb(0xF4, 0xF4, 0xF6), WpfColor.FromRgb(0x0F, 0x17, 0x2A)),
        ["BrushTextSecondary"] = (WpfColor.FromRgb(0x94, 0x96, 0xA1), WpfColor.FromRgb(0x64, 0x74, 0x8B)),
        ["BrushTextMuted"] = (WpfColor.FromRgb(0x64, 0x66, 0x75), WpfColor.FromRgb(0x94, 0xA3, 0xB8)),
        ["BrushPulse"] = (WpfColor.FromRgb(0x10, 0xB9, 0x81), WpfColor.FromRgb(0x05, 0x96, 0x69)),
        ["BrushPulseText"] = (WpfColor.FromRgb(0x34, 0xD3, 0x99), WpfColor.FromRgb(0x04, 0x78, 0x57)),
        ["BrushStatusConnected"] = (WpfColor.FromRgb(0x10, 0xB9, 0x81), WpfColor.FromRgb(0x05, 0x96, 0x69)),
        ["BrushStatusWarning"] = (WpfColor.FromRgb(0xF5, 0x9E, 0x0B), WpfColor.FromRgb(0xD9, 0x77, 0x06)),
        ["BrushStatusError"] = (WpfColor.FromRgb(0xEF, 0x44, 0x44), WpfColor.FromRgb(0xDC, 0x26, 0x26)),
        ["BrushStatusDisconnected"] = (WpfColor.FromRgb(0x94, 0x96, 0xA1), WpfColor.FromRgb(0x64, 0x74, 0x8B)),
        ["BrushBadgeDeviceBg"] = (WpfColor.FromRgb(0x0F, 0x29, 0x1E), WpfColor.FromRgb(0xEC, 0xFD, 0xF5)),
        ["BrushBadgeDeviceBorder"] = (WpfColor.FromRgb(0x13, 0x4E, 0x39), WpfColor.FromRgb(0xA7, 0xF3, 0xD0)),
        ["BrushBadgeDeviceFg"] = (WpfColor.FromRgb(0x34, 0xD3, 0x99), WpfColor.FromRgb(0x05, 0x96, 0x69)),
        ["BrushBadgeMediaBg"] = (WpfColor.FromRgb(0x17, 0x25, 0x54), WpfColor.FromRgb(0xEF, 0xF6, 0xFF)),
        ["BrushBadgeMediaBorder"] = (WpfColor.FromRgb(0x1E, 0x40, 0xAF), WpfColor.FromRgb(0xBF, 0xDB, 0xFE)),
        ["BrushBadgeMediaFg"] = (WpfColor.FromRgb(0x60, 0xA5, 0xFA), WpfColor.FromRgb(0x25, 0x63, 0xEB)),
        ["BrushBadgeClipBg"] = (WpfColor.FromRgb(0x2E, 0x10, 0x65), WpfColor.FromRgb(0xF5, 0xF3, 0xFF)),
        ["BrushBadgeClipBorder"] = (WpfColor.FromRgb(0x4C, 0x1D, 0x95), WpfColor.FromRgb(0xDD, 0xD6, 0xFE)),
        ["BrushBadgeClipFg"] = (WpfColor.FromRgb(0xA7, 0x8B, 0xFA), WpfColor.FromRgb(0x7C, 0x3A, 0xED)),
        ["BrushBadgeHistoryBg"] = (WpfColor.FromRgb(0x45, 0x1A, 0x03), WpfColor.FromRgb(0xFF, 0xFB, 0xEB)),
        ["BrushBadgeHistoryBorder"] = (WpfColor.FromRgb(0x78, 0x35, 0x0F), WpfColor.FromRgb(0xFD, 0xE6, 0x8A)),
        ["BrushBadgeHistoryFg"] = (WpfColor.FromRgb(0xFB, 0xBF, 0x24), WpfColor.FromRgb(0xD9, 0x77, 0x06))
    };

    public static void ApplyTheme(string theme, bool animate = false)
    {
        CurrentThemeMode = theme;
        bool isDark = theme switch
        {
            "dark" => true,
            "light" => false,
            "system" => IsWindowsDarkTheme(),
            _ => IsWindowsDarkTheme()
        };

        var app = WpfApplication.Current;
        if (app == null) return;

        var duration = new System.Windows.Duration(TimeSpan.FromMilliseconds(200));
        var ease = new QuadraticEase { EasingMode = EasingMode.EaseInOut };

        Action<System.Windows.ResourceDictionary> applyToDict = dict =>
        {
            foreach (var (key, (darkColor, lightColor)) in ThemeColors)
            {
                var targetColor = isDark ? darkColor : lightColor;
                if (dict.Contains(key) && dict[key] is SolidColorBrush brush && !brush.IsFrozen)
                {
                    if (animate)
                    {
                        var anim = new ColorAnimation
                        {
                            To = targetColor,
                            Duration = duration,
                            EasingFunction = ease
                        };
                        brush.BeginAnimation(SolidColorBrush.ColorProperty, anim);
                    }
                    else
                    {
                        brush.BeginAnimation(SolidColorBrush.ColorProperty, null);
                        brush.Color = targetColor;
                    }
                }
                else
                {
                    dict[key] = new SolidColorBrush(targetColor);
                }
            }
        };

        applyToDict(app.Resources);
        foreach (System.Windows.Window window in app.Windows)
        {
            applyToDict(window.Resources);
        }

        ThemeChanged?.Invoke(isDark);
    }
}
