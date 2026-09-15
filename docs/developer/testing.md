# Developer Testing Guide

RapiDrop emphasizes deterministic cross-platform test suites that verify cryptographic parity, binary wire framing, path sanitization, and streaming state machines.

## Test Execution Matrix

| Platform | Framework | Test Path | Execution Command |
| :--- | :--- | :--- | :--- |
| **macOS** | Swift Testing | `macos/Tests/RapiDropTests/RapiDropTests.swift` | `swift test --package-path macos` |
| **Android** | JUnit 4 | `android/app/src/test/java/com/prabotics/rapidrop/RapiDropUnitTest.kt` | `cd android && ./gradlew test` |
| **Windows** | xUnit | `windows/tests/RapiDrop.Tests/` | `cd windows && dotnet test` |

## Key Test Vectors & Parity Points

### 1. Canonical Handshake Test Vector
All three platforms execute an identical test vector verifying that given:
- Alice Private Key: `77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a`
- Bob Public Key: `de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f`
The derived Short Authentication String (SAS) code is deterministically:
`784010`

### 2. Path Sanitization Adversarial Suite
Verifies that malicious inputs (`../../etc/passwd`, `..\evil.bat`, colons, null bytes) are stripped to safe relative paths under `Downloads/RapiDrop/`.

### 3. Incremental SHA-256 Chunk Streaming
Verifies that chunk-by-chunk incremental streaming hashes match whole-buffer SHA-256 digests across 1 MB boundaries.
