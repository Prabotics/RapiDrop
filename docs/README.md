# RapiDrop Documentation

Canonical documentation for RapiDrop: an open-source, peer-to-peer local network clipboard synchronization and file transfer system for macOS, Android, and Windows, developed under **Prabotics**.

## Documentation Structure

```
docs/
│
├── user/                            # User Guides (Plain Language, Non-Technical)
│   ├── getting-started.md          # Installation, setup, and first run
│   ├── pairing-devices.md          # Interactive discovery and pairing flow
│   ├── clipboard-sync.md           # Cross-device clipboard copying and pasteboard flow
│   ├── sharing-files.md            # Local file and folder sharing guide
│   ├── privacy-and-security.md     # User FAQ on encryption, privacy, and permissions
│   └── troubleshooting.md          # Common connection issues and resolutions
│
└── developer/                       # Developer & Technical Specifications
    ├── setup.md                    # Prerequisites, toolchains, and build commands
    ├── architecture.md             # System design, sequence data flows, and state lifecycle
    ├── protocol.md                 # Binary 28-byte wire framing, opcodes, and chunk streaming
    ├── security.md                 # Cryptography, key agreement, test vectors, threat model
    ├── platforms.md                # macOS, Android, and Windows platform implementation details
    ├── testing.md                  # Test suites, parity vectors, and failure matrix
    └── operations.md               # CI/CD pipelines, release packaging, and packaging scripts
```

## Quick Reference Links

* **User Guides**: [Getting Started](user/getting-started.md) • [Pairing Devices](user/pairing-devices.md) • [Clipboard Sync](user/clipboard-sync.md) • [Sharing Files](user/sharing-files.md) • [Privacy FAQ](user/privacy-and-security.md) • [User Troubleshooting](user/troubleshooting.md)
* **Developer Specifications**: [Developer Setup](developer/setup.md) • [Architecture Guide](developer/architecture.md) • [Wire Protocol](developer/protocol.md) • [Security & Cryptography](developer/security.md) • [Platform Internals](developer/platforms.md) • [Testing](developer/testing.md) • [Operations](developer/operations.md)
