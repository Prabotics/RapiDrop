# System Architecture & Data Flow

RapiDrop is structured as a decentralized, zero-cloud peer-to-peer system that uses pure platform-native frameworks without intermediate cloud servers.

## 1. Subsystem Architecture Map

```
┌─────────────────────────────────────────────────────────────┐
│                      RapiDrop Core                          │
├─────────────────┬───────────────────┬───────────────────────┤
│  macOS Layer    │   Android Layer   │     Windows Layer     │
│  (Swift 6)      │   (Kotlin 2.0)    │     (.NET 10 / C#)    │
├─────────────────┼───────────────────┼───────────────────────┤
│ AppKit / SwiftUI│ Jetpack Compose   │ WPF / XAML            │
│ Network.fw      │ Java NIO Sockets  │ System.Net.Sockets    │
│ Apple CryptoKit │ javax.crypto / K2 │ System.Security.Crypto│
│ NSPasteboard    │ ClipboardManager  │ Win32 OleSetClipboard │
│ NSFileManager   │ MediaStore API    │ System.IO.FileStream  │
└─────────────────┴───────────────────┴───────────────────────┘
```

## 2. End-to-End Data Flows

### 2.1 Local Network Discovery Flow
```mermaid
sequenceDiagram
    autonumber
    participant AppA as Device A (Initiator)
    participant mDNS as Local Wi-Fi Subnet
    participant AppB as Device B (Receiver)

    AppB->>mDNS: Register service "_clipsync._tcp" on Port 58240 (TXT: model, name)
    AppA->>mDNS: Query for service "_clipsync._tcp"
    mDNS-->>AppA: Resolved IP, Port 58240, TXT metadata
    AppA->>AppA: Update Discovered Devices Radar List
```

### 2.2 Pairing & Trust Establishment Flow
```mermaid
sequenceDiagram
    autonumber
    participant UI_A as Device A UI
    participant Net_A as Device A Engine
    participant Net_B as Device B Engine
    participant UI_B as Device B UI

    UI_A->>Net_A: User taps Peer on Radar
    Net_A->>Net_B: TCP Connect (Port 58240)
    Net_A->>Net_B: PAIR_INVITE {"fromDeviceName": "...", "publicKey": "...", "nonce": "..."}
    Net_B->>UI_B: Display Incoming Pair Card with 6-Digit Code

    alt User Approves
        UI_B->>Net_B: User taps "Accept"
        Net_B->>Net_A: PAIR_ACCEPT {"publicKey": "...", "nonce": "..."}
        Net_B->>Net_A: DEVICE_INFO (Encrypted hardware details)
        Net_A->>Net_B: DEVICE_INFO (Encrypted hardware details)
        Net_A->>Net_A: Persist Pair Record Key (K_pair)
        Net_B->>Net_B: Persist Pair Record Key (K_pair)
    else User Declines or Cancels
        UI_B->>Net_B: User taps "Decline" (or Sender cancels)
        Net_B->>Net_A: PAIR_FAIL {"reason": "DECLINED"}
        Net_A->>Net_B: Close TCP Socket
    end
```

### 2.3 Clipboard Synchronization Flow
```mermaid
sequenceDiagram
    autonumber
    participant ClipA as Local Clipboard
    participant MonA as Clipboard Monitor
    participant CryptoA as Crypto Engine
    participant NetA as Sockets (Sender)
    participant NetB as Sockets (Receiver)
    participant CryptoB as Crypto Engine (B)
    participant ClipB as Remote Clipboard

    ClipA->>MonA: User copies text / URL / image
    MonA->>MonA: Inspect metadata (Drop if sensitive)
    MonA->>MonA: Check hash against echo filter (Drop if incoming echo)
    MonA->>CryptoA: Encrypt payload with Session Key (AES-256-GCM)
    CryptoA-->>NetA: Ciphertext + 12B Nonce + 16B Auth Tag
    NetA->>NetB: Transmit 28B Header + Encrypted Frame
    NetB->>CryptoB: Decrypt & verify Auth Tag
    CryptoB-->>NetB: Plaintext payload
    NetB->>NetB: Record content hash in echo filter
    NetB->>ClipB: Write to System Pasteboard & Recent History
```

### 2.4 Chunked File Streaming Flow
```mermaid
sequenceDiagram
    autonumber
    participant SenderUI as Sender UI
    participant SenderEngine as Sender Engine
    participant ReceiverEngine as Receiver Engine
    participant ReceiverDisk as Storage (.part)
    participant ReceiverUI as Receiver UI

    SenderUI->>SenderEngine: User drops file / selects in Share sheet
    SenderEngine->>ReceiverEngine: FILE_START {"transferId": "...", "fileName": "...", "fileSize": 10485760}
    ReceiverEngine->>ReceiverDisk: Create temporary file "filename.ext.rapidrop_part"
    ReceiverEngine->>ReceiverUI: Display incoming progress bar (0%)

    loop For each 1 MB slice (with Backpressure)
        SenderEngine->>ReceiverEngine: FILE_CHUNK [16B UUID][4B fileIndex][8B chunkIndex][1MB Data]
        ReceiverEngine->>ReceiverEngine: Validate UUID, fileIndex, and chunkIndex sequence
        ReceiverEngine->>ReceiverDisk: Append bytes & update running SHA-256 hasher
        ReceiverEngine->>ReceiverUI: Update progress (bytesTransferred, totalBytes, isComplete: false)
    end

    SenderEngine->>ReceiverEngine: FILE_END {"transferId": "...", "sha256": "computed_hash", "status": "OK"}

    alt SHA-256 & Byte Count Valid
        ReceiverEngine->>ReceiverDisk: Move from .part to final download path
        ReceiverEngine->>ReceiverUI: Dismiss progress, show completion toast
    else SHA-256 Mismatch or Byte Count Corrupted
        ReceiverEngine->>ReceiverDisk: Delete partial file (.part)
        ReceiverEngine->>ReceiverUI: Display "Integrity verification failed" error
    end
```

## 3. State Lifecycle & Watchdogs

* **Heartbeat Ping/Pong**: Senders dispatch a lightweight `PING` (`0x0001`) every 5 seconds over active idle connections. Receivers respond with `PONG` (`0x0002`).
* **Connection Watchdog**: If no packet or keepalive response is received within 16 seconds, the socket is cleanly torn down and auto-reconnect backoff is scheduled.
* **Auto-Reconnect Loop**: Attempts exponential backoff reconnects (1.5s $\rightarrow$ 3s $\rightarrow$ 6s $\dots$ capped at 20s) when paired peers are on the network.
