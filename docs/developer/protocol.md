# Binary Wire Protocol Specification

RapiDrop uses a lightweight, binary framing protocol over direct TCP socket connections (default port `58240`).

## 1. 28-Byte Binary Header Layout

Every network frame begins with a fixed 28-byte header encoded in Big-Endian (Network Byte Order):

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       Payload Length (4B)                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Packet Type (2B)        |     Protocol Version (2B)     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                       Timestamp (8B, UInt64)                  +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                    Initialization Vector (12B)                +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    Ciphertext Payload (Variable)              |
|                               ...                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    Authentication Tag (16B)                   |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Header Field Definitions

| Field | Offset | Length | Type | Description |
| :--- | :---: | :---: | :--- | :--- |
| **Payload Length** | `0..3` | 4 Bytes | `UInt32` (BE) | Length of ciphertext payload + 16-byte auth tag ($\ge 16$, $\le 104,857,600$). |
| **Packet Type** | `4..5` | 2 Bytes | `UInt16` (BE) | Opcode identifier (see Packet Type table below). |
| **Protocol Version** | `6..7` | 2 Bytes | `UInt16` (BE) | Canonical protocol version: `0x0001`. |
| **Timestamp** | `8..15` | 8 Bytes | `UInt64` (BE) | Unix timestamp in milliseconds when frame was sent. |
| **Nonce (IV)** | `16..27` | 12 Bytes | Raw Bytes | Cryptographically random initialization vector per frame. |
| **Ciphertext** | `28..N-16` | Variable | Encrypted Data | AES-256-GCM encrypted payload. |
| **Auth Tag** | `N-15..N` | 16 Bytes | Raw Bytes | 128-bit Galois Message Authentication Code. |

## 2. Packet Types & Opcodes

| Opcode | Hex | Name | Payload Content | Purpose |
| :---: | :---: | :--- | :--- | :--- |
| `1` | `0x0001` | `PING` | Empty | Keepalive heartbeat probe |
| `2` | `0x0002` | `PONG` | Empty | Keepalive heartbeat response |
| `3` | `0x0003` | `PAIR_REQUEST` | JSON Envelope | Initial pairing request probe |
| `4` | `0x0004` | `PAIR_CONFIRM` | JSON Envelope | Bidirectional handshake confirmation |
| `5` | `0x0005` | `DEVICE_INFO` | JSON (Encrypted) | Hardware model, OS name, and device name exchange |
| `6` | `0x0006` | `PAIR_INVITE` | JSON Envelope | Interactive AirDrop-style invitation with ephemeral key |
| `7` | `0x0007` | `PAIR_FAIL` | JSON Envelope | Pairing decline or user cancellation signal |
| `8` | `0x0008` | `PAIR_ACCEPT` | JSON Envelope | Pairing acceptance with ephemeral public key |
| `16` | `0x0010` | `CLIP_TEXT` | UTF-8 Text (Encrypted) | Synchronized plain text clipboard item |
| `17` | `0x0011` | `CLIP_URL` | UTF-8 URL (Encrypted) | Synchronized web link clipboard item |
| `18` | `0x0012` | `CLIP_IMAGE` | 2B Name Len + Name + Image | Synchronized screenshot/image (PNG/JPEG) |
| `19` | `0x0013` | `CLIP_FILE` | 2B Name Len + Name + Data | Small inline binary file item |
| `20` | `0x0014` | `FILE_START` | JSON (Encrypted) | Initiates chunked streaming file transfer |
| `21` | `0x0015` | `FILE_CHUNK` | 28B Header + 1 MB Data | Discrete chunk slice of active streaming transfer |
| `22` | `0x0016` | `FILE_END` | JSON (Encrypted) | Signals streaming completion with SHA-256 hash |
| `23` | `0x0017` | `FILE_CANCEL` | JSON (Encrypted) | Aborts streaming transfer and triggers cleanup |
| `32` | `0x0020` | `CONFIG_SYNC` | JSON (Encrypted) | Synchronizes direct mode / privacy preferences |
| `255` | `0x00FF` | `DISCONNECT` | UTF-8 String (Encrypted) | Unpair / graceful session termination |

## 3. Pairing Handshake Sequence

```
Device A (Initiator)                    Device B (Receiver)
       │                                       │
Generate Ephemeral (a, A, N_A)          Generate Ephemeral (b, B, N_B)
       │                                       │
       │─── PAIR_INVITE (A, N_A, NameA) ──────►│
       │                                Display Invite Dialog
       │                                User Taps "Accept"
       │◄── PAIR_ACCEPT (B, N_B, NameB) ───────│
       │                                       │
Compute S = X25519(a, B)                Compute S = X25519(b, A)
Extract & Expand Handshake Keys         Extract & Expand Handshake Keys
       │                                       │
       │─── DEVICE_INFO (Encrypted) ──────────►│
       │◄── DEVICE_INFO (Encrypted) ───────────│
       │                                       │
[ Trusted Session Established ]         [ Trusted Session Established ]
```

## 4. Chunked File Streaming

Files are streamed in discrete 1 MB (`1,048,576` bytes) slices:

1. **`FILE_START` (`0x0014`)**:
   ```json
   {
     "transferId": "550e8400-e29b-41d4-a716-446655440000",
     "fileIndex": 0,
     "totalFiles": 1,
     "fileName": "archive.zip",
     "relativePath": "archive.zip",
     "fileSize": 104857600,
     "totalBytes": 104857600
   }
   ```
2. **`FILE_CHUNK` (`0x0015`)** (28-byte Header + Chunk Payload):
   - `0..15`: 16-byte Transfer UUID
   - `16..19`: 4-byte `fileIndex` (`UInt32` BE)
   - `20..27`: 8-byte `chunkIndex` (`UInt64` BE)
   - `28..N`: Chunk data bytes (up to 1 MB)
3. **`FILE_END` (`0x0016`)**:
   ```json
   {
     "transferId": "550e8400-e29b-41d4-a716-446655440000",
     "fileIndex": 0,
     "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
     "status": "OK"
   }
   ```
4. **`FILE_CANCEL` (`0x0017`)**:
   ```json
   {
     "transferId": "550e8400-e29b-41d4-a716-446655440000",
     "reason": "user_cancelled"
   }
   ```
