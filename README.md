# RapiDrop

[![CI](https://github.com/Prabotics/RapiDrop/actions/workflows/ci.yml/badge.svg)](https://github.com/Prabotics/RapiDrop/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Prabotics/RapiDrop?include_prereleases&label=release&color=emerald)](https://github.com/Prabotics/RapiDrop/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platforms](https://img.shields.io/badge/platforms-macOS%20%7C%20Android%20%7C%20Windows-lightgrey.svg)](#supported-platforms)

> Fast, private, peer-to-peer clipboard synchronization and local file sharing between macOS, Android, and Windows.

**RapiDrop** connects your devices directly over local Wi-Fi using end-to-end encrypted TCP connections. Your data never leaves your local network: zero cloud servers, zero telemetry, zero accounts, and zero third-party relays.

## Downloads

Download the latest pre-compiled binaries from [GitHub Releases](https://github.com/Prabotics/RapiDrop/releases/latest):

| Platform | Download | Requirements |
| :--- | :--- | :--- |
| **macOS** | [`RapiDrop-macOS.dmg`](https://github.com/Prabotics/RapiDrop/releases/latest/download/RapiDrop-macOS.dmg) | macOS 14.0+ (Apple Silicon & Intel) |
| **Android** | [`RapiDrop.apk`](https://github.com/Prabotics/RapiDrop/releases/latest/download/RapiDrop.apk) | Android 10+ (API 29–35+) |
| **Windows** | [`RapiDrop-Standalone.exe`](https://github.com/Prabotics/RapiDrop/releases/latest/download/RapiDrop-Standalone.exe) | Windows 10/11 (64-bit) |

> Looking for portable ZIP archives (`.zip`) or cryptographic checksums? Visit the [Latest Release Page](https://github.com/Prabotics/RapiDrop/releases/latest).
## Installation & Setup

### macOS
1. Open `RapiDrop-macOS.dmg` and drag **RapiDrop** into your `/Applications` folder.
2. Launch RapiDrop from `/Applications`. The icon will appear in your menu bar.

> [!NOTE]
> **macOS Gatekeeper**: On first launch, macOS may report that the developer cannot be verified. Right-click `RapiDrop.app` in `/Applications` and choose **Open**, or run:
> ```bash
> xattr -cr /Applications/RapiDrop.app
> ```

### Android
1. Download and open `RapiDrop.apk` on your phone to install.
2. Open the app and allow notification permission so background sync stays active.
3. (Optional) Add the **RapiDrop** tile to your Quick Settings notification shade for instant one-tap sharing.

### Windows
1. Download and run `RapiDrop-Standalone.exe`.
2. Click the RapiDrop icon in your system tray to discover devices and share clips.

## Key Highlights

* **Instant Clipboard Sync**: Copy text, links, or screenshots on one device and paste them on another in milliseconds.
* **Large File Streaming**: Stream multi-gigabyte files and folders at wire speed in bounded 1 MB chunks with streaming SHA-256 integrity verification.
* **Interactive Radar Pairing**: Tap a nearby peer on the radar screen. The receiver accepts with a matching 6-digit Short Authentication String (SAS) code.
* **Pure Local-First Privacy**: Operates strictly within your local subnet (`_clipsync._tcp` via mDNS). Zero cloud tracking, zero telemetry.
* **End-to-End Encryption**: Every packet is encrypted with AES-256-GCM using fresh ephemeral keys negotiated via X25519 key agreement with HKDF-SHA256 transcript hashing.
* **Password Manager Protection**: Automatically detects and ignores sensitive transient clips from 1Password, Bitwarden, KeePass, and Apple Keychain.

## Supported Platforms

| Platform | Native Architecture | System Integration |
| :--- | :--- | :--- |
| **macOS** | Swift 6 / Apple `Network.framework` | Menu Bar Accessory (`LSUIElement`) & `NSPasteboard` 400ms polling |
| **Android** | Kotlin 2.0 / Jetpack Compose | `connectedDevice` Foreground Service & MediaStore Scoped Storage |
| **Windows** | C# 13 / .NET 10 / Win32 | System Tray Flyout & Win32 Clipboard Format Listener |

## Documentation

### For Users
* [Getting Started](docs/user/getting-started.md): First-time setup and overview.
* [Pairing Devices](docs/user/pairing-devices.md): How discovery and SAS verification work.
* [Clipboard Synchronization](docs/user/clipboard-sync.md): Cross-device copy and paste behavior.
* [Sharing Files](docs/user/sharing-files.md): Sending files and folders over Wi-Fi.
* [Privacy & Security FAQ](docs/user/privacy-and-security.md): Plain-language security answers.
* [Troubleshooting](docs/user/troubleshooting.md): Common connection questions.

### For Developers
* [Developer Setup & Build Guide](docs/developer/setup.md): Prerequisites and toolchains.
* [Architecture Overview](docs/developer/architecture.md): Component breakdown and data flows.
* [Wire Protocol Specification](docs/developer/protocol.md): 28-byte framing, opcodes, and chunk format.
* [Cryptographic Specification](docs/developer/security.md): X25519, HKDF-SHA256, and AES-256-GCM.
* [Testing Guide](docs/developer/testing.md): Test suites, parity vectors, and failure matrix.
* [Platform Implementations](docs/developer/platforms.md): Native platform details.
* [Operations & CI/CD](docs/developer/operations.md): GitHub Actions workflows and release packaging.
* [Contributing Guidelines](CONTRIBUTING.md): Standards and pull request guidelines.

## Development & Test Commands

### macOS
```bash
# Run unit tests
swift test --package-path macos

# Build release bundle (.app / .dmg / .zip)
./macos/package.sh --all
```

### Android
```bash
cd android

# Run unit tests
./gradlew test

# Assemble release APK
./gradlew assembleRelease
```

### Windows
```bash
cd windows

# Run unit tests
dotnet test tests/RapiDrop.Tests/RapiDrop.Tests.csproj -f net10.0

# Build release executable
dotnet publish src/RapiDrop.UI/RapiDrop.UI.csproj -f net10.0-windows -c Release -r win-x64 -p:PublishSingleFile=true --self-contained true
```

## License

RapiDrop is open-source software licensed under the [MIT License](LICENSE).
Built and maintained by [Prabotics](https://github.com/Prabotics).
