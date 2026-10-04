# Operations & Release Packaging

## Continuous Integration (`ci.yml`)
Runs on pushes to `main` and pull requests:
- **`hygiene-check`**: Validates working tree against unwanted metadata files.
- **`macos`**: Runs `swift test --package-path macos` and packages `.app` bundle via `macos/package.sh --all`.
- **`android`**: Runs `./gradlew test` and builds release package with R8 shrinking via `./gradlew assembleRelease`.

## Release Workflow (`release.yml`)
Triggered on `v*` git tags or manual `workflow_dispatch`:
1. Compiles and packages `RapiDrop-macOS.dmg`, `RapiDrop-macOS.zip`, and production-signed `RapiDrop.apk`.
2. Generates `SHA256SUMS.txt` cryptographic checksums.
3. Automatically extracts the matching version section from `CHANGELOG.md` to populate release notes.
4. Publishes assets directly to GitHub Releases.

## Local Build Commands

### macOS
```bash
# Tests
swift test --package-path macos

# Package release DMG & ZIP
./macos/package.sh --all
```

### Android
```bash
cd android

# Tests
./gradlew test

# Release APK
./gradlew assembleRelease
```
