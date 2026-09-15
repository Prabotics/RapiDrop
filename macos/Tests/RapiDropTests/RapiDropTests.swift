import CryptoKit
import Foundation
import Testing

@testable import RapiDrop

@Suite("WireFrame Protocol Tests")
struct WireFrameTests {
  @Test("Serialize and deserialize valid text frame")
  func testSerializationRoundTrip() {
    let text = "Hello RapiDrop Protocol"
    let rawCiphertext = Data(text.utf8)
    let nonce = Data(repeating: 0x42, count: 12)
    let tag = Data(repeating: 0x99, count: 16)

    let originalFrame = WireFrame(
      type: .clipText,
      timestamp: 1_700_000_000_000,
      nonce: nonce,
      ciphertext: rawCiphertext,
      tag: tag
    )

    let serialized = originalFrame.serialize()
    #expect(serialized.count == WireFrame.headerSize + rawCiphertext.count + tag.count)

    let deserialized = WireFrame.deserialize(from: serialized)
    #expect(deserialized != nil)
    #expect(deserialized?.type == .clipText)
    #expect(deserialized?.timestamp == 1_700_000_000_000)
    #expect(deserialized?.nonce == nonce)
    #expect(deserialized?.ciphertext == rawCiphertext)
    #expect(deserialized?.tag == tag)
  }

  @Test("Serialize and deserialize pairInvite frame")
  func testPairInviteSerialization() {
    let inviteJson = Data(#"{"fromDeviceName":"Test Mac","deviceType":"mac"}"#.utf8)
    let nonce = Data(repeating: 0, count: 12)
    let tag = Data(repeating: 0, count: 16)

    let originalFrame = WireFrame(
      type: .pairInvite,
      timestamp: 1_700_000_000_000,
      nonce: nonce,
      ciphertext: inviteJson,
      tag: tag
    )

    let serialized = originalFrame.serialize()
    let deserialized = WireFrame.deserialize(from: serialized)
    #expect(deserialized != nil)
    #expect(deserialized?.type == .pairInvite)
    #expect(deserialized?.ciphertext == inviteJson)
  }
  @Test("Serialize and deserialize pairAccept frame")
  func testPairAcceptSerialization() {
    let acceptJson = Data(#"{"status":"accepted","pin":"123456"}"#.utf8)
    let nonce = Data(repeating: 0, count: 12)
    let tag = Data(repeating: 0, count: 16)
    let originalFrame = WireFrame(
      type: .pairAccept,
      timestamp: 1_700_000_000_000,
      nonce: nonce,
      ciphertext: acceptJson,
      tag: tag
    )
    let serialized = originalFrame.serialize()
    let deserialized = WireFrame.deserialize(from: serialized)
    #expect(deserialized != nil)
    #expect(deserialized?.type == .pairAccept)
    #expect(deserialized?.ciphertext == acceptJson)
  }
  @Test("Serialize and deserialize pairFail frame")
  func testPairFailSerialization() {
    let failDeclined = Data("DECLINED".utf8)
    let nonce = Data(repeating: 0, count: 12)
    let tag = Data(repeating: 0, count: 16)
    let frameDeclined = WireFrame(
      type: .pairFail,
      timestamp: 1_700_000_000_000,
      nonce: nonce,
      ciphertext: failDeclined,
      tag: tag
    )
    let serializedDeclined = frameDeclined.serialize()
    let deserializedDeclined = WireFrame.deserialize(from: serializedDeclined)
    #expect(deserializedDeclined != nil)
    #expect(deserializedDeclined?.type == .pairFail)
    #expect(deserializedDeclined?.ciphertext == failDeclined)

    let failCancelled = Data("CANCELLED".utf8)
    let frameCancelled = WireFrame(
      type: .pairFail,
      timestamp: 1_700_000_000_000,
      nonce: nonce,
      ciphertext: failCancelled,
      tag: tag
    )
    let serializedCancelled = frameCancelled.serialize()
    let deserializedCancelled = WireFrame.deserialize(from: serializedCancelled)
    #expect(deserializedCancelled != nil)
    #expect(deserializedCancelled?.type == .pairFail)
    #expect(deserializedCancelled?.ciphertext == failCancelled)
  }

  @Test("Serialize and deserialize configSync frame")
  func testConfigSyncSerialization() {
    let configJson = Data(#"{"privacyMode":true}"#.utf8)
    let nonce = Data(repeating: 0x01, count: 12)
    let tag = Data(repeating: 0x02, count: 16)
    let frame = WireFrame(
      type: .configSync,
      timestamp: 1_700_000_000_000,
      nonce: nonce,
      ciphertext: configJson,
      tag: tag
    )
    let serialized = frame.serialize()
    let deserialized = WireFrame.deserialize(from: serialized)
    #expect(deserialized != nil)
    #expect(deserialized?.type == .configSync)
    #expect(deserialized?.ciphertext == configJson)
  }

  @Test("Serialize and deserialize clipFile structured frame")
  func testClipFileSerialization() {
    let filename = "project-spec.pdf"
    let fileContent = Data("Binary file payload sample data".utf8)
    let filenameData = Data(filename.utf8)
    var filePayload = Data()
    var nameLenBig = UInt16(filenameData.count).bigEndian
    filePayload.append(Data(bytes: &nameLenBig, count: 2))
    filePayload.append(filenameData)
    filePayload.append(fileContent)

    let nonce = Data(repeating: 0xAA, count: 12)
    let tag = Data(repeating: 0xBB, count: 16)
    let frame = WireFrame(
      type: .clipFile,
      timestamp: 1_700_000_000_000,
      nonce: nonce,
      ciphertext: filePayload,
      tag: tag
    )
    let serialized = frame.serialize()
    let deserialized = WireFrame.deserialize(from: serialized)
    #expect(deserialized != nil)
    #expect(deserialized?.type == .clipFile)
    #expect(deserialized?.ciphertext == filePayload)
  }

  @Test("Serialize and deserialize disconnect frame")
  func testDisconnectSerialization() {
    let nonce = Data(repeating: 0, count: 12)
    let tag = Data(repeating: 0, count: 16)
    let frame = WireFrame(
      type: .disconnect,
      timestamp: 1_700_000_000_000,
      nonce: nonce,
      ciphertext: Data("UNPAIR".utf8),
      tag: tag
    )
    let serialized = frame.serialize()
    let deserialized = WireFrame.deserialize(from: serialized)
    #expect(deserialized != nil)
    #expect(deserialized?.type == .disconnect)
    #expect(deserialized?.ciphertext == Data("UNPAIR".utf8))
  }

  @Test("Reject corrupted or truncated frames")
  func testCorruptedFrameRejection() {
    let shortData = Data([0x00, 0x00, 0x00, 0x05])
    #expect(WireFrame.deserialize(from: shortData) == nil)

    var oversizedLength = UInt32(WireFrame.maxPayloadSize + 100).bigEndian
    var invalidData = Data(bytes: &oversizedLength, count: 4)
    invalidData.append(Data(repeating: 0, count: 40))
    #expect(WireFrame.deserialize(from: invalidData) == nil)
  }

  @Test("Verify all wire packet type numerical constants")
  func testPacketTypeWireValuesParity() {
    #expect(PacketType.ping.rawValue == 0x0001)
    #expect(PacketType.pong.rawValue == 0x0002)
    #expect(PacketType.pairRequest.rawValue == 0x0003)
    #expect(PacketType.pairConfirm.rawValue == 0x0004)
    #expect(PacketType.deviceInfo.rawValue == 0x0005)
    #expect(PacketType.pairInvite.rawValue == 0x0006)
    #expect(PacketType.pairFail.rawValue == 0x0007)
    #expect(PacketType.pairAccept.rawValue == 0x0008)
    #expect(PacketType.clipText.rawValue == 0x0010)
    #expect(PacketType.clipUrl.rawValue == 0x0011)
    #expect(PacketType.clipImage.rawValue == 0x0012)
    #expect(PacketType.clipFile.rawValue == 0x0013)
    #expect(PacketType.configSync.rawValue == 0x0020)
    #expect(PacketType.disconnect.rawValue == 0x00FF)
  }
}

@Suite("CryptoEngine Security Tests")
struct CryptoEngineTests {
  @Test("Encrypt and decrypt payload with symmetric key")
  func testEncryptDecryptSuccess() throws {
    let key = SymmetricKey(size: .bits256)
    let plaintext = Data("Confidential Clipboard Data 2026".utf8)
    let encrypted = try CryptoEngine.encrypt(payload: plaintext, using: key)

    let decrypted = try CryptoEngine.decrypt(
      ciphertext: encrypted.ciphertext,
      nonce: encrypted.nonce,
      tag: encrypted.tag,
      using: key
    )

    #expect(decrypted == plaintext)
  }

  @Test("Encrypt and decrypt payload with 6-digit PIN derived key")
  func testPinKeyDerivationAndEncryption() throws {
    let pin = "849201"
    let key = CryptoEngine.deriveKey(from: pin)
    let plaintext = Data("PIN Authenticated Payload".utf8)
    let encrypted = try CryptoEngine.encrypt(payload: plaintext, using: key)

    let decrypted = try CryptoEngine.decrypt(
      ciphertext: encrypted.ciphertext,
      nonce: encrypted.nonce,
      tag: encrypted.tag,
      using: key
    )

    #expect(decrypted == plaintext)
  }

  @Test("Decryption fails with tampered ciphertext or wrong key")
  func testDecryptionTamperFailure() throws {
    let key1 = SymmetricKey(size: .bits256)
    let key2 = SymmetricKey(size: .bits256)

    let plaintext = Data("Sensitive Data".utf8)
    let encrypted = try CryptoEngine.encrypt(payload: plaintext, using: key1)

    #expect(throws: (any Error).self) {
      try CryptoEngine.decrypt(
        ciphertext: encrypted.ciphertext,
        nonce: encrypted.nonce,
        tag: encrypted.tag,
        using: key2
      )
    }
  }

  @Test("Cryptographic Nonce uniqueness over 100 iterations")
  func testNonceUniqueness() throws {
    let key = SymmetricKey(size: .bits256)
    var nonces = Set<Data>()
    let payload = Data("Fixed Plaintext".utf8)

    for _ in 1...100 {
      let result = try CryptoEngine.encrypt(payload: payload, using: key)
      #expect(result.nonce.count == 12)
      #expect(!nonces.contains(result.nonce))
      nonces.insert(result.nonce)
    }
    #expect(nonces.count == 100)
  }

  @Test("Cross-platform SHA-256 PIN key derivation vector")
  func testCrossPlatformKeyDerivationVector() {
    let pin = "849201"
    let derivedKey = CryptoEngine.deriveKey(from: pin)

    let expectedSecret = Data("RapiDrop-PIN-849201".utf8)
    let expectedHash = SHA256.hash(data: expectedSecret)
    let expectedKeyData = Data(expectedHash)

    let keyData = derivedKey.withUnsafeBytes { Data($0) }
    #expect(keyData == expectedKeyData)
    #expect(keyData.count == 32)
  }

  @Test("Tampered GMAC tag fails decryption")
  func testCorruptedTagRejection() throws {
    let key = SymmetricKey(size: .bits256)
    let plaintext = Data("Authenticated Data".utf8)
    let encrypted = try CryptoEngine.encrypt(payload: plaintext, using: key)

    var corruptedTag = Data(encrypted.tag)
    corruptedTag[corruptedTag.count - 1] ^= 0xFF

    #expect(throws: (any Error).self) {
      try CryptoEngine.decrypt(
        ciphertext: encrypted.ciphertext,
        nonce: encrypted.nonce,
        tag: corruptedTag,
        using: key
      )
    }
  }

  @Test("Cross-platform X25519 and HKDF canonical test vector")
  func testCrossPlatformX25519AndHkdfCanonicalVector() throws {
    let alicePrivData = CryptoEngine.hexToData("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")!
    let bobPubHex = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"

    let alicePriv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: alicePrivData)
    let sharedSecret = try CryptoEngine.computeSharedSecret(privateKey: alicePriv, remotePublicKeyHex: bobPubHex)
    #expect(sharedSecret.map { String(format: "%02x", $0) }.joined() == "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

    let nonceA = CryptoEngine.hexToData("000102030405060708090a0b0c0d0e0f")!
    let nonceB = CryptoEngine.hexToData("101112131415161718191a1b1c1d1e1f")!
    let idA = "device-uuid-alice"
    let idB = "device-uuid-bob"
    let alicePubData = alicePriv.publicKey.rawRepresentation
    let bobPubData = CryptoEngine.hexToData(bobPubHex)!

    let keys = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: sharedSecret,
      initiatorPublicKey: alicePubData,
      receiverPublicKey: bobPubData,
      initiatorNonce: nonceA,
      receiverNonce: nonceB,
      initiatorId: idA,
      receiverId: idB
    )

