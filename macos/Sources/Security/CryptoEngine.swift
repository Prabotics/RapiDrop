@preconcurrency import CryptoKit
import Foundation
import Security

public enum CryptoError: Error {
  case encryptionFailed
  case decryptionFailed
  case invalidKeySize
  case invalidNonce
  case invalidTag
  case invalidPublicKey
  case keyAgreementFailed
}

public struct CryptoEngine: Sendable {
  public static func deriveKey(from pin: String) -> SymmetricKey {
    let cleanPin = pin.filter { $0.isNumber }
    let secret = Data("RapiDrop-PIN-\(cleanPin)".utf8)
    let hash = SHA256.hash(data: secret)
    return SymmetricKey(data: hash)
  }

  public static func generateEphemeralKeypair() -> (privateKey: Curve25519.KeyAgreement.PrivateKey, publicKeyHex: String) {
    let priv = Curve25519.KeyAgreement.PrivateKey()
    let pubHex = priv.publicKey.rawRepresentation.map { String(format: "%02x", $0) }.joined()
    return (priv, pubHex)
  }

  public static func generateNonce(count: Int = 16) -> Data {
    var data = Data(count: count)
    let status = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
    guard status == errSecSuccess else {
      fatalError("SecRandomCopyBytes failed: \(status)")
    }
    return data
  }

  public static func hexToData(_ hex: String) -> Data? {
    let clean = hex.trimmingCharacters(in: .whitespacesAndNewlines)
    guard clean.count % 2 == 0 else { return nil }
    var data = Data()
    var index = clean.startIndex
    while index < clean.endIndex {
      let nextIndex = clean.index(index, offsetBy: 2)
      guard let byte = UInt8(clean[index..<nextIndex], radix: 16) else { return nil }
      data.append(byte)
      index = nextIndex
    }
    return data
  }

  public static func computeSharedSecret(
    privateKey: Curve25519.KeyAgreement.PrivateKey,
    remotePublicKeyHex: String
  ) throws -> Data {
    guard let pubData = hexToData(remotePublicKeyHex), pubData.count == 32 else {
      throw CryptoError.invalidPublicKey
    }
    guard let remotePub = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: pubData) else {
      throw CryptoError.invalidPublicKey
    }
    guard let sharedSecret = try? privateKey.sharedSecretFromKeyAgreement(with: remotePub) else {
      throw CryptoError.keyAgreementFailed
    }
    return sharedSecret.withUnsafeBytes { Data($0) }
  }

  public static func deriveHandshakeKeys(
    sharedSecret: Data,
    initiatorPublicKey: Data,
    receiverPublicKey: Data,
    initiatorNonce: Data,
    receiverNonce: Data,
    initiatorId: String,
    receiverId: String
  ) -> (sessionKey: SymmetricKey, authKey: Data, pairRecordKey: Data, sasCode: String, transcriptHash: Data) {
    var transcript = Data("RapiDrop-v1|".utf8)
    transcript.append(initiatorPublicKey)
    transcript.append(Data("|".utf8))
    transcript.append(receiverPublicKey)
    transcript.append(Data("|".utf8))
    transcript.append(initiatorNonce)
    transcript.append(Data("|".utf8))
    transcript.append(receiverNonce)
    transcript.append(Data("|".utf8))
    transcript.append(Data(initiatorId.utf8))
    transcript.append(Data("|".utf8))
    transcript.append(Data(receiverId.utf8))
    let transcriptHash = Data(SHA256.hash(data: transcript))
    var salt = Data(initiatorNonce)
    salt.append(receiverNonce)

    let secretSymmetricKey = SymmetricKey(data: sharedSecret)

    var sessInfo = Data("RapiDrop-v1-Session-Key|".utf8)
    sessInfo.append(transcriptHash)
    let sessionKey = HKDF<SHA256>.deriveKey(
      inputKeyMaterial: secretSymmetricKey,
      salt: salt,
      info: sessInfo,
      outputByteCount: 32
    )

    var authInfo = Data("RapiDrop-v1-Auth-Token|".utf8)
    authInfo.append(transcriptHash)
    let authSymmetricKey = HKDF<SHA256>.deriveKey(
      inputKeyMaterial: secretSymmetricKey,
      salt: salt,
      info: authInfo,
      outputByteCount: 32
    )
    let authKeyData = authSymmetricKey.withUnsafeBytes { Data($0) }

    var pairInfo = Data("RapiDrop-v1-Pair-Record|".utf8)
    pairInfo.append(transcriptHash)
    let pairRecordKey = HKDF<SHA256>.deriveKey(
      inputKeyMaterial: secretSymmetricKey,
      salt: salt,
      info: pairInfo,
      outputByteCount: 32
    )
    let pairRecordKeyData = pairRecordKey.withUnsafeBytes { Data($0) }

    let rawUInt32 = authKeyData.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    let sas = rawUInt32 % 1_000_000
    let sasCode = String(format: "%06d", sas)

    return (
      sessionKey: sessionKey,
      authKey: authKeyData,
      pairRecordKey: pairRecordKeyData,
      sasCode: sasCode,
      transcriptHash: transcriptHash
    )
  }

  public static func encrypt(
    payload: Data,
    using key: SymmetricKey
  ) throws -> (ciphertext: Data, nonce: Data, tag: Data) {
    let nonce = AES.GCM.Nonce()
    guard let sealed = try? AES.GCM.seal(payload, using: key, nonce: nonce) else {
      throw CryptoError.encryptionFailed
    }
    return (
      ciphertext: sealed.ciphertext,
      nonce: Data(sealed.nonce),
      tag: sealed.tag
    )
  }

  public static func decrypt(
    ciphertext: Data,
    nonce: Data,
    tag: Data,
    using key: SymmetricKey
  ) throws -> Data {
    guard let gcmNonce = try? AES.GCM.Nonce(data: nonce) else {
      throw CryptoError.invalidNonce
    }
    guard let sealedBox = try? AES.GCM.SealedBox(nonce: gcmNonce, ciphertext: ciphertext, tag: tag)
    else {
      throw CryptoError.invalidTag
    }
    guard let decrypted = try? AES.GCM.open(sealedBox, using: key) else {
      throw CryptoError.decryptionFailed
    }
    return decrypted
  }

  public static func saveSecureValue(key: String, value: String) {
    guard let data = value.data(using: .utf8) else { return }
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: "com.prabotics.rapidrop",
      kSecAttrAccount as String: key,
    ]
    SecItemDelete(query as CFDictionary)
    var attributes = query
    attributes[kSecValueData as String] = data
    attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
    SecItemAdd(attributes as CFDictionary, nil)
  }

  public static func loadSecureValue(key: String) -> String? {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: "com.prabotics.rapidrop",
      kSecAttrAccount as String: key,
      kSecReturnData as String: true,
      kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var item: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &item)
    if status == errSecSuccess, let data = item as? Data {
      return String(data: data, encoding: .utf8)
    }
    return nil
  }

  public static func deleteSecureValue(key: String) {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: "com.prabotics.rapidrop",
      kSecAttrAccount as String: key,
    ]
    SecItemDelete(query as CFDictionary)
  }
}
