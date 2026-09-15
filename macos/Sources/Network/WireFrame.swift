import Foundation

public enum PacketType: UInt16, Sendable {
  case ping = 0x0001
  case pong = 0x0002
  case pairRequest = 0x0003
  case pairConfirm = 0x0004
  case deviceInfo = 0x0005
  case pairInvite = 0x0006
  case pairFail = 0x0007
  case pairAccept = 0x0008
  case clipText = 0x0010
  case clipUrl = 0x0011
  case clipImage = 0x0012
  case clipFile = 0x0013
  case fileStart = 0x0014
  case fileChunk = 0x0015
  case fileEnd = 0x0016
  case fileCancel = 0x0017
  case configSync = 0x0020
  case disconnect = 0x00FF
}

public struct WireFrame: Sendable, Equatable {
  public static let protocolVersion: UInt16 = 0x0001
  public static let headerSize: Int = 28
  public static let maxPayloadSize: Int = 104_857_600
  public static let authTagSize: Int = 16
  public static let nonceSize: Int = 12
  public static let streamingChunkSize: Int = 1_048_576
  public static let defaultPort: UInt16 = 58240
  public static let defaultClientPort: UInt16 = 58241
  public static let serviceType: String = "_clipsync._tcp"
  public static let clientServiceType: String = "_clipsync-cli._tcp"
  public let type: PacketType
  public let timestamp: UInt64
  public let nonce: Data
  public let ciphertext: Data
  public let tag: Data

  public init(
    type: PacketType, timestamp: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000), nonce: Data,
    ciphertext: Data, tag: Data
  ) {
    self.type = type
    self.timestamp = timestamp
    self.nonce = nonce
    self.ciphertext = ciphertext
    self.tag = tag
  }

  public func serialize() -> Data {
    let payloadLength = UInt32(ciphertext.count + tag.count)
    var data = Data(capacity: Self.headerSize + ciphertext.count + tag.count)

    var lengthBig = payloadLength.bigEndian
    data.append(Data(bytes: &lengthBig, count: 4))

    var typeBig = type.rawValue.bigEndian
    data.append(Data(bytes: &typeBig, count: 2))

    var versionBig = Self.protocolVersion.bigEndian
    data.append(Data(bytes: &versionBig, count: 2))

    var timestampBig = timestamp.bigEndian
    data.append(Data(bytes: &timestampBig, count: 8))

    data.append(nonce.prefix(Self.nonceSize))
    data.append(ciphertext)
    data.append(tag.prefix(Self.authTagSize))

    return data
  }

  public static func deserialize(from data: Data) -> WireFrame? {
    guard data.count >= headerSize + authTagSize else {
      return nil
    }

    let parsedHeader: (UInt32, PacketType, UInt16, UInt64)? = data.withUnsafeBytes { ptr in
      let len = ptr.loadUnaligned(fromByteOffset: 0, as: UInt32.self).bigEndian
      let typeRaw = ptr.loadUnaligned(fromByteOffset: 4, as: UInt16.self).bigEndian
      let ver = ptr.loadUnaligned(fromByteOffset: 6, as: UInt16.self).bigEndian
      let time = ptr.loadUnaligned(fromByteOffset: 8, as: UInt64.self).bigEndian
      guard let type = PacketType(rawValue: typeRaw) else { return nil }
      return (len, type, ver, time)
    }

    guard let (payloadLength, packetType, version, timestamp) = parsedHeader,
      version == protocolVersion,
      Int(payloadLength) <= maxPayloadSize,
      data.count == headerSize + Int(payloadLength)
    else {
      return nil
    }
    let nonce = data.subdata(in: 16..<28)

    let ciphertextEnd = data.count - authTagSize
    guard ciphertextEnd >= headerSize else {
      return nil
    }

    let ciphertext = data.subdata(in: headerSize..<ciphertextEnd)
    let tag = data.subdata(in: ciphertextEnd..<data.count)

    return WireFrame(
      type: packetType,
      timestamp: timestamp,
      nonce: nonce,
      ciphertext: ciphertext,
      tag: tag
    )
  }
}