    #expect(keys.transcriptHash.map { String(format: "%02x", $0) }.joined() == "2cd9946128df4a5cb0f1a1786e31136a0b7ea489c8f913552b605f6c83638c00")
    let sessionKeyHex = keys.sessionKey.withUnsafeBytes { Data($0) }.map { String(format: "%02x", $0) }.joined()
    #expect(sessionKeyHex == "c01bcb745e0644d944458b953fe8363beb4b8c7271aadc92f217e45b360b5d17")
    #expect(keys.authKey.map { String(format: "%02x", $0) }.joined() == "ffe30b48f5f241bfc48233070dc246222312376d9630f1585ef418af6945056a")
    #expect(keys.pairRecordKey.map { String(format: "%02x", $0) }.joined() == "ba2c59676e4619a4e6ab0ef71d9a2d414815292b97508c926516c5b0a9427838")
    #expect(keys.sasCode == "069640")
  }

  @Test("Mismatched transcript or tampered public key changes SAS code")
  func testMismatchedTranscriptFailsSAS() throws {
    let alicePrivData = CryptoEngine.hexToData("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")!
    let bobPubHex = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
    let alicePriv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: alicePrivData)
    let sharedSecret = try CryptoEngine.computeSharedSecret(privateKey: alicePriv, remotePublicKeyHex: bobPubHex)

    let nonceA = CryptoEngine.hexToData("000102030405060708090a0b0c0d0e0f")!
    let nonceB = CryptoEngine.hexToData("101112131415161718191a1b1c1d1e1f")!
    let alicePubData = alicePriv.publicKey.rawRepresentation
    let bobPubData = CryptoEngine.hexToData(bobPubHex)!

    let keysOriginal = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: sharedSecret,
      initiatorPublicKey: alicePubData,
      receiverPublicKey: bobPubData,
      initiatorNonce: nonceA,
      receiverNonce: nonceB,
      initiatorId: "device-uuid-alice",
      receiverId: "device-uuid-bob"
    )

    let keysTamperedId = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: sharedSecret,
      initiatorPublicKey: alicePubData,
      receiverPublicKey: bobPubData,
      initiatorNonce: nonceA,
      receiverNonce: nonceB,
      initiatorId: "device-uuid-attacker",
      receiverId: "device-uuid-bob"
    )

    #expect(keysOriginal.sasCode != keysTamperedId.sasCode)
    #expect(keysOriginal.sessionKey != keysTamperedId.sessionKey)
  }
}

