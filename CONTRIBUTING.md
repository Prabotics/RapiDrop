# Contributing to RapiDrop

Thank you for your interest in contributing to RapiDrop. Contributions that maintain high platform standards, low resource usage, and clean platform-native architecture are welcome.

* **Repository**: [https://github.com/Prabotics/RapiDrop](https://github.com/Prabotics/RapiDrop)
* **Organization**: [Prabotics](https://github.com/Prabotics)

## Engineering Guidelines

1. **Native Platform Architecture**:
   - macOS: Swift 6 with strict concurrency (`AppKit` + `SwiftUI`).
   - Android: Kotlin 2.0+ with Jetpack Compose (`Material 3`).
2. **Resource Constraints**:
   - Idle CPU usage must remain negligible (< 0.05%).
   - Memory footprint should stay under 35MB on macOS and 50MB on Android (idle working set).
   - Avoid background polling loops and unnecessary heap allocations.
3. **High-Signal Code & Documentation**:
   - Write clean, self-documenting code with clear separation of concerns and deterministic error handling.
4. **Security & Privacy**:
   - Peer-to-peer over the local subnet only. No telemetry or external cloud dependencies.
   - Retain sensitive pasteboard filtering for password managers on all platforms.

For detailed developer guides, architecture specifications, and testing matrices, see [docs/developer/architecture.md](docs/developer/architecture.md) and [docs/developer/testing.md](docs/developer/testing.md).
## Development & Testing Workflow

### macOS
```bash
cd macos
# Run unit test suite
swift test

# Build release executable
swift build -c release
```

### Android
```bash
cd android
# Run unit test suite
./gradlew test

# Build debug APK
./gradlew assembleDebug
```
## Commit Guidelines

We follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

- `feat:` A new user-facing capability
- `fix:` A bug fix
- `refactor:` A code change that neither fixes a bug nor adds a feature
- `perf:` A code change that improves performance
- `docs:` Documentation updates
- `chore:` Maintenance tasks or build configuration
- `test:` Adding or correcting tests

Keep commit titles concise (under 72 characters) and imperative (e.g., `feat: add bounded scroll view for recent clips`).

## Submitting a Pull Request

1. Fork the repository and create your branch from `main`:
   ```bash
   git checkout -b feat/your-feature-name
   ```
2. Ensure all tests pass on your platform:
   - macOS: `swift test --package-path macos`
   - Android: `cd android && ./gradlew test`
3. Verify `git status` to ensure no transient build outputs or logs are untracked.
4. Push to your fork and submit a Pull Request with a clear description of changes.
