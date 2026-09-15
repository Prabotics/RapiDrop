# Developer Setup & Build Guide

RapiDrop is built using pure platform-native toolchains across macOS, Android, and Windows.

## Prerequisites

* **macOS**: Xcode 16+ / Swift 6.0 toolchain (`swift --version`)
* **Android**: Android Studio Ladybug+ / JDK 17+ / Android SDK 35
* **Windows**: .NET 10.0 SDK (`dotnet --version`)

## Building Each Platform

### macOS (Swift / AppKit / SwiftUI)
```bash
# Run automated tests
swift test --package-path macos

# Build release executable
swift build -c release --package-path macos

# Package macOS Application Bundle (.app / .dmg)
./macos/package.sh
```

### Android (Kotlin / Jetpack Compose)
```bash
cd android

# Run unit tests
./gradlew test

# Assemble debug APK
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug
```

### Windows (C# / .NET 10 / WPF)
```bash
cd windows

# Run xUnit test suite
dotnet test tests/RapiDrop.Tests/RapiDrop.Tests.csproj

# Build release binary
dotnet build src/RapiDrop.UI/RapiDrop.UI.csproj -c Release
```