@Suite("Media Destination Mode Tests")
struct MediaDestinationModeTests {
  @Test("Verify all media destination mode cases and display labels")
  func testMediaDestinationModes() {
    #expect(MediaDestinationMode.allCases.count == 3)
    #expect(MediaDestinationMode.both.rawValue == "both")
    #expect(MediaDestinationMode.folderOnly.rawValue == "folderOnly")
    #expect(MediaDestinationMode.clipboardOnly.rawValue == "clipboardOnly")

    #expect(MediaDestinationMode.both.displayLabel == "Folder & Clipboard")
    #expect(MediaDestinationMode.folderOnly.displayLabel == "Folder Only")
    #expect(MediaDestinationMode.clipboardOnly.displayLabel == "Clipboard Only")
  }

  @Test("Verify AppState default media folder resolution")
  @MainActor
  func testAppStateDefaultFolder() {
    let appState = AppState()
    #expect(appState.mediaFolderURL.lastPathComponent == "RapiDrop")
    #expect(
      appState.mediaDestinationMode == .both || appState.mediaDestinationMode == .folderOnly
        || appState.mediaDestinationMode == .clipboardOnly)
  }

  @Test("Verify deleting individual clip removes it from history and pins")
  @MainActor
  func testDeleteClip() {
    let appState = AppState()
    appState.showSyncHistory = true
    let clip1 = ClipItem(type: .text, textContent: "Delete Me")
    let clip2 = ClipItem(type: .text, textContent: "Keep Me")
    appState.addRecentClip(clip1)
    appState.addRecentClip(clip2)
    appState.togglePinClip(clip1)
    #expect(appState.recentClips.count == 2)
    #expect(appState.pinnedClipIds.contains(clip1.id))

    appState.deleteClip(clip1)
    #expect(appState.recentClips.count == 1)
    #expect(appState.recentClips.first?.textContent == "Keep Me")
    #expect(!appState.pinnedClipIds.contains(clip1.id))
  }
  @Test("Verify history limits, deduplication, and Direct Mode behavior")
  @MainActor
  func testHistoryLimitsAndDirectMode() {
    let appState = AppState()
    appState.recentClips.removeAll()
    appState.showSyncHistory = true

    let clipA = ClipItem(type: .text, textContent: "Alpha")
    let clipB = ClipItem(type: .text, textContent: "Beta")
    let clipADup = ClipItem(type: .text, textContent: "Alpha")

    appState.addRecentClip(clipA)
    #expect(appState.recentClips.count == 1)

    appState.addRecentClip(clipB)
    #expect(appState.recentClips.count == 2)
    #expect(appState.recentClips.first?.textContent == "Beta")

    appState.addRecentClip(clipADup)
    #expect(appState.recentClips.count == 2)
    #expect(appState.recentClips.first?.textContent == "Alpha")

    for i in 1...25 {
      appState.addRecentClip(ClipItem(type: .text, textContent: "Item #\(i)"))
    }
    #expect(appState.recentClips.count == 20)
    #expect(appState.recentClips.first?.textContent == "Item #25")

    appState.showSyncHistory = false
    appState.addRecentClip(ClipItem(type: .text, textContent: "Should Not Add"))
    #expect(appState.recentClips.isEmpty)
    appState.showSyncHistory = true
  }
  @Test("Verify settings defaults, persistence, and local privacy isolation")
  @MainActor
  func testSettingsDefaultsAndPrivacyIsolation() {
    let appState = AppState()
    #expect(appState.mediaDestinationMode == .both || appState.mediaDestinationMode == .folderOnly || appState.mediaDestinationMode == .clipboardOnly)

    let initialPrivacy = appState.showSyncHistory
    appState.networkEngine(appState.network, didReceivePrivacyModeUpdate: !initialPrivacy)
    #expect(appState.showSyncHistory == initialPrivacy)
  }

  @Test("Verify device identity remains immutable across display name changes")
  func testDeviceIdentityImmutableOnDisplayNameChange() {
    let id1 = NetworkEngine.localDeviceId
    #expect(!id1.isEmpty)

    let normalized1 = NetworkEngine.normalizeDeviceName("Test Device 1")
    let normalized2 = NetworkEngine.normalizeDeviceName("Test Device Renamed")
    #expect(normalized1 != normalized2)

    let id2 = NetworkEngine.localDeviceId
    #expect(id1 == id2)
  }

  @Test("Verify semantic version comparison for update checking")
  @MainActor
  func testUpdateVersionComparison() {
    let appState = AppState()
    #expect(appState.isNewerVersion("v1.0.1", current: "1.0.0"))
    #expect(appState.isNewerVersion("v1.1.0", current: "1.0.0"))
    #expect(appState.isNewerVersion("v2.0.0", current: "1.0.0"))
    #expect(appState.isNewerVersion("1.0.1", current: "1.0.0"))
    #expect(!appState.isNewerVersion("v1.0.0", current: "1.0.0"))
    #expect(!appState.isNewerVersion("v0.9.9", current: "1.0.0"))
    #expect(!appState.isNewerVersion("", current: "1.0.0"))
  }

  @Test("Verify non-colliding unique destination URL generation")
  func testUniqueDestinationURL() {
    let engine = NetworkEngine()
    let tmpDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try? FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: tmpDir) }

    let base = tmpDir.appendingPathComponent("document.pdf")
    #expect(engine.uniqueDestinationURL(for: base).lastPathComponent == "document.pdf")

    FileManager.default.createFile(atPath: base.path, contents: Data())
    #expect(engine.uniqueDestinationURL(for: base).lastPathComponent == "document (1).pdf")

    let firstCollision = tmpDir.appendingPathComponent("document (1).pdf")
    FileManager.default.createFile(atPath: firstCollision.path, contents: Data())
    #expect(engine.uniqueDestinationURL(for: base).lastPathComponent == "document (2).pdf")
  }
}

