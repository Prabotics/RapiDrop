# RapiDrop Documentation

RapiDrop provides peer-to-peer clipboard synchronization and local file sharing between macOS and Android without intermediate servers.

## User Guides
- [Getting Started](user/getting-started.md): Installation and initial setup.
- [Pairing Devices](user/pairing-devices.md): Peer discovery and 6-digit SAS code verification.
- [Clipboard Synchronization](user/clipboard-sync.md): Text, links, and screenshot sync behavior.
- [Sharing Files](user/sharing-files.md): Local file transfer and storage paths.
- [Privacy & Security FAQ](user/privacy-and-security.md): Encryption and permissions overview.
- [Troubleshooting](user/troubleshooting.md): Wi-Fi network and background sync diagnostics.

## Technical Specifications
- [Architecture & Subsystems](developer/architecture.md): Component map and network data flows.
- [Wire Protocol Specification](developer/protocol.md): 28-byte header layout, opcodes, and chunk framing.
- [Cryptographic Specification](developer/security.md): X25519 key agreement, HKDF derivation, and test vectors.
- [Platform Internals](developer/platforms.md): Native implementations and directory layouts.
- [Testing Guide](developer/testing.md): Test execution matrix and parity points.
- [Operations & Packaging](developer/operations.md): CI/CD workflows and local build commands.
