## Summary

Provide a concise summary of the changes made and the motivation behind them.

## Affected Platforms

- [ ] macOS (`macos/`)
- [ ] Android (`android/`)
- [ ] Windows (`windows/`)
- [ ] Protocol & Networking (`WireFrame`)
- [ ] Documentation, Scripts & CI (`.github/`, `docs/`)

## Type of Change

- [ ] Fix (non-breaking bug fix)
- [ ] Feature (non-breaking functionality addition)
- [ ] Refactor (code simplification without behavioral changes)
- [ ] Performance (measurable CPU / memory / network optimization)
- [ ] Infrastructure (CI/CD, packaging, build scripts)

## Invariant & Quality Checklist

- [ ] **Code Clarity & Documentation**: Code is clean, readable, and self-documenting; no commented-out dead code or temporary debugging artifacts.
- [ ] **Zero Cloud Dependencies**: All data exchange remains strictly peer-to-peer on the local subnet.
- [ ] **Native Frameworks**: No external third-party dependencies introduced.
- [ ] **Sensitive Data Protection**: Password manager and transient pasteboard exclusions remain intact.
- [ ] **Local Verification Passed**:
  - macOS: `swift test --package-path macos`
  - Android: `cd android && ./gradlew test`
  - Windows: `dotnet test windows/tests/RapiDrop.Tests/RapiDrop.Tests.csproj -f net10.0`
- [ ] **Clean Working Tree**: No untracked build artifacts (`.DS_Store`, `*.app`, `*.apk`, `.build/`, `bin/`, `obj/`).