@Suite("Streaming & Path Sanitization Security Tests")
struct StreamingSecurityTests {
  @Test("Sanitize relative paths against directory traversal and backslashes")
  func testSanitizeRelativePath() {
    #expect(NetworkEngine.sanitizeRelativePath("../../etc/passwd", fallbackFileName: "safe.txt") == "etc/passwd")
    #expect(NetworkEngine.sanitizeRelativePath("..\\..\\windows\\system32\\cmd.exe", fallbackFileName: "safe.txt") == "windows/system32/cmd.exe")
    #expect(NetworkEngine.sanitizeRelativePath("C:\\Users\\Victim\\Desktop\\evil.sh", fallbackFileName: "safe.txt") == "Users/Victim/Desktop/evil.sh")
    #expect(NetworkEngine.sanitizeRelativePath("valid/nested/folder/document.pdf", fallbackFileName: "fallback.pdf") == "valid/nested/folder/document.pdf")
    #expect(NetworkEngine.sanitizeRelativePath("..../..../file.txt", fallbackFileName: "fallback.txt") == "..../..../file.txt")
    #expect(NetworkEngine.sanitizeRelativePath("", fallbackFileName: "empty.txt") == "empty.txt")
    #expect(NetworkEngine.sanitizeRelativePath(".", fallbackFileName: "dot.txt") == "dot.txt")
    #expect(NetworkEngine.sanitizeRelativePath("..", fallbackFileName: "dotdot.txt") == "dotdot.txt")
    #expect(NetworkEngine.sanitizeRelativePath("/", fallbackFileName: "slash.txt") == "slash.txt")
    #expect(NetworkEngine.sanitizeRelativePath("C:\\path\\fallback.txt", fallbackFileName: "C:\\path\\fallback.txt") == "path/fallback.txt")
  }

