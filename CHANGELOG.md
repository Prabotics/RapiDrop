# Changelog
## [Unreleased]

### Networking & Protocol
- New packet frames exchange encrypted notification payloads and bilateral dismissal commands.

### macOS
- System notifications forward phone alerts directly to macOS banners with sound support.
- Notification history panel provides one-click OTP verification code copying and remote phone dismissal.

### Android
- Notification listener mirrors mobile alerts while filtering ongoing media players and download bars.
- One-tap banner alerts users when device notification access requires system setup.

## [1.0.0] - 2026-09-16

### Networking & Protocol
- Binary wireframe protocol transmits 28-byte headers over local TCP sockets with 1 MB streaming chunks.
- Local mDNS discovery locates nearby peer devices on the same Wi-Fi network without external servers.
- Bounded packet queues and heartbeat probes keep active peer connections alive.

### macOS
- Native menu bar accessory runs in the status bar with quick clip inspection and drag-and-drop file sharing.
- Background pasteboard monitor detects copied text, links, and images at 400 millisecond intervals.
- System service registration enables optional launch at login directly from settings.

### Android
- Material 3 user interface provides radar discovery, sync history, and paired device management.
- Foreground service maintains local network connectivity while Android is in standby mode.
- Quick settings tile and share sheet integration allow one-tap clipboard pushes from any app.

### Security & Privacy
- Curve25519 key agreement derives per-session encryption keys with 6-digit numeric verification codes.
- AES-256-GCM cipher encrypts all network frames with unique nonces per message.
- Automatic clipboard filters suppress transient password manager entries from Bitwarden, 1Password, KeePass, and Keychain.
