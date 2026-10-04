# Architecture & Subsystems

RapiDrop operates exclusively over the local Wi-Fi subnet using mDNS service discovery (`_clipsync._tcp`) and AES-256-GCM encrypted TCP sockets.

## Subsystems

| Subsystem | Stack | Key Components |
| :--- | :--- | :--- |
| **macOS** (`macos/`) | Swift 6, AppKit, SwiftUI | Menu bar accessory (`LSUIElement`), `NSPasteboard` change polling (400ms connected / 1500ms idle), Apple `Network.framework` socket server. |
| **Android** (`android/`) | Kotlin 2.0+, Jetpack Compose | Material 3 UI, `connectedDevice` foreground service, Scoped Storage (`MediaStore.Downloads`), non-blocking NIO sockets. |

## Network & Protocol Data Flows

1. **Discovery**: Peers publish and browse `_clipsync._tcp` on port `58240` via mDNS with device model and name in TXT records.
2. **Pairing**: Initiator connects via TCP and sends `PAIR_INVITE` with ephemeral public key. Receiver confirms with matching 6-digit SAS code, sends `PAIR_ACCEPT`, and exchanges `DEVICE_INFO`.
3. **Clipboard Sync**: Local clipboard monitors capture copied text, URLs, or images, encrypt payloads with the derived session key, and transmit 28-byte framed packets. Sensitive clips from password managers are excluded.
4. **File Streaming**: Large files are sliced into 1 MB chunks (`FILE_CHUNK`), streamed sequentially with flow control, and verified via whole-file SHA-256 digest upon completion (`FILE_END`).

## State Lifecycle & Watchdogs
- **Keepalive**: Peers send `PING` (`0x0001`) every 4 seconds over idle connections. Receivers respond with `PONG` (`0x0002`).
- **Watchdog**: Sockets with no frame or heartbeat for 16 seconds (`INACTIVITY_TIMEOUT_MS`) are closed. Reconnection attempts use exponential backoff (1.5s to 20s).