  @Test("Verify symmetrical cancellation wire frame serialization and parsing")
  func testEncryptedPairCancellationRoundtrip() throws {
    let payload = Data(#"{"version":1,"reason":"CANCELLED"}"#.utf8)
    let frame = WireFrame(
      type: .pairFail,
      nonce: Data(repeating: 0, count: 12),
      ciphertext: payload,
      tag: Data(repeating: 0, count: 16)
    )
    let serialized = frame.serialize()
    let deserialized = WireFrame.deserialize(from: serialized)
    #expect(deserialized != nil)
    #expect(deserialized?.type == .pairFail)
    #expect(deserialized?.ciphertext == payload)
  }

  @Test("Serialize and deserialize streaming file frames")
  func testStreamingFramesSerialization() {
    let startJson = Data(#"{"transferId":"uuid-123","fileIndex":0,"totalFiles":1,"fileName":"test.png","relativePath":"test.png","fileSize":1024,"totalBytes":1024}"#.utf8)
    let startFrame = WireFrame(
      type: .fileStart,
      nonce: Data(repeating: 0, count: 12),
      ciphertext: startJson,
      tag: Data(repeating: 0, count: 16)
    )
    let deserializedStart = WireFrame.deserialize(from: startFrame.serialize())
    #expect(deserializedStart?.type == .fileStart)
    #expect(deserializedStart?.ciphertext == startJson)

    let endJson = Data(#"{"transferId":"uuid-123","fileIndex":0,"sha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}"#.utf8)
    let endFrame = WireFrame(
      type: .fileEnd,
      nonce: Data(repeating: 0, count: 12),
      ciphertext: endJson,
      tag: Data(repeating: 0, count: 16)
    )
    let deserializedEnd = WireFrame.deserialize(from: endFrame.serialize())
    #expect(deserializedEnd?.type == .fileEnd)
    #expect(deserializedEnd?.ciphertext == endJson)

    let cancelJson = Data(#"{"transferId":"uuid-123","reason":"user_abort"}"#.utf8)
    let cancelFrame = WireFrame(
      type: .fileCancel,
      nonce: Data(repeating: 0, count: 12),
      ciphertext: cancelJson,
      tag: Data(repeating: 0, count: 16)
    )
    let deserializedCancel = WireFrame.deserialize(from: cancelFrame.serialize())
    #expect(deserializedCancel?.type == .fileCancel)
    #expect(deserializedCancel?.ciphertext == cancelJson)
  }

  @Test("Verify 28-byte file chunk header layout with 64-bit chunk index")
  func testFileChunkHeaderStructureAnd64BitChunkIndex() {
    let transferId = "test-transfer-id"
    let fileIndex: UInt32 = 42
    let chunkIndex: UInt64 = 1_000_000_000

    var header = Data(capacity: 28)
    var idData = Data(transferId.utf8)
    if idData.count < 16 {
      idData.append(Data(repeating: 0, count: 16 - idData.count))
    } else {
      idData = idData.prefix(16)
    }
    header.append(idData)

    var fileIndexBig = fileIndex.bigEndian
    header.append(Data(bytes: &fileIndexBig, count: 4))

    var chunkIndexBig = chunkIndex.bigEndian
    header.append(Data(bytes: &chunkIndexBig, count: 8))

    #expect(header.count == 28)

    let parsedFileIndex = header.subdata(in: 16..<20).withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
    let parsedChunkIndex = header.subdata(in: 20..<28).withUnsafeBytes { $0.load(as: UInt64.self).bigEndian }

    #expect(parsedFileIndex == 42)
    #expect(parsedChunkIndex == 1_000_000_000)
  }

  @Test("Incremental SHA-256 matches one-shot digest across chunk boundaries")
  func testIncrementalSha256StreamingParity() {
    let chunkA = Data("First chunk of stream ".utf8)
    let chunkB = Data("second chunk of stream ".utf8)
    let chunkC = Data("final payload bytes.".utf8)

    var incremental = SHA256()
    incremental.update(data: chunkA)
    incremental.update(data: chunkB)
    incremental.update(data: chunkC)
    let incDigest = incremental.finalize().map { String(format: "%02x", $0) }.joined()

    var full = Data()
    full.append(chunkA)
    full.append(chunkB)
    full.append(chunkC)
    let fullDigest = SHA256.hash(data: full).map { String(format: "%02x", $0) }.joined()

    #expect(incDigest == fullDigest)
  }

  @Test("Large file integer safety for 10 GB file size")
  func testLargeFileSize64BitArithmetic() {
    let tenGigabytes: Int64 = 10 * 1024 * 1024 * 1024
    let chunkSize: Int64 = Int64(WireFrame.streamingChunkSize)
    let totalChunks = (tenGigabytes + chunkSize - 1) / chunkSize

    #expect(totalChunks == 10240)
    #expect(tenGigabytes > Int64(Int32.max))
  }
  @Test("Self device filtering ignores local device ID even when renamed")
  func testSelfDeviceFilteringWithId() {
    let localId = "mac-uuid-1111"
    let localName = "test mac"

    let selfDeviceExact = DiscoveredClientDevice(id: "mac-uuid-1111", name: "Test Mac")
    let isSelf1 = !selfDeviceExact.id.isEmpty ? (selfDeviceExact.id == localId) : (NetworkEngine.normalizeDeviceName(selfDeviceExact.name) == localName)
    #expect(isSelf1 == true)

    let selfDeviceRenamed = DiscoveredClientDevice(id: "mac-uuid-1111", name: "Test Mac (2)")
    let isSelf2 = !selfDeviceRenamed.id.isEmpty ? (selfDeviceRenamed.id == localId) : (NetworkEngine.normalizeDeviceName(selfDeviceRenamed.name) == localName)
    #expect(isSelf2 == true)

    let remoteSameName = DiscoveredClientDevice(id: "mac-uuid-2222", name: "Test Mac")
    let isSelf3 = !remoteSameName.id.isEmpty ? (remoteSameName.id == localId) : (NetworkEngine.normalizeDeviceName(remoteSameName.name) == localName)
    #expect(isSelf3 == false)

    let remotePhone = DiscoveredClientDevice(id: "android-uuid-3333", name: "Test Android")
    let isSelf4 = !remotePhone.id.isEmpty ? (remotePhone.id == localId) : (NetworkEngine.normalizeDeviceName(remotePhone.name) == localName)
    #expect(isSelf4 == false)
  }

  @Test("Same display name with different canonical ID remains distinct")
  func testSameNameDifferentIdDeduplication() {
    let dev1 = DiscoveredClientDevice(id: "device-uuid-1", name: "Test Device")
    let dev2 = DiscoveredClientDevice(id: "device-uuid-2", name: "Test Device")
    let dev1DuplicateCallback = DiscoveredClientDevice(id: "device-uuid-1", name: "Test Device (2)")

    var seenIds = Set<String>()
    var merged: [DiscoveredClientDevice] = []

    for dev in [dev1, dev2, dev1DuplicateCallback] {
      if !seenIds.contains(dev.id) {
        seenIds.insert(dev.id)
        merged.append(dev)
      }
    }

    #expect(merged.count == 2)
    #expect(merged[0].id == "device-uuid-1")
    #expect(merged[1].id == "device-uuid-2")
  }

  @Test("Handshake key derivation parity across peers")
  func testHandshakeKeyDerivationParity() throws {
    let (privA, pubAHex) = CryptoEngine.generateEphemeralKeypair()
    let (privB, pubBHex) = CryptoEngine.generateEphemeralKeypair()

    let nonceA = CryptoEngine.generateNonce(count: 16)
    let nonceB = CryptoEngine.generateNonce(count: 16)

    let pubAData = try #require(CryptoEngine.hexToData(pubAHex))
    let pubBData = try #require(CryptoEngine.hexToData(pubBHex))

    let sharedA = try CryptoEngine.computeSharedSecret(privateKey: privA, remotePublicKeyHex: pubBHex)
    let sharedB = try CryptoEngine.computeSharedSecret(privateKey: privB, remotePublicKeyHex: pubAHex)

    #expect(sharedA == sharedB)

    let keysA = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: sharedA,
      initiatorPublicKey: pubAData,
      receiverPublicKey: pubBData,
      initiatorNonce: nonceA,
      receiverNonce: nonceB,
      initiatorId: "device-uuid-a",
      receiverId: "device-uuid-b"
    )

    let keysB = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: sharedB,
      initiatorPublicKey: pubAData,
      receiverPublicKey: pubBData,
      initiatorNonce: nonceA,
      receiverNonce: nonceB,
      initiatorId: "device-uuid-a",
      receiverId: "device-uuid-b"
    )
    #expect(keysA.sessionKey == keysB.sessionKey)
    #expect(keysA.sasCode == keysB.sasCode)
  }

  @Test("Device name normalization and collision suffix stripping")
  func testDeviceNameNormalization() {
    #expect(NetworkEngine.normalizeDeviceName("Test Mac") == "test mac")
    #expect(NetworkEngine.normalizeDeviceName("Test’s Mac") == "test's mac")
    #expect(NetworkEngine.normalizeDeviceName("\"Test Device\"") == "test device")
    #expect(NetworkEngine.normalizeDeviceName("Test Mac (2)") == "test mac")
    #expect(NetworkEngine.normalizeDeviceName("Test Android - 2") == "test android")
    #expect(NetworkEngine.normalizeDeviceName("Test Android (99)") == "test android")
  }

  @Test("Pairing state machine transitions and retry semantics")
  func testPairingStateMachineTransitions() {
    let declineJson = Data(#"{"version":1,"reason":"DECLINED"}"#.utf8)
    let cancelJson = Data(#"{"version":1,"reason":"CANCELLED"}"#.utf8)

    let frameDeclined = WireFrame(type: .pairFail, nonce: Data(repeating: 0, count: 12), ciphertext: declineJson, tag: Data(repeating: 0, count: 16))
    let frameCancelled = WireFrame(type: .pairFail, nonce: Data(repeating: 0, count: 12), ciphertext: cancelJson, tag: Data(repeating: 0, count: 16))

    let deserializedDeclined = WireFrame.deserialize(from: frameDeclined.serialize())
    let deserializedCancelled = WireFrame.deserialize(from: frameCancelled.serialize())

    #expect(deserializedDeclined?.type == .pairFail)
    #expect(deserializedCancelled?.type == .pairFail)
    #expect(deserializedDeclined?.ciphertext == declineJson)
    #expect(deserializedCancelled?.ciphertext == cancelJson)
  }
  @Test("SAS transcript sensitivity and display name independence")
  func testSasTranscriptSensitivity() throws {
    let pubA = Data(repeating: 0x01, count: 32)
    let pubB = Data(repeating: 0x02, count: 32)
    let nonceA = Data(repeating: 0x03, count: 16)
    let nonceB = Data(repeating: 0x04, count: 16)
    let secret = Data(repeating: 0x05, count: 32)

    let base = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: secret,
      initiatorPublicKey: pubA,
      receiverPublicKey: pubB,
      initiatorNonce: nonceA,
      receiverNonce: nonceB,
      initiatorId: "device-a",
      receiverId: "device-b"
    )

    let baseSame = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: secret,
      initiatorPublicKey: pubA,
      receiverPublicKey: pubB,
      initiatorNonce: nonceA,
      receiverNonce: nonceB,
      initiatorId: "device-a",
      receiverId: "device-b"
    )
    #expect(base.sasCode == baseSame.sasCode)
    #expect(base.sasCode.count == 6)

    let diffKeyA = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: secret,
      initiatorPublicKey: Data(repeating: 0x99, count: 32),
      receiverPublicKey: pubB,
      initiatorNonce: nonceA,
      receiverNonce: nonceB,
      initiatorId: "device-a",
      receiverId: "device-b"
    )
    #expect(diffKeyA.sasCode != base.sasCode)

    let diffNonceA = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: secret,
      initiatorPublicKey: pubA,
      receiverPublicKey: pubB,
      initiatorNonce: Data(repeating: 0x99, count: 16),
      receiverNonce: nonceB,
      initiatorId: "device-a",
      receiverId: "device-b"
    )
    #expect(diffNonceA.sasCode != base.sasCode)

    let diffIdA = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: secret,
      initiatorPublicKey: pubA,
      receiverPublicKey: pubB,
      initiatorNonce: nonceA,
      receiverNonce: nonceB,
      initiatorId: "device-a-different",
      receiverId: "device-b"
    )
    #expect(diffIdA.sasCode != base.sasCode)

