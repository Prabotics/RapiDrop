# Platform Architecture & Native Implementations

RapiDrop uses pure platform-native frameworks on each operating system to achieve high performance, low resource consumption, and native OS integration without third-party cross-platform abstractions.

## 1. macOS Platform Architecture

* **Language**: Swift 6 (Strict Concurrency)
* **UI**: AppKit `NSStatusItem` / Menu Bar Extra with SwiftUI popover
* **Networking**: Apple `Network.framework` (`NWListener`, `NWBrowser`, `NWConnection`)
* **Security**: Apple `CryptoKit` (`Curve25519`, `AES.GCM`, `HKDF<SHA256>`) & Apple Keychain Services
* **Clipboard**: `NSPasteboard` change count polling (1.5s active / 3.0s background)

### Directory Layout
```text
macos/
├── Package.swift                     # SPM manifest
├── package.sh                        # App bundle & DMG packager
├── Sources/
│   ├── App/                          # AppState & App entry point
│   ├── UI/                           # MenuBarView SwiftUI interface
│   ├── Clipboard/                    # PasteboardMonitor & ClipItem
│   ├── Network/                      # NetworkEngine & WireFrame
│   └── Security/                     # CryptoEngine
└── Tests/RapiDropTests/              # Swift Testing suites
```

## 2. Android Platform Architecture

* **Language**: Kotlin 2.0+ / JVM 17
* **UI**: Jetpack Compose (Material 3)
* **Service**: `connectedDevice` Foreground Service with Status Bar notification
* **Networking**: Non-blocking `java.nio` TCP Sockets & `NsdManager`
* **Security**: `javax.crypto.Cipher` + Pure Kotlin RFC 7748 `Curve25519` & Hardware-backed Android Keystore
* **Storage**: Scoped Storage (`MediaStore.Downloads`) via `MediaStorageHelper`

### Directory Layout
```text
android/app/src/main/java/com/prabotics/rapidrop/
├── ui/                               # MainActivity, MainScreen, Components, Themes
├── service/                          # SyncService, TileService, ShareActivity, BootReceiver
├── network/                          # SocketClient, ClientSocketServer, WireFrame, NsdDiscovery
├── clipboard/                        # MediaStorageHelper, ClipboardManagerHelper, ClipItem
├── security/                         # CryptoEngine, Curve25519
└── preference/                       # PreferencesManager (rapidrop_secure_prefs)
```

## 3. Windows Platform Architecture

* **Language**: C# / .NET 10.0
* **UI**: WPF & XAML Fluent System Tray Flyout
* **Networking**: Asynchronous `System.Net.Sockets.TcpClient` / `TcpListener` & DNS-SD
* **Security**: `System.Security.Cryptography.AesGcm` & C# RFC 7748 `Curve25519` & Windows DPAPI
* **Clipboard**: Win32 `AddClipboardFormatListener` / `WM_CLIPBOARDUPDATE`

### Directory Layout
```text
windows/
├── RapiDrop.sln                      # Visual Studio solution
├── src/
│   ├── RapiDrop.Core/                # NetworkEngine, WireFrame, MdnsDiscovery, CryptoEngine
│   └── RapiDrop.UI/                  # TrayFlyoutWindow, Theme, Win32Clipboard
└── tests/RapiDrop.Tests/             # WireFrameTests, CryptoEngineTests
```

## 4. Native Visual Foundation & Token Architecture

| Platform | Foundation & Token Locations | Key Visual Principles |
| :--- | :--- | :--- |
| **macOS** | `macos/Sources/UI/Theme/AppTheme.swift` | Semantic system colors (`AppColors`), SF Pro typography hierarchy (`AppTypography`), standard spacing scale (`AppSpacing`), and native corner radii (`AppRadius`). |
| **Android** | `android/app/src/main/java/com/prabotics/rapidrop/ui/theme/` (`Color.kt`, `Type.kt`, `Shape.kt`, `Theme.kt`) | Material 3 color schemes (`LightColorScheme`, `DarkColorScheme`), dynamic color integration on Android 12+, semantic `LocalStatusColors`, Inter/JetBrains Mono typography tokens, and standard `Spacing`/`TouchTarget` scales. |
| **Windows** | `windows/src/RapiDrop.UI/Theme/` (`Palette.xaml`, `Controls.xaml`, `ThemeManager.cs`) | Fluent-compatible XAML resources (`BrushCanvas`, `BrushCard`, `BrushStatusConnected`, `BrushSurfaceSubtle`), Segoe UI Variable typography, standard corner radii, and smooth light/dark palette transitions. |
