# Security & Cryptographic Engine Specification

RapiDrop uses standard, platform-native cryptographic primitives for high performance, zero-dependency security, and perfect forward secrecy.

## 1. Cryptographic Primitives

| Parameter | Specification | Standard |
| :--- | :--- | :--- |
| **Key Agreement** | X25519 Montgomery Curve25519 | RFC 7748 |
| **Symmetric Cipher** | AES-256 in Galois/Counter Mode (GCM) | NIST SP 800-38D |
| **Key Size** | 256 bits (32 bytes) | FIPS 197 |
| **Initialization Vector (IV)** | 96 bits (12 bytes) CSPRNG per frame | RFC 5116 |
| **Authentication Tag** | 128 bits (16 bytes) Galois MAC | RFC 5116 |
| **Key Derivation Function** | HKDF-SHA256 (Extract-and-Expand) | RFC 5869 |
| **Short Authentication String** | 6-Digit Decimal Code ($\text{UInt32}(K_{auth}[0..3]) \bmod 10^6$) | Protocol Specification |
| **File Integrity Digest** | SHA-256 | FIPS 180-4 |

## 2. Key Derivation Pipeline

```
Initiator Keypair (a, A) ──┐
                          ├──> X25519 Shared Secret (S)
Receiver Keypair  (b, B) ──┘
                                   │
                       Nonces Salt (N_A || N_B)
                                   │
                                   ▼
                   HKDF-Extract(Salt, S) ──> PRK (32 Bytes)
                                              │
                      ┌───────────────────────┼───────────────────────┐
                      │                       │                       │
                      ▼                       ▼                       ▼
            HKDF-Expand (Session)   HKDF-Expand (Auth)      HKDF-Expand (Pair)
                      │                       │                       │
                      ▼                       ▼                       ▼
               K_session (32B)          K_auth (32B)            K_pair (32B)
                                              │
                                              ▼
                                         SAS (6 Digits)
```

### Canonical Handshake Test Vectors
All platforms execute identical deterministic test vectors:
- **Alice Private Key**: `77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a`
- **Bob Public Key**: `de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f`
- **Derived Shared Secret**: `4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742`
- **Transcript Hash**: `1aace1004eca802c7cc3ee4e78a1e2f4da275e0d77998193e85f19d217d90fbc`
- **Session Key ($K_{session}$)**: `02bbd12373e37ce395ea08c747c5296ba9dc881586e84efeb0ec8cdefe7d7127`
- **Authentication Token ($K_{auth}$)**: `17d4384a1b5e20a8268ac2d1e3e28b4aa7281f37c0be20951381ac95432279b5`
- **Persistent Pair Record Key ($K_{pair}$)**: `50b4fa871f3bbab60481054fdd55277c7c69f2efc3f76fca4fbf59c53005b583`
- **Numeric SAS Code**: `784010`

## 3. Platform Native Implementations

| Platform | Subsystem | Implementation Class |
| :--- | :--- | :--- |
| **macOS** | Apple `CryptoKit` | `Curve25519.KeyAgreement`, `AES.GCM`, `HKDF<SHA256>`, `SHA256` |
| **Android** | Kotlin & `javax.crypto` | `com.prabotics.rapidrop.security.Curve25519`, `Cipher("AES/GCM/NoPadding")`, `Mac("HmacSHA256")` |
| **Windows** | C# & `System.Security.Cryptography` | `RapiDrop.Core.Security.Curve25519`, `AesGcm`, `HKDF.Extract`/`HKDF.Expand`, `SHA256` |

## 4. Threat Model & Asset Protection

| Protected Asset | Threat | Mitigation |
| :--- | :--- | :--- |
| **Clipboard Content** | Passive LAN eavesdropping | AES-256-GCM encryption with unique random nonces per frame. |
| **Pairing Integrity** | Man-in-the-Middle (MITM) | X25519 key agreement with Short Authentication String (SAS) verification. |
| **Session Keys** | Key compromise / replay | Fresh ephemeral keypairs generated per connection; nonces never reused. |
| **Credentials / Passwords** | Unintended clipboard sync | Automatic suppression of transient password manager clips. |
| **File Storage** | Path traversal attacks | Strict `sanitizeRelativePath` and directory containment checks. |
| **File Data** | Chunk tampering / corruption | Mandatory SHA-256 digest verification before finalizing downloads. |

## 5. Secure Credential Storage at Rest

| Platform | Storage Mechanism | Protected Material |
| :--- | :--- | :--- |
| **macOS** | Apple Keychain (`kSecClassGenericPassword`) | Paired session keys, SAS PIN codes |
| **Android** | Hardware-backed Android Keystore (`KeyGenParameterSpec` AES-256-GCM) | Paired session keys, SAS PIN codes |
| **Windows** | Windows Data Protection API (`ProtectedData`, CurrentUser scope) | Persistent configuration and paired keys |