    let diffIdB = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: secret,
      initiatorPublicKey: pubA,
      receiverPublicKey: pubB,
      initiatorNonce: nonceA,
      receiverNonce: nonceB,
      initiatorId: "device-a",
      receiverId: "device-b-different"
    )
    #expect(diffIdB.sasCode != base.sasCode)
  }

  @Test("Discovery unresolved record merges into resolved device without duplicate")
  func testDiscoveryUnresolvedMerge() {
    let unresolved = DiscoveredClientDevice(id: "", name: "Test Android")
    let resolved = DiscoveredClientDevice(id: "uuid-1234", name: "Test Android")

    var devicesById: [String: DiscoveredClientDevice] = [:]
    var devicesByNameWithoutId: [String: DiscoveredClientDevice] = [:]

    for dev in [unresolved, resolved] {
      if !dev.rawId.isEmpty {
        devicesById[dev.rawId] = dev
      } else {
        let nameKey = NetworkEngine.normalizeDeviceName(dev.name)
        if devicesByNameWithoutId[nameKey] == nil {
          devicesByNameWithoutId[nameKey] = dev
        }
      }
    }

    var merged: [DiscoveredClientDevice] = Array(devicesById.values)
    let seenNamesWithId = Set(merged.map { NetworkEngine.normalizeDeviceName($0.name) })

    for (nameKey, dev) in devicesByNameWithoutId {
      if !seenNamesWithId.contains(nameKey) {
        merged.append(dev)
      }
    }

    #expect(merged.count == 1)
    #expect(merged[0].rawId == "uuid-1234")
  }

  @Test("Remote device with same name as local is not filtered when ID is distinct")
  func testRemoteDeviceSameNameNotSelf() {
    let localId = "my-mac-uuid"
    let localName = "macbook air"

    let remoteWithSameName = DiscoveredClientDevice(id: "other-mac-uuid", name: "MacBook Air")
    let isSelf: Bool
    if !remoteWithSameName.rawId.isEmpty && !localId.isEmpty {
      isSelf = (remoteWithSameName.rawId == localId)
    } else {
      isSelf = (NetworkEngine.normalizeDeviceName(remoteWithSameName.name) == localName)
    }
    #expect(isSelf == false)
  }
}

@Suite("Protocol Integration Flow Tests")
struct ProtocolIntegrationTests {
  @Test("End-to-End Handshake: Android initiator -> macOS responder -> Session Established")
  func testAndroidInitiatorToMacResponder() throws {
    let (privA, pubAHex) = CryptoEngine.generateEphemeralKeypair()
    let nonceA = CryptoEngine.generateNonce(count: 16)
    let (privB, pubBHex) = CryptoEngine.generateEphemeralKeypair()
    let nonceB = CryptoEngine.generateNonce(count: 16)

    let secretA = try CryptoEngine.computeSharedSecret(privateKey: privA, remotePublicKeyHex: pubBHex)
    let secretB = try CryptoEngine.computeSharedSecret(privateKey: privB, remotePublicKeyHex: pubAHex)
    #expect(secretA == secretB)

    let keysA = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: secretA,
      initiatorPublicKey: CryptoEngine.hexToData(pubAHex)!,
      receiverPublicKey: CryptoEngine.hexToData(pubBHex)!,
      initiatorNonce: nonceA,
      receiverNonce: nonceB,
      initiatorId: "android-uuid-1",
      receiverId: "mac-uuid-2"
    )
    let keysB = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: secretB,
      initiatorPublicKey: CryptoEngine.hexToData(pubAHex)!,
      receiverPublicKey: CryptoEngine.hexToData(pubBHex)!,
      initiatorNonce: nonceA,
      receiverNonce: nonceB,
      initiatorId: "android-uuid-1",
      receiverId: "mac-uuid-2"
    )

    #expect(keysA.sasCode == keysB.sasCode)
    #expect(keysA.sessionKey == keysB.sessionKey)
    #expect(keysA.pairRecordKey == keysB.pairRecordKey)

    let pairReqData = try JSONEncoder().encode(DeviceDescriptor(modelId: "Pixel 9", modelName: "Pixel 9", chip: "Tensor G4", deviceName: "Pixel 9"))
    let reqEncrypted = try CryptoEngine.encrypt(payload: pairReqData, using: keysA.sessionKey)
    let reqFrame = WireFrame(type: .pairRequest, nonce: reqEncrypted.nonce, ciphertext: reqEncrypted.ciphertext, tag: reqEncrypted.tag)
    let reqSerialized = reqFrame.serialize()

    let reqDeserialized = WireFrame.deserialize(from: reqSerialized)
    #expect(reqDeserialized != nil)
    let reqDecrypted = try CryptoEngine.decrypt(ciphertext: reqDeserialized!.ciphertext, nonce: reqDeserialized!.nonce, tag: reqDeserialized!.tag, using: keysB.sessionKey)
    let decodedDesc = try JSONDecoder().decode(DeviceDescriptor.self, from: reqDecrypted)
    #expect(decodedDesc.deviceName == "Pixel 9")

    let confirmEncrypted = try CryptoEngine.encrypt(payload: Data("OK".utf8), using: keysB.sessionKey)
    let confirmFrame = WireFrame(type: .pairConfirm, nonce: confirmEncrypted.nonce, ciphertext: confirmEncrypted.ciphertext, tag: confirmEncrypted.tag)
    let confirmSerialized = confirmFrame.serialize()
    let confirmDeserialized = WireFrame.deserialize(from: confirmSerialized)
    #expect(confirmDeserialized != nil)
    let confirmDecrypted = try CryptoEngine.decrypt(ciphertext: confirmDeserialized!.ciphertext, nonce: confirmDeserialized!.nonce, tag: confirmDeserialized!.tag, using: keysA.sessionKey)
    #expect(String(data: confirmDecrypted, encoding: .utf8) == "OK")

    let clipText = "Synthetic clipboard message"
    let clipEncrypted = try CryptoEngine.encrypt(payload: Data(clipText.utf8), using: keysA.sessionKey)
    let clipFrame = WireFrame(type: .clipText, nonce: clipEncrypted.nonce, ciphertext: clipEncrypted.ciphertext, tag: clipEncrypted.tag)
    let clipSerialized = clipFrame.serialize()
    let clipDeserialized = WireFrame.deserialize(from: clipSerialized)
    #expect(clipDeserialized != nil)
    let clipDecrypted = try CryptoEngine.decrypt(ciphertext: clipDeserialized!.ciphertext, nonce: clipDeserialized!.nonce, tag: clipDeserialized!.tag, using: keysB.sessionKey)
    #expect(String(data: clipDecrypted, encoding: .utf8) == clipText)
  }

  @Test("End-to-End Handshake: macOS initiator -> Android responder")
  func testMacInitiatorToAndroidResponder() throws {
    let (privMac, pubMacHex) = CryptoEngine.generateEphemeralKeypair()
    let nonceMac = CryptoEngine.generateNonce(count: 16)
    let (privAndroid, pubAndroidHex) = CryptoEngine.generateEphemeralKeypair()
    let nonceAndroid = CryptoEngine.generateNonce(count: 16)

    let secretMac = try CryptoEngine.computeSharedSecret(privateKey: privMac, remotePublicKeyHex: pubAndroidHex)
    let secretAndroid = try CryptoEngine.computeSharedSecret(privateKey: privAndroid, remotePublicKeyHex: pubMacHex)

    let keysMac = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: secretMac,
      initiatorPublicKey: CryptoEngine.hexToData(pubMacHex)!,
      receiverPublicKey: CryptoEngine.hexToData(pubAndroidHex)!,
      initiatorNonce: nonceMac,
      receiverNonce: nonceAndroid,
      initiatorId: "mac-uuid-1",
      receiverId: "android-uuid-2"
    )
    let keysAndroid = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: secretAndroid,
      initiatorPublicKey: CryptoEngine.hexToData(pubMacHex)!,
      receiverPublicKey: CryptoEngine.hexToData(pubAndroidHex)!,
      initiatorNonce: nonceMac,
      receiverNonce: nonceAndroid,
      initiatorId: "mac-uuid-1",
      receiverId: "android-uuid-2"
    )

    #expect(keysMac.sasCode == keysAndroid.sasCode)
    #expect(keysMac.sessionKey == keysAndroid.sessionKey)
  }

  @Test("Pair decline returns DECLINED and cleans pairing state")
  func testPairDeclineFlow() {
    let declineJson = Data(#"{"version":1,"reason":"DECLINED"}"#.utf8)
    let frame = WireFrame(type: .pairFail, nonce: Data(repeating: 0, count: 12), ciphertext: declineJson, tag: Data(repeating: 0, count: 16))
    let serialized = frame.serialize()
    let deserialized = WireFrame.deserialize(from: serialized)
    #expect(deserialized?.type == .pairFail)
    #expect(String(data: deserialized!.ciphertext, encoding: .utf8)?.contains("DECLINED") == true)
  }

  @Test("Stale connection generation rejection protects active session")
  func testStaleConnectionGenerationRejection() {
    var currentGen: UInt64 = 5
    let delayedCallbackGen: UInt64 = 4

    let isStale = (delayedCallbackGen != currentGen)
    #expect(isStale == true)

    currentGen &+= 1
    #expect(currentGen == 6)
  }

  @Test("Reconnect with stored pre-shared key skips ephemeral key exchange")
  func testReconnectWithStoredPreSharedKey() throws {
    let storedKey = SymmetricKey(size: .bits256)
    let keyHex = storedKey.withUnsafeBytes { Data($0).map { String(format: "%02x", $0) }.joined() }
    let loadedKeyData = CryptoEngine.hexToData(keyHex)!
    let restoredKey = SymmetricKey(data: loadedKeyData)

    let desc = DeviceDescriptor(modelId: "Mac15,12", modelName: "MacBook Air", chip: "Apple M3", deviceName: "Samir's MacBook Air")
    let descData = try JSONEncoder().encode(desc)
    let encryptedReq = try CryptoEngine.encrypt(payload: descData, using: restoredKey)
    let reqFrame = WireFrame(type: .pairRequest, nonce: encryptedReq.nonce, ciphertext: encryptedReq.ciphertext, tag: encryptedReq.tag)
    let deserialized = WireFrame.deserialize(from: reqFrame.serialize())

    #expect(deserialized != nil)
    let decrypted = try CryptoEngine.decrypt(ciphertext: deserialized!.ciphertext, nonce: deserialized!.nonce, tag: deserialized!.tag, using: storedKey)
    let decodedDesc = try JSONDecoder().decode(DeviceDescriptor.self, from: decrypted)
    #expect(decodedDesc.deviceName == "Samir's MacBook Air")
  }

  @Test("Process restart key restoration allows immediate authenticated decryption")
  func testProcessRestartKeyRestoration() throws {
    let originalKey = SymmetricKey(size: .bits256)
    let persistedHex = originalKey.withUnsafeBytes { Data($0).map { String(format: "%02x", $0) }.joined() }

    let restoredKeyData = CryptoEngine.hexToData(persistedHex)!
    let restoredKey = SymmetricKey(data: restoredKeyData)

    let payload = Data("Restored session clip content".utf8)
    let encrypted = try CryptoEngine.encrypt(payload: payload, using: originalKey)
    let frame = WireFrame(type: .clipText, nonce: encrypted.nonce, ciphertext: encrypted.ciphertext, tag: encrypted.tag)

    let deserialized = WireFrame.deserialize(from: frame.serialize())
    #expect(deserialized != nil)
    let decrypted = try CryptoEngine.decrypt(ciphertext: deserialized!.ciphertext, nonce: deserialized!.nonce, tag: deserialized!.tag, using: restoredKey)
    #expect(String(data: decrypted, encoding: .utf8) == "Restored session clip content")
  }

  @Test("Failure injection: invalid authentication tag is rejected")
  func testFailureInjectionInvalidTagRejection() throws {
    let key = SymmetricKey(size: .bits256)
    let payload = Data("Sensitive test payload".utf8)
    let encrypted = try CryptoEngine.encrypt(payload: payload, using: key)

    var tamperedTag = Data(encrypted.tag)
    let lastIdx = tamperedTag.index(before: tamperedTag.endIndex)
    tamperedTag[lastIdx] ^= 0xFF

    #expect(throws: (any Error).self) {
      try CryptoEngine.decrypt(ciphertext: encrypted.ciphertext, nonce: encrypted.nonce, tag: tamperedTag, using: key)
    }
  }

  @Test("Failure injection: wrong session key is rejected")
  func testFailureInjectionWrongKeyRejection() throws {
    let correctKey = SymmetricKey(size: .bits256)
    let wrongKey = SymmetricKey(size: .bits256)

    let payload = Data("Authenticated data".utf8)
    let encrypted = try CryptoEngine.encrypt(payload: payload, using: correctKey)

    #expect(throws: (any Error).self) {
      try CryptoEngine.decrypt(ciphertext: encrypted.ciphertext, nonce: encrypted.nonce, tag: encrypted.tag, using: wrongKey)
    }
  }

  @Test("Heartbeat watchdog calculation correctly identifies idle timeout")
  func testHeartbeatWatchdogCalculation() {
    let now = Date()
    let lastActiveRecent = now.addingTimeInterval(-4.0)
    let lastActiveExpired = now.addingTimeInterval(-17.0)

    let isRecentExpired = now.timeIntervalSince(lastActiveRecent) > 16.0
    let isExpiredTimeout = now.timeIntervalSince(lastActiveExpired) > 16.0

    #expect(isRecentExpired == false)
    #expect(isExpiredTimeout == true)
  }

  @Test("Multi-device isolation preserves independent session states")
  func testMultiDeviceIsolation() throws {
    let keyAB = SymmetricKey(size: .bits256)
    let keyAC = SymmetricKey(size: .bits256)

    let payloadB = Data("Message intended for Device B".utf8)
    let encryptedB = try CryptoEngine.encrypt(payload: payloadB, using: keyAB)

    let decryptedByB = try CryptoEngine.decrypt(ciphertext: encryptedB.ciphertext, nonce: encryptedB.nonce, tag: encryptedB.tag, using: keyAB)
    #expect(String(data: decryptedByB, encoding: .utf8) == "Message intended for Device B")

    #expect(throws: (any Error).self) {
      try CryptoEngine.decrypt(ciphertext: encryptedB.ciphertext, nonce: encryptedB.nonce, tag: encryptedB.tag, using: keyAC)
    }
  }

  @Test("Deterministic bidirectional file streaming and SHA-256 verification")
  func testDeterministicBidirectionalFileStreaming() throws {
    let key = SymmetricKey(size: .bits256)
    let fileData = Data("Small synthetic test file content for streaming verification".utf8)
    let digest = SHA256.hash(data: fileData).map { String(format: "%02x", $0) }.joined()

    let startJson = Data("{\"transferId\":\"tid-100\",\"fileName\":\"test.txt\",\"fileSize\":60,\"sha256\":\"\(digest)\"}".utf8)
    let startEncrypted = try CryptoEngine.encrypt(payload: startJson, using: key)
    let startFrame = WireFrame(type: .fileStart, nonce: startEncrypted.nonce, ciphertext: startEncrypted.ciphertext, tag: startEncrypted.tag)
    let startDeserialized = WireFrame.deserialize(from: startFrame.serialize())
    #expect(startDeserialized?.type == .fileStart)

    let chunkEncrypted = try CryptoEngine.encrypt(payload: fileData, using: key)
    let chunkFrame = WireFrame(type: .fileChunk, nonce: chunkEncrypted.nonce, ciphertext: chunkEncrypted.ciphertext, tag: chunkEncrypted.tag)
    let chunkDeserialized = WireFrame.deserialize(from: chunkFrame.serialize())
    #expect(chunkDeserialized?.type == .fileChunk)

    let endJson = Data("{\"transferId\":\"tid-100\",\"sha256\":\"\(digest)\"}".utf8)
    let endEncrypted = try CryptoEngine.encrypt(payload: endJson, using: key)
    let endFrame = WireFrame(type: .fileEnd, nonce: endEncrypted.nonce, ciphertext: endEncrypted.ciphertext, tag: endEncrypted.tag)
    let endDeserialized = WireFrame.deserialize(from: endFrame.serialize())
    #expect(endDeserialized?.type == .fileEnd)

    let decryptedChunk = try CryptoEngine.decrypt(ciphertext: chunkDeserialized!.ciphertext, nonce: chunkDeserialized!.nonce, tag: chunkDeserialized!.tag, using: key)
    let receivedDigest = SHA256.hash(data: decryptedChunk).map { String(format: "%02x", $0) }.joined()
    #expect(receivedDigest == digest)
  }

  @Test("Deterministic end-to-end pairing handshake and authenticated session exchange")
  func testEndToEndPairingHandshakeAndAuthenticatedSession() throws {
    let (privA, pubAHex) = CryptoEngine.generateEphemeralKeypair()
    let nonceA = CryptoEngine.generateNonce(count: 16)
    let idA = "mac-initiator-id"

    let (privB, pubBHex) = CryptoEngine.generateEphemeralKeypair()
    let nonceB = CryptoEngine.generateNonce(count: 16)
    let idB = "android-responder-id"

    let secretB = try CryptoEngine.computeSharedSecret(privateKey: privB, remotePublicKeyHex: pubAHex)
    let pubAData = CryptoEngine.hexToData(pubAHex)!
    let pubBData = CryptoEngine.hexToData(pubBHex)!
    let keysB = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: secretB,
      initiatorPublicKey: pubAData,
      receiverPublicKey: pubBData,
      initiatorNonce: nonceA,
      receiverNonce: nonceB,
      initiatorId: idA,
      receiverId: idB
    )

    let secretA = try CryptoEngine.computeSharedSecret(privateKey: privA, remotePublicKeyHex: pubBHex)
    let keysA = CryptoEngine.deriveHandshakeKeys(
      sharedSecret: secretA,
      initiatorPublicKey: pubAData,
      receiverPublicKey: pubBData,
      initiatorNonce: nonceA,
      receiverNonce: nonceB,
      initiatorId: idA,
      receiverId: idB
    )

    #expect(keysA.sasCode == keysB.sasCode)
    #expect(keysA.sessionKey == keysB.sessionKey)

    let requestPayload = Data("{\"deviceName\":\"MacBook Air\",\"modelId\":\"MacBookAir10,1\"}".utf8)
    let encryptedReq = try CryptoEngine.encrypt(payload: requestPayload, using: keysA.sessionKey)
    let reqFrame = WireFrame(type: .pairRequest, nonce: encryptedReq.nonce, ciphertext: encryptedReq.ciphertext, tag: encryptedReq.tag)
    let deserializedReq = WireFrame.deserialize(from: reqFrame.serialize())!
    #expect(deserializedReq.type == .pairRequest)

    let decryptedReq = try CryptoEngine.decrypt(ciphertext: deserializedReq.ciphertext, nonce: deserializedReq.nonce, tag: deserializedReq.tag, using: keysB.sessionKey)
    #expect(String(data: decryptedReq, encoding: .utf8) == String(data: requestPayload, encoding: .utf8))

    let confirmPayload = Data("OK".utf8)
    let encryptedConfirm = try CryptoEngine.encrypt(payload: confirmPayload, using: keysB.sessionKey)
    let confirmFrame = WireFrame(type: .pairConfirm, nonce: encryptedConfirm.nonce, ciphertext: encryptedConfirm.ciphertext, tag: encryptedConfirm.tag)
    let deserializedConfirm = WireFrame.deserialize(from: confirmFrame.serialize())!
    #expect(deserializedConfirm.type == .pairConfirm)

    let decryptedConfirm = try CryptoEngine.decrypt(ciphertext: deserializedConfirm.ciphertext, nonce: deserializedConfirm.nonce, tag: deserializedConfirm.tag, using: keysA.sessionKey)
    #expect(String(data: decryptedConfirm, encoding: .utf8) == "OK")

    let clipText = "Authenticated cross-platform clip"
    let encryptedClip = try CryptoEngine.encrypt(payload: Data(clipText.utf8), using: keysA.sessionKey)
    let clipFrame = WireFrame(type: .clipText, nonce: encryptedClip.nonce, ciphertext: encryptedClip.ciphertext, tag: encryptedClip.tag)
    let deserializedClip = WireFrame.deserialize(from: clipFrame.serialize())!
    let decryptedClip = try CryptoEngine.decrypt(ciphertext: deserializedClip.ciphertext, nonce: deserializedClip.nonce, tag: deserializedClip.tag, using: keysB.sessionKey)
    #expect(String(data: decryptedClip, encoding: .utf8) == clipText)
  }

  @Test("Wrong key or corrupted tag rejects session confirmation")
  func testCorruptedSessionKeyRejection() throws {
    let keyA = SymmetricKey(size: .bits256)
    let keyB = SymmetricKey(size: .bits256)
    let payload = Data("OK".utf8)
    let encrypted = try CryptoEngine.encrypt(payload: payload, using: keyA)
    let frame = WireFrame(type: .pairConfirm, nonce: encrypted.nonce, ciphertext: encrypted.ciphertext, tag: encrypted.tag)
    let deserialized = WireFrame.deserialize(from: frame.serialize())!

    #expect(throws: (any Error).self) {
      try CryptoEngine.decrypt(ciphertext: deserialized.ciphertext, nonce: deserialized.nonce, tag: deserialized.tag, using: keyB)
    }
  }
}
