@preconcurrency import CryptoKit
extension SymmetricKey: @unchecked Sendable {}
import Foundation
import Network

public struct DeviceDescriptor: Codable, Sendable {
  public let modelId: String
  public let modelName: String
  public let chip: String
  public let deviceName: String

  public init(modelId: String, modelName: String, chip: String, deviceName: String) {
    self.modelId = modelId
    self.modelName = modelName
    self.chip = chip
    self.deviceName = deviceName
  }

  public static func current() -> DeviceDescriptor {
    var size: Int = 0
    sysctlbyname("hw.model", nil, &size, nil, 0)
    var model = [CChar](repeating: 0, count: max(1, size))
    if size > 0 {
      sysctlbyname("hw.model", &model, &size, nil, 0)
    }
    let modelId =
      model.withUnsafeBufferPointer { ptr in ptr.baseAddress.map { String(cString: $0) } }
      ?? "Mac15,12"

    var chipSize: Int = 0
    sysctlbyname("machdep.cpu.brand_string", nil, &chipSize, nil, 0)
    var chipName = [CChar](repeating: 0, count: max(1, chipSize))
    if chipSize > 0 {
      sysctlbyname("machdep.cpu.brand_string", &chipName, &chipSize, nil, 0)
    }
    let chip =
      chipName.withUnsafeBufferPointer { ptr in ptr.baseAddress.map { String(cString: $0) } }
      ?? "Apple M3"

    let modelName: String = {
      if modelId.contains("MacBookAir") { return "MacBook Air" }
      if modelId.contains("MacBookPro") { return "MacBook Pro" }
      if modelId.contains("Macmini") { return "Mac mini" }
      if modelId.contains("MacStudio") || modelId.contains("Mac13") { return "Mac Studio" }
      if modelId.contains("iMac") { return "iMac" }
      return "Mac"
    }()

    let deviceName = Host.current().localizedName ?? "MacBook"
    return DeviceDescriptor(
      modelId: modelId, modelName: modelName, chip: chip, deviceName: deviceName)
  }
}
public struct DiscoveredClientDevice: Identifiable, Sendable, Equatable {
  public let id: String
  public let rawId: String
  public let name: String
  public let endpoint: NWEndpoint?
  public let host: String?
  public let port: Int?

  public init(id: String = "", name: String, endpoint: NWEndpoint? = nil, host: String? = nil, port: Int? = nil) {
    self.rawId = id
    self.id = id.isEmpty ? name : id
    self.name = name
    self.endpoint = endpoint
    self.host = host
    self.port = port
  }
}
public enum TransferFailureReason: String, Sendable {
  case userCancelled = "Transfer cancelled"
  case integrityMismatch = "Integrity verification failed"
  case connectionLost = "Transfer interrupted"
}

public protocol NetworkEngineDelegate: AnyObject, Sendable {
  func networkEngine(_ engine: NetworkEngine, didReceiveClip item: ClipItem)
  func networkEngine(
    _ engine: NetworkEngine, didUpdateConnectionState isConnected: Bool, peerName: String?)
  func networkEngine(
    _ engine: NetworkEngine, didReceivePairInviteFrom device: DeviceDescriptor, pin: String?,
    host: String?, port: Int)
  func networkEngineDidFailPairing(_ engine: NetworkEngine, reason: String)
  func networkEngineDidReceiveDisconnect(_ engine: NetworkEngine)
  func networkEngine(_ engine: NetworkEngine, didReceivePrivacyModeUpdate privacyMode: Bool)
  func networkEngine(
    _ engine: NetworkEngine, didReceivePairOfferFrom fromName: String, sasCode: String)
  func networkEngine(
    _ engine: NetworkEngine, didReceivePairAcceptFrom fromName: String, sasCode: String, host: String?, port: Int)
  func networkEngine(
    _ engine: NetworkEngine, didUpdateTransferProgress transferId: String, fileName: String,
    bytesTransferred: Int64, totalBytes: Int64, fileIndex: Int, totalFiles: Int, isComplete: Bool)
  func networkEngineDidCancelTransfer(_ engine: NetworkEngine, transferId: String, reason: TransferFailureReason)
}
extension NetworkEngineDelegate {
  public func networkEngine(
    _ engine: NetworkEngine, didReceivePairInviteFrom device: DeviceDescriptor, pin: String?,
    host: String?, port: Int
  ) {}
  public func networkEngineDidFailPairing(_ engine: NetworkEngine, reason: String) {}
  public func networkEngineDidReceiveDisconnect(_ engine: NetworkEngine) {}
  public func networkEngine(_ engine: NetworkEngine, didReceivePrivacyModeUpdate privacyMode: Bool)
  {}
  public func networkEngine(
    _ engine: NetworkEngine, didReceivePairOfferFrom fromName: String, sasCode: String
  ) {}
  public func networkEngine(
    _ engine: NetworkEngine, didReceivePairAcceptFrom fromName: String, sasCode: String, host: String?, port: Int
  ) {}
  public func networkEngine(
    _ engine: NetworkEngine, didUpdateTransferProgress transferId: String, fileName: String,
    bytesTransferred: Int64, totalBytes: Int64, fileIndex: Int, totalFiles: Int, isComplete: Bool
  ) {}
  public func networkEngineDidCancelTransfer(_ engine: NetworkEngine, transferId: String, reason: TransferFailureReason) {}
}

public final class NetworkEngine: @unchecked Sendable {
  public static let serviceType = WireFrame.serviceType
  public static let clientServiceType = WireFrame.clientServiceType
  public static let servicePort: UInt16 = WireFrame.defaultPort
  public static var localDeviceId: String {
    if let id = UserDefaults.standard.string(forKey: "rapidrop_device_id"), !id.isEmpty {
      return id
    }
    let newId = UUID().uuidString
    UserDefaults.standard.set(newId, forKey: "rapidrop_device_id")
    return newId
  }
  private var listener: NWListener?
  private var clientBrowser: NWBrowser?
  private var serverBrowser: NWBrowser?
  private var clientDiscoveredDevices: [DiscoveredClientDevice] = []
  private var serverDiscoveredDevices: [DiscoveredClientDevice] = []
  private var activeConnections: [NWConnection] = []
  private var connectionGeneration: UInt64 = 0
  private var currentOutgoingConnection: NWConnection?
  private var pendingPairingConnection: NWConnection?
  private var pendingInitiatorKeypair: (privateKey: Curve25519.KeyAgreement.PrivateKey, publicKeyHex: String, nonceHex: String)?
  private var pendingReceiverState: (sessionKey: SymmetricKey, sasCode: String, host: String?, port: Int, fromName: String)?
  private var pendingReceiverAcceptPayload: [String: Any]?
  private var pendingHandshakeConnections: [NWConnection] = []
  private let queue = DispatchQueue(label: "com.prabotics.rapidrop.networkengine", qos: .userInitiated)
  private let ioQueue = DispatchQueue(label: "com.prabotics.rapidrop.io", qos: .userInitiated)
  private let pathMonitor = NWPathMonitor()
  private var lastPathStatus: NWPath.Status?
  private var lastInterfaceNames: Set<String> = []
  private var sessionKey: SymmetricKey?
  private var lastActivityTimestamp = Date()
  private var watchdogTimer: DispatchSourceTimer?
  private var isConnectionNotified = false
  public var onDiscoveredDevicesChanged: (@Sendable ([DiscoveredClientDevice]) -> Void)?
  public weak var delegate: NetworkEngineDelegate?
  public var downloadFolderURL: URL =
    FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
  public func uniqueDestinationURL(for url: URL) -> URL {
    var candidate = url
    let ext = url.pathExtension
    let baseName = url.deletingPathExtension().lastPathComponent
    let parent = url.deletingLastPathComponent()
    var counter = 1
    while FileManager.default.fileExists(atPath: candidate.path) {
      let newName = ext.isEmpty ? "\(baseName) (\(counter))" : "\(baseName) (\(counter)).\(ext)"
      candidate = parent.appendingPathComponent(newName)
      counter += 1
    }
    return candidate
  }
  public static func sanitizeRelativePath(_ path: String, fallbackFileName: String = "file") -> String {
    let cleanFallback = (fallbackFileName.replacingOccurrences(of: "\\", with: "/") as NSString).lastPathComponent.trimmingCharacters(in: .whitespacesAndNewlines)
    let fallback = cleanFallback.isEmpty ? "file" : cleanFallback
    let normalized = path.replacingOccurrences(of: "\\", with: "/")
    let components = normalized.split(separator: "/").map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
    let safeComponents = components.filter { segment in
      !segment.isEmpty && segment != "." && segment != ".." && !segment.contains(":") && !segment.contains("\0")
    }
    if safeComponents.isEmpty {
      return fallback
    }
    return safeComponents.joined(separator: "/")
  }

  private struct IncomingStreamTransfer {
    let transferId: String
    let fileIndex: Int
    let totalFiles: Int
    let fileName: String
    let relativePath: String
    let fileSize: Int64
    let totalBytes: Int64
    var bytesReceived: Int64
    var nextChunkIndex: UInt64
    let fileHandle: FileHandle
    let targetURL: URL
    let partURL: URL
    var hasher: SHA256
  }
  private var activeIncomingTransfer: IncomingStreamTransfer?

  public init() {}

  public func setSessionKey(_ key: SymmetricKey?) {
    queue.async { [weak self] in
      self?.sessionKey = key
    }
  }

  public func getSessionKeyData() -> Data? {
    queue.sync {
      self.sessionKey?.withUnsafeBytes { Data($0) }
    }
  }

  public func start() {
    queue.async { [weak self] in
      guard let self else { return }
      self.startPathMonitor()
      self.setupListener()
      self.restartBrowser()
      self.startWatchdogTimer()
    }
  }

  private func startWatchdogTimer() {
    watchdogTimer?.cancel()
    let timer = DispatchSource.makeTimerSource(queue: queue)
    timer.schedule(deadline: .now() + 5, repeating: 5)
    timer.setEventHandler { [weak self] in
      guard let self else { return }
      if !self.activeConnections.isEmpty {
        let elapsed = Date().timeIntervalSince(self.lastActivityTimestamp)
        if elapsed > 16.0 {
          for conn in self.activeConnections {
            conn.cancel()
          }
          self.activeConnections.removeAll()
          self.notifyConnectionState(isConnected: false, peerName: nil)
        } else if let key = self.sessionKey {
          if let encrypted = try? CryptoEngine.encrypt(payload: Data("PING".utf8), using: key) {
            let pingFrame = WireFrame(
              type: .ping, nonce: encrypted.nonce, ciphertext: encrypted.ciphertext,
              tag: encrypted.tag)
            let data = pingFrame.serialize()
            for conn in self.activeConnections {
              conn.send(content: data, completion: .contentProcessed { _ in })
            }
          }
        }
      }
    }
    timer.resume()
    self.watchdogTimer = timer
  }

  private func startPathMonitor() {
    pathMonitor.pathUpdateHandler = { [weak self] path in
      guard let self else { return }
      self.queue.async {
        let currentInterfaces = Set(path.availableInterfaces.map { $0.name })
        let interfaceChanged = self.lastPathStatus != nil && (self.lastInterfaceNames != currentInterfaces)
        self.lastPathStatus = path.status
        self.lastInterfaceNames = currentInterfaces

        if path.status == .satisfied {
          if self.listener == nil || interfaceChanged {
            self.setupListener()
          }
          if self.clientBrowser == nil || self.serverBrowser == nil || interfaceChanged {
            self.restartBrowser()
          }
        } else {
          for conn in self.activeConnections {
            conn.cancel()
          }
          self.activeConnections.removeAll()
          self.pendingPairingConnection?.cancel()
          self.pendingPairingConnection = nil
          self.notifyConnectionState(isConnected: false, peerName: nil)
        }
      }
    }
    pathMonitor.start(queue: queue)
  }

  private static func createTcpParameters() -> NWParameters {
    let tcpOptions = NWProtocolTCP.Options()
    tcpOptions.noDelay = true
    tcpOptions.enableKeepalive = true
    let params = NWParameters(tls: nil, tcp: tcpOptions)
    return params
  }
  private func setupListener() {
    self.listener?.cancel()
    self.listener = nil

    do {
      let params = Self.createTcpParameters()
      let port = NWEndpoint.Port(rawValue: Self.servicePort) ?? .any
      let listener = try NWListener(using: params, on: port)

      let desc = DeviceDescriptor.current()
      var txtRecord = NWTXTRecord()
      txtRecord["id"] = Self.localDeviceId
      txtRecord["device"] = "mac"
      txtRecord["version"] = "1"
      if let ip = self.getLocalIPv4() {
        txtRecord["ip"] = ip
      }
      txtRecord["port"] = "\(Self.servicePort)"
      listener.service = NWListener.Service(
        name: desc.deviceName,
        type: Self.serviceType,
        txtRecord: txtRecord
      )

      listener.stateUpdateHandler = { [weak self] state in
        guard let self else { return }
        switch state {
        case .ready:
          break
        case .failed:
          self.queue.asyncAfter(deadline: .now() + 3) {
            self.setupListener()
          }
        case .cancelled:
          break
        default:
          break
        }
      }

      listener.newConnectionHandler = { [weak self] connection in
        self?.handleNewConnection(connection)
      }
      listener.start(queue: self.queue)
      self.listener = listener
    } catch {
      self.queue.asyncAfter(deadline: .now() + 3) { [weak self] in
        self?.setupListener()
      }
    }
  }

  public func restartBrowser() {
    queue.async { [weak self] in
      guard let self else { return }
      self.clientBrowser?.cancel()
      self.clientBrowser = nil
      self.serverBrowser?.cancel()
      self.serverBrowser = nil
      self.clientDiscoveredDevices.removeAll()
      self.serverDiscoveredDevices.removeAll()
      self.onDiscoveredDevicesChanged?([])

      let browserParams = NWParameters()
      browserParams.includePeerToPeer = true
      let cBrowser = NWBrowser(
        for: .bonjour(type: Self.clientServiceType, domain: nil), using: browserParams)
      cBrowser.stateUpdateHandler = { [weak self] state in
        guard let self else { return }
        if case .failed = state {
          self.queue.asyncAfter(deadline: .now() + 3) {
            self.restartBrowser()
          }
        }
      }
      cBrowser.browseResultsChangedHandler = { [weak self] results, _ in
        guard let self else { return }
        self.queue.async {
          self.clientDiscoveredDevices = results.compactMap { res in
            if case .service(let name, _, _, _) = res.endpoint {
              var remoteId = ""
              var host: String? = nil
              var port: Int? = nil
              if case .bonjour(let txt) = res.metadata {
                remoteId = txt["id"] ?? ""
                if let ip = txt["ip"], !ip.isEmpty {
                  host = ip
                }
                if let portStr = txt["port"], let p = Int(portStr), p > 0 {
                  port = p
                }
              }
              return DiscoveredClientDevice(id: remoteId, name: name, endpoint: res.endpoint, host: host, port: port)
            }
            return nil
          }
          self.notifyMergedDiscoveredDevices()
        }
      }
      cBrowser.start(queue: self.queue)
      self.clientBrowser = cBrowser

      let sBrowser = NWBrowser(
        for: .bonjour(type: Self.serviceType, domain: nil), using: browserParams)
      sBrowser.stateUpdateHandler = { [weak self] state in
        guard let self else { return }
        if case .failed = state {
          self.queue.asyncAfter(deadline: .now() + 3) {
            self.restartBrowser()
          }
        }
      }
      sBrowser.browseResultsChangedHandler = { [weak self] results, _ in
        guard let self else { return }
        self.queue.async {
          self.serverDiscoveredDevices = results.compactMap { res in
            if case .service(let name, _, _, _) = res.endpoint {
              var remoteId = ""
              var host: String? = nil
              var port: Int? = nil
              if case .bonjour(let txt) = res.metadata {
                remoteId = txt["id"] ?? ""
                if let ip = txt["ip"], !ip.isEmpty {
                  host = ip
                }
                if let portStr = txt["port"], let p = Int(portStr), p > 0 {
                  port = p
                }
              }
              return DiscoveredClientDevice(id: remoteId, name: name, endpoint: res.endpoint, host: host, port: port)
            }
            return nil
          }
          self.notifyMergedDiscoveredDevices()
        }
      }
      sBrowser.start(queue: self.queue)
      self.serverBrowser = sBrowser
    }
  }

  public static func normalizeDeviceName(_ name: String?) -> String {
    guard let name = name, !name.isEmpty else { return "" }
    var n = name.replacingOccurrences(of: "’", with: "'")
      .replacingOccurrences(of: "‘", with: "'")
      .replacingOccurrences(of: "\"", with: "")
      .replacingOccurrences(of: "\\", with: "")
      .trimmingCharacters(in: .whitespacesAndNewlines)
    if let range = n.range(of: #"\s*\(\d+\)$"#, options: .regularExpression) {
      n.removeSubrange(range)
    }
    if let range = n.range(of: #"\s*-\s*\d+$"#, options: .regularExpression) {
      n.removeSubrange(range)
    }
    return n.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
  }

  private func notifyMergedDiscoveredDevices() {
    let rawLocalName = DeviceDescriptor.current().deviceName
    let localNormalized = Self.normalizeDeviceName(rawLocalName)
    let localId = Self.localDeviceId

    var devicesById: [String: DiscoveredClientDevice] = [:]
    var devicesByNameWithoutId: [String: DiscoveredClientDevice] = [:]

    for dev in (clientDiscoveredDevices + serverDiscoveredDevices) {
      let isSelf: Bool
      if !dev.rawId.isEmpty && !localId.isEmpty {
        isSelf = (dev.rawId == localId)
      } else {
        isSelf = !localNormalized.isEmpty && (Self.normalizeDeviceName(dev.name) == localNormalized)
      }
      if isSelf { continue }

      if !dev.rawId.isEmpty {
        if let existing = devicesById[dev.rawId] {
          let mergedDev = DiscoveredClientDevice(
            id: dev.rawId,
            name: dev.name,
            endpoint: dev.endpoint ?? existing.endpoint,
            host: dev.host ?? existing.host,
            port: dev.port ?? existing.port
          )
          devicesById[dev.rawId] = mergedDev
        } else {
          devicesById[dev.rawId] = dev
        }
      } else {
        let nameKey = Self.normalizeDeviceName(dev.name)
        if let existing = devicesByNameWithoutId[nameKey] {
          let mergedDev = DiscoveredClientDevice(
            id: dev.id,
            name: dev.name,
            endpoint: dev.endpoint ?? existing.endpoint,
            host: dev.host ?? existing.host,
            port: dev.port ?? existing.port
          )
          devicesByNameWithoutId[nameKey] = mergedDev
        } else {
          devicesByNameWithoutId[nameKey] = dev
        }
      }
    }

    var merged: [DiscoveredClientDevice] = Array(devicesById.values)
    let seenNamesWithId = Set(merged.map { Self.normalizeDeviceName($0.name) })

    for (nameKey, dev) in devicesByNameWithoutId {
      if !seenNamesWithId.contains(nameKey) {
        merged.append(dev)
      }
    }
    self.onDiscoveredDevicesChanged?(merged)
  }
  public func stop() {
    queue.async { [weak self] in
      guard let self else { return }
      if let transfer = self.activeIncomingTransfer {
        try? transfer.fileHandle.close()
        try? FileManager.default.removeItem(at: transfer.partURL)
        self.activeIncomingTransfer = nil
      }
      for connection in self.activeConnections {
        connection.cancel()
      }
      self.activeConnections.removeAll()
      self.listener?.cancel()
      self.listener = nil
      self.clientBrowser?.cancel()
      self.clientBrowser = nil
      self.serverBrowser?.cancel()
      self.serverBrowser = nil
      self.pathMonitor.cancel()
      self.notifyConnectionState(isConnected: false, peerName: nil)
    }
  }

  private func handleNewConnection(_ connection: NWConnection) {
    connection.stateUpdateHandler = { [weak self, weak connection] state in
      guard let self, let connection else { return }
      switch state {
      case .ready:
        self.lastActivityTimestamp = Date()
        self.receiveFrame(from: connection)
      case .failed, .cancelled:
        if self.activeConnections.contains(where: { $0 === connection }) {
          self.activeConnections.removeAll { $0 === connection }
          if self.activeConnections.isEmpty {
            if let transfer = self.activeIncomingTransfer {
              let tid = transfer.transferId
              try? transfer.fileHandle.close()
              try? FileManager.default.removeItem(at: transfer.partURL)
              self.activeIncomingTransfer = nil
              self.delegate?.networkEngineDidCancelTransfer(self, transferId: tid, reason: .connectionLost)
            }
            self.notifyConnectionState(isConnected: false, peerName: nil)
          }
        }
      default:
        break
      }
    }
    connection.start(queue: queue)
  }

  private func sendDeviceInfo(to connection: NWConnection) {
    guard let key = sessionKey else { return }
    let descriptor = DeviceDescriptor.current()
    guard let jsonData = try? JSONEncoder().encode(descriptor) else { return }
    guard let encrypted = try? CryptoEngine.encrypt(payload: jsonData, using: key) else { return }

    let frame = WireFrame(
      type: .deviceInfo,
      nonce: encrypted.nonce,
      ciphertext: encrypted.ciphertext,
      tag: encrypted.tag
    )
    let data = frame.serialize()
    connection.send(content: data, completion: .contentProcessed { _ in })
  }

  private func sendPairConfirm(to connection: NWConnection) {
    guard let key = sessionKey else { return }
    let payload = Data("OK".utf8)
    guard let encrypted = try? CryptoEngine.encrypt(payload: payload, using: key) else { return }
    let frame = WireFrame(
      type: .pairConfirm,
      nonce: encrypted.nonce,
      ciphertext: encrypted.ciphertext,
      tag: encrypted.tag
    )
    let data = frame.serialize()
    connection.send(content: data, completion: .contentProcessed { _ in })
  }

  private func sendPairFail(to connection: NWConnection, reason: String) {
    let payload = ["version": 1, "reason": reason] as [String: Any]
    guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else {
      connection.cancel()
      return
    }
    let frame = WireFrame(
      type: .pairFail,
      nonce: Data(repeating: 0, count: 12),
      ciphertext: jsonData,
      tag: Data(repeating: 0, count: 16)
    )
    let data = frame.serialize()
    connection.send(
      content: data,
      completion: .contentProcessed { _ in
        self.queue.asyncAfter(deadline: .now() + 0.1) {
          connection.cancel()
        }
      })
  }

  private func readExactBytes(
    count: Int, from connection: NWConnection, accumulated: Data = Data(),
    completion: @escaping @Sendable (Data?) -> Void
  ) {
    let needed = count - accumulated.count
    guard needed > 0 else {
      completion(accumulated)
      return
    }
    let minLength = min(needed, 131072)
    connection.receive(minimumIncompleteLength: minLength, maximumLength: needed) {
      [weak self, weak connection] data, _, isComplete, error in
      guard let self, let connection, error == nil, let data, !data.isEmpty else {
        completion(nil)
        return
      }
      var nextAccumulated = accumulated
      if nextAccumulated.isEmpty {
        nextAccumulated.reserveCapacity(count)
      }
      nextAccumulated.append(data)
      if nextAccumulated.count == count {
        completion(nextAccumulated)
      } else if nextAccumulated.count < count {
        if isComplete {
          completion(nil)
        } else {
          self.readExactBytes(
            count: count, from: connection, accumulated: nextAccumulated, completion: completion)
        }
      } else {
        completion(nil)
      }
    }
  }

  private func receiveFrame(from connection: NWConnection) {
    readExactBytes(count: 4, from: connection) { [weak self, weak connection] headerData in
      guard let self, let connection, let headerData else {
        if let self, let transfer = self.activeIncomingTransfer {
          let tid = transfer.transferId
          let handle = transfer.fileHandle
          let part = transfer.partURL
          self.ioQueue.async {
            try? handle.close()
            try? FileManager.default.removeItem(at: part)
          }
          self.activeIncomingTransfer = nil
          self.delegate?.networkEngineDidCancelTransfer(self, transferId: tid, reason: .connectionLost)
        }
        connection?.cancel()
        return
      }

      let payloadLength = headerData.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
      guard
        Int(payloadLength) >= WireFrame.authTagSize
          && Int(payloadLength) <= WireFrame.maxPayloadSize
      else {
        connection.cancel()
        return
      }

      let remainingBytes = (WireFrame.headerSize - 4) + Int(payloadLength)
      self.readExactBytes(count: remainingBytes, from: connection) {
        [weak self, weak connection] bodyData in
        guard let self, let connection, let bodyData else {
          if let self, let transfer = self.activeIncomingTransfer {
            let tid = transfer.transferId
            try? transfer.fileHandle.close()
            try? FileManager.default.removeItem(at: transfer.partURL)
            self.activeIncomingTransfer = nil
            self.delegate?.networkEngineDidCancelTransfer(self, transferId: tid, reason: .connectionLost)
          }
          connection?.cancel()
          return
        }
        var fullFrameData = Data(capacity: headerData.count + bodyData.count)
        fullFrameData.append(headerData)
        fullFrameData.append(bodyData)

        if let frame = WireFrame.deserialize(from: fullFrameData) {
          self.processFrame(frame, from: connection)
        }

        self.receiveFrame(from: connection)
      }
    }
  }

  private func processFrame(_ frame: WireFrame, from connection: NWConnection) {
    if frame.type == .pairInvite {
      guard let json = try? JSONSerialization.jsonObject(with: frame.ciphertext) as? [String: Any],
        let remotePubHex = json["publicKey"] as? String,
        let remoteNonceHex = json["nonce"] as? String,
        let remotePubData = CryptoEngine.hexToData(remotePubHex),
        let remoteNonceData = CryptoEngine.hexToData(remoteNonceHex),
        remotePubData.count == 32, remoteNonceData.count == 16 else {
        return
      }
      let fromName = json["fromDeviceName"] as? String ?? "Unknown Device"
      let initiatorId = json["id"] as? String ?? ""
      let receiverId = Self.localDeviceId

      if self.pendingInitiatorKeypair != nil {
        if !initiatorId.isEmpty && !receiverId.isEmpty && receiverId < initiatorId {
          return
        } else {
          self.pendingInitiatorKeypair = nil
        }
      }

      let model = json["model"] as? String ?? "Device"
      let chip = json["chip"] as? String ?? ""
      var host = json["host"] as? String
      if host == nil || host?.isEmpty == true {
        if case .hostPort(let remoteHost, _) = connection.endpoint {
          switch remoteHost {
          case .ipv4(let ip): host = "\(ip)"
          case .ipv6(let ip): host = "\(ip)"
          default: break
          }
        }
      }
      let port = json["port"] as? Int ?? Int(WireFrame.defaultClientPort)
      let (privB, pubBHex) = CryptoEngine.generateEphemeralKeypair()
      let nonceBData = CryptoEngine.generateNonce(count: 16)
      let nonceBHex = nonceBData.map { String(format: "%02x", $0) }.joined()
      guard let sharedSecret = try? CryptoEngine.computeSharedSecret(privateKey: privB, remotePublicKeyHex: remotePubHex),
            let pubBData = CryptoEngine.hexToData(pubBHex) else {
        return
      }
      let desc = DeviceDescriptor.current()
      let keys = CryptoEngine.deriveHandshakeKeys(
        sharedSecret: sharedSecret,
        initiatorPublicKey: remotePubData,
        receiverPublicKey: pubBData,
        initiatorNonce: remoteNonceData,
        receiverNonce: nonceBData,
        initiatorId: initiatorId,
        receiverId: receiverId
      )
      self.pendingReceiverState = (
        sessionKey: keys.sessionKey,
        sasCode: keys.sasCode,
        host: host,
        port: port,
        fromName: fromName
      )
      self.pendingReceiverAcceptPayload = [
        "version": 1,
        "id": Self.localDeviceId,
        "status": "accepted",
        "fromDeviceName": desc.deviceName,
        "deviceType": "mac",
        "model": desc.modelId,
        "chip": desc.chip,
        "publicKey": pubBHex,
        "nonce": nonceBHex,
        "host": self.getLocalIPv4() ?? "",
        "port": Int(Self.servicePort),
      ]
      if let host = host, !host.isEmpty {
        let offerPayload: [String: Any] = [
          "version": 1,
          "id": Self.localDeviceId,
          "status": "offered",
          "fromDeviceName": desc.deviceName,
          "deviceType": "mac",
          "model": desc.modelId,
          "chip": desc.chip,
          "publicKey": pubBHex,
          "nonce": nonceBHex,
          "host": self.getLocalIPv4() ?? "",
          "port": Int(Self.servicePort),
        ]
        self.sendPairOffer(to: host, port: UInt16(port), payload: offerPayload)
      }
      let descriptor = DeviceDescriptor(
        modelId: model, modelName: fromName, chip: chip, deviceName: fromName)
      self.delegate?.networkEngine(
        self, didReceivePairInviteFrom: descriptor, pin: keys.sasCode, host: host, port: port)
      return
    }

    if frame.type == .pairFail {
      var reason = "DECLINED"
      if let json = try? JSONSerialization.jsonObject(with: frame.ciphertext) as? [String: Any],
         let r = json["reason"] as? String {
        reason = r
      } else if let raw = String(data: frame.ciphertext, encoding: .utf8) {
        reason = raw
      }
      self.pendingInitiatorKeypair = nil
      self.pendingReceiverState = nil
      delegate?.networkEngineDidFailPairing(self, reason: reason)
      connection.cancel()
      return
    }
    if frame.type == .pairAccept {
      guard let json = try? JSONSerialization.jsonObject(with: frame.ciphertext) as? [String: Any],
        let remotePubHex = json["publicKey"] as? String,
        let remoteNonceHex = json["nonce"] as? String,
        let remotePubData = CryptoEngine.hexToData(remotePubHex),
        let remoteNonceData = CryptoEngine.hexToData(remoteNonceHex),
        let initiatorState = self.pendingInitiatorKeypair,
        let initPubData = CryptoEngine.hexToData(initiatorState.publicKeyHex),
        let initNonceData = CryptoEngine.hexToData(initiatorState.nonceHex),
        let sharedSecret = try? CryptoEngine.computeSharedSecret(privateKey: initiatorState.privateKey, remotePublicKeyHex: remotePubHex) else {
        return
      }
      let fromName = json["fromDeviceName"] as? String ?? "Device"
      let initiatorId = Self.localDeviceId
      let receiverId = json["id"] as? String ?? ""
      let keys = CryptoEngine.deriveHandshakeKeys(
        sharedSecret: sharedSecret,
        initiatorPublicKey: initPubData,
        receiverPublicKey: remotePubData,
        initiatorNonce: initNonceData,
        receiverNonce: remoteNonceData,
        initiatorId: initiatorId,
        receiverId: receiverId
      )
      self.sessionKey = keys.sessionKey
      let status = json["status"] as? String ?? "accepted"

      var host = json["host"] as? String
      if host == nil || host?.isEmpty == true {
        if case .hostPort(let remoteHost, _) = connection.endpoint {
          switch remoteHost {
          case .ipv4(let ip): host = "\(ip)"
          case .ipv6(let ip): host = "\(ip)"
          default: break
          }
        }
      }
      let port = json["port"] as? Int ?? Int(WireFrame.defaultPort)

      if status == "offered" {
        self.sessionKey = keys.sessionKey
        delegate?.networkEngine(self, didReceivePairOfferFrom: fromName, sasCode: keys.sasCode)
      } else {
        self.pendingInitiatorKeypair = nil
        self.sessionKey = keys.sessionKey
        delegate?.networkEngine(self, didReceivePairAcceptFrom: fromName, sasCode: keys.sasCode, host: host, port: port)
      }
      return
    }

    if frame.type == .disconnect {
      delegate?.networkEngineDidReceiveDisconnect(self)
      connection.cancel()
      return
    }

    guard let key = sessionKey else {
      connection.cancel()
      return
    }

    guard let decryptedData = try? CryptoEngine.decrypt(
      ciphertext: frame.ciphertext,
      nonce: frame.nonce,
      tag: frame.tag,
      using: key
    ) else {
      if frame.type == .pairRequest {
        sendPairFail(to: connection, reason: "Pairing failed")
      } else {
        connection.cancel()
      }
      return
    }
    self.lastActivityTimestamp = Date()
    let clipItem: ClipItem?
    switch frame.type {
    case .ping:
      if let encrypted = try? CryptoEngine.encrypt(payload: Data("PONG".utf8), using: key) {
        let pongFrame = WireFrame(
          type: .pong, nonce: encrypted.nonce, ciphertext: encrypted.ciphertext, tag: encrypted.tag)
        connection.send(content: pongFrame.serialize(), completion: .contentProcessed { _ in })
      }
      clipItem = nil
    case .pong:
      clipItem = nil
    case .pairRequest:
      self.pendingInitiatorKeypair = nil
      for oldConn in self.activeConnections {
        if oldConn !== connection {
          oldConn.cancel()
        }
      }
      if !self.activeConnections.contains(where: { $0 === connection }) {
        self.activeConnections = [connection]
      }
      var peerName = "Device"
      if let jsonString = String(data: decryptedData, encoding: .utf8),
        let jsonData = jsonString.data(using: .utf8),
        let descriptor = try? JSONDecoder().decode(DeviceDescriptor.self, from: jsonData)
      {
        peerName = descriptor.modelName
      }
      notifyConnectionState(isConnected: true, peerName: peerName)
      sendPairConfirm(to: connection)
      sendDeviceInfo(to: connection)
      clipItem = nil
    case .pairConfirm:
      self.pendingInitiatorKeypair = nil
      for oldConn in self.activeConnections {
        if oldConn !== connection {
          oldConn.cancel()
        }
      }
      if !self.activeConnections.contains(where: { $0 === connection }) {
        self.activeConnections = [connection]
      }
      notifyConnectionState(isConnected: true, peerName: nil)
      sendDeviceInfo(to: connection)
      clipItem = nil
    case .deviceInfo:
      if !self.activeConnections.contains(where: { $0 === connection }) {
        self.activeConnections.append(connection)
      }
      if let jsonString = String(data: decryptedData, encoding: .utf8),
        let jsonData = jsonString.data(using: .utf8),
        let descriptor = try? JSONDecoder().decode(DeviceDescriptor.self, from: jsonData)
      {
        notifyConnectionState(isConnected: true, peerName: descriptor.modelName)
      }
      clipItem = nil
    case .clipText:
      if let text = String(data: decryptedData, encoding: .utf8) {
        clipItem = ClipItem(type: .text, textContent: text)
      } else {
        clipItem = nil
      }
    case .clipUrl:
      if let urlString = String(data: decryptedData, encoding: .utf8) {
        clipItem = ClipItem(type: .url, textContent: urlString)
      } else {
        clipItem = nil
      }
    case .clipImage:
      clipItem = ClipItem(type: .image, rawData: decryptedData)
    case .clipFile:
      if decryptedData.count >= 2 {
        let nameLen = Int(
          decryptedData.prefix(2).withUnsafeBytes { $0.load(as: UInt16.self).bigEndian })
        if decryptedData.count >= 2 + nameLen {
          let nameData = decryptedData.subdata(in: 2..<(2 + nameLen))
          let fileName = String(data: nameData, encoding: .utf8) ?? "File"
          let fileData = decryptedData.subdata(in: (2 + nameLen)..<decryptedData.count)
          clipItem = ClipItem(type: .file, fileName: fileName, rawData: fileData)
        } else {
          clipItem = nil
        }
      } else {
        clipItem = nil
      }
    case .configSync:
      if let json = try? JSONSerialization.jsonObject(with: decryptedData) as? [String: Any],
        let privacyMode = json["privacyMode"] as? Bool
      {
        delegate?.networkEngine(self, didReceivePrivacyModeUpdate: privacyMode)
      }
      clipItem = nil
    case .fileStart:
      clipItem = nil
      if let transfer = self.activeIncomingTransfer {
        let handle = transfer.fileHandle
        let part = transfer.partURL
        ioQueue.async {
          try? handle.close()
          try? FileManager.default.removeItem(at: part)
        }
        self.activeIncomingTransfer = nil
      }
      if let json = try? JSONSerialization.jsonObject(with: decryptedData) as? [String: Any],
        let transferId = json["transferId"] as? String,
        let fileIndex = json["fileIndex"] as? Int,
        let totalFiles = json["totalFiles"] as? Int,
        let relativePath = json["relativePath"] as? String,
        let fileSize = (json["fileSize"] as? NSNumber)?.int64Value,
        let totalBytes = (json["totalBytes"] as? NSNumber)?.int64Value
      {
        let fallbackName = (json["fileName"] as? String) ?? "file"
        let safeRelativePath = Self.sanitizeRelativePath(relativePath, fallbackFileName: fallbackName)
        let rawDestURL = self.downloadFolderURL.appendingPathComponent(safeRelativePath).standardizedFileURL
        let baseFolder = self.downloadFolderURL.standardizedFileURL.path
        let baseWithSlash = baseFolder.hasSuffix("/") ? baseFolder : baseFolder + "/"
        guard rawDestURL.path.hasPrefix(baseWithSlash) || rawDestURL.path == baseFolder else {
          break
        }
        let parentFolder = rawDestURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: parentFolder, withIntermediateDirectories: true)
        let destURL = uniqueDestinationURL(for: rawDestURL)
        let partURL = destURL.appendingPathExtension("rapidrop_part")
        FileManager.default.createFile(atPath: partURL.path, contents: nil)
        if let handle = try? FileHandle(forWritingTo: partURL) {
          self.activeIncomingTransfer = IncomingStreamTransfer(
            transferId: transferId,
            fileIndex: fileIndex,
            totalFiles: totalFiles,
            fileName: destURL.lastPathComponent,
            relativePath: relativePath,
            fileSize: fileSize,
            totalBytes: totalBytes,
            bytesReceived: 0,
            nextChunkIndex: 0,
            fileHandle: handle,
            targetURL: destURL,
            partURL: partURL,
            hasher: SHA256()
          )
          delegate?.networkEngine(
            self,
            didUpdateTransferProgress: transferId,
            fileName: destURL.lastPathComponent,
            bytesTransferred: 0,
            totalBytes: totalBytes,
            fileIndex: fileIndex,
            totalFiles: totalFiles,
            isComplete: false
          )
        }
      }
    case .fileChunk:
      clipItem = nil
      if var transfer = self.activeIncomingTransfer, decryptedData.count >= 28 {
        let idData = decryptedData.subdata(in: 0..<16)
        let incomingFileIndex = decryptedData.subdata(in: 16..<20).withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
        let incomingChunkIndex = decryptedData.subdata(in: 20..<28).withUnsafeBytes { $0.load(as: UInt64.self).bigEndian }
        var expectedId = Data(transfer.transferId.utf8)
        if expectedId.count < 16 {
          expectedId.append(Data(repeating: 0, count: 16 - expectedId.count))
        } else {
          expectedId = expectedId.prefix(16)
        }
        guard idData == expectedId,
          Int(incomingFileIndex) == transfer.fileIndex,
          incomingChunkIndex == transfer.nextChunkIndex
        else {
          break
        }
        let chunkPayload = decryptedData.subdata(in: 28..<decryptedData.count)
        if transfer.fileSize > 0 && transfer.bytesReceived + Int64(chunkPayload.count) > transfer.fileSize {
          let handle = transfer.fileHandle
          let part = transfer.partURL
          ioQueue.async {
            try? handle.close()
            try? FileManager.default.removeItem(at: part)
          }
          self.activeIncomingTransfer = nil
          delegate?.networkEngineDidCancelTransfer(self, transferId: transfer.transferId, reason: .integrityMismatch)
          break
        }
        let handle = transfer.fileHandle
        ioQueue.async {
          try? handle.write(contentsOf: chunkPayload)
        }
        transfer.hasher.update(data: chunkPayload)
        transfer.bytesReceived += Int64(chunkPayload.count)
        transfer.nextChunkIndex += 1
        self.activeIncomingTransfer = transfer
        delegate?.networkEngine(
          self,
          didUpdateTransferProgress: transfer.transferId,
          fileName: transfer.fileName,
          bytesTransferred: transfer.bytesReceived,
          totalBytes: transfer.totalBytes,
          fileIndex: transfer.fileIndex,
          totalFiles: transfer.totalFiles,
          isComplete: false
        )
      }
    case .fileEnd:
      clipItem = nil
      if let transfer = self.activeIncomingTransfer {
        self.activeIncomingTransfer = nil
        let finalDigest = transfer.hasher.finalize().map { String(format: "%02x", $0) }.joined()
        guard let json = try? JSONSerialization.jsonObject(with: decryptedData) as? [String: Any],
          let sentSha = json["sha256"] as? String,
          !sentSha.isEmpty,
          sentSha.lowercased() == finalDigest.lowercased(),
          transfer.fileSize <= 0 || transfer.bytesReceived == transfer.fileSize
        else {
          let handle = transfer.fileHandle
          let part = transfer.partURL
          ioQueue.async {
            try? handle.close()
            try? FileManager.default.removeItem(at: part)
          }
          delegate?.networkEngineDidCancelTransfer(self, transferId: transfer.transferId, reason: .integrityMismatch)
          break
        }
        let handle = transfer.fileHandle
        let part = transfer.partURL
        let target = transfer.targetURL
        let tid = transfer.transferId
        let fname = transfer.fileName
        let tBytes = transfer.totalBytes
        let fIdx = transfer.fileIndex
        let tFiles = transfer.totalFiles
        ioQueue.async { [weak self] in
          try? handle.synchronize()
          try? handle.close()
          try? FileManager.default.removeItem(at: target)
          try? FileManager.default.moveItem(at: part, to: target)
          self?.queue.async { [weak self] in
            guard let self else { return }
            self.delegate?.networkEngine(
              self,
              didUpdateTransferProgress: tid,
              fileName: fname,
              bytesTransferred: tBytes,
              totalBytes: tBytes,
              fileIndex: fIdx,
              totalFiles: tFiles,
              isComplete: true
            )
            self.delegate?.networkEngine(
              self,
              didReceiveClip: ClipItem(type: .file, fileName: fname, rawData: nil)
            )
          }
        }
      }
    case .fileCancel:
      clipItem = nil
      if let transfer = self.activeIncomingTransfer {
        let tid = transfer.transferId
        let handle = transfer.fileHandle
        let part = transfer.partURL
        ioQueue.async {
          try? handle.close()
          try? FileManager.default.removeItem(at: part)
        }
        self.activeIncomingTransfer = nil
        delegate?.networkEngineDidCancelTransfer(self, transferId: tid, reason: .userCancelled)
      }
    default:
      clipItem = nil
    }

    if let item = clipItem {
      delegate?.networkEngine(self, didReceiveClip: item)
    }
  }

  public func sendClip(_ item: ClipItem) {
    queue.async { [weak self] in
      guard let self, let key = self.sessionKey, !self.activeConnections.isEmpty else {
        return
      }

      let payload: Data
      let packetType: PacketType
      switch item.type {
      case .text:
        guard let text = item.textContent, let data = text.data(using: .utf8) else { return }
        payload = data
        packetType = .clipText
      case .url:
        guard let urlString = item.textContent, let data = urlString.data(using: .utf8) else {
          return
        }
        payload = data
        packetType = .clipUrl
      case .image:
        guard let imageData = item.rawData else { return }
        payload = imageData
        packetType = .clipImage
      case .file:
        guard let fileData = item.rawData else { return }
        let fileName = item.fileName ?? "File"
        guard let nameData = fileName.data(using: .utf8) else { return }
        var buffer = Data(capacity: 2 + nameData.count + fileData.count)
        var nameLenBig = UInt16(nameData.count).bigEndian
        buffer.append(Data(bytes: &nameLenBig, count: 2))
        buffer.append(nameData)
        buffer.append(fileData)
        payload = buffer
        packetType = .clipFile
      }

      guard let encrypted = try? CryptoEngine.encrypt(payload: payload, using: key) else { return }

      let frame = WireFrame(
        type: packetType,
        nonce: encrypted.nonce,
        ciphertext: encrypted.ciphertext,
        tag: encrypted.tag
      )

      let serializedData = frame.serialize()
      for connection in self.activeConnections {
        connection.send(
          content: serializedData,
          completion: .contentProcessed { [weak self, weak connection] error in
            if error != nil, let self, let connection {
              self.queue.async {
                connection.cancel()
                self.activeConnections.removeAll { $0 === connection }
                if self.activeConnections.isEmpty {
                  self.notifyConnectionState(isConnected: false, peerName: nil)
                }
              }
            }
          })
      }
    }
  }
private actor SendWindow {
  private var inFlight = 0
  private let maxInFlight: Int
  private var waiters: [CheckedContinuation<Void, Never>] = []
  private var emptyWaiters: [CheckedContinuation<Void, Never>] = []
  private var firstError: Error?

  init(maxInFlight: Int = 4) {
    self.maxInFlight = maxInFlight
  }

  func acquire() async throws {
    if let error = firstError {
      throw error
    }
    if inFlight < maxInFlight {
      inFlight += 1
      return
    }
    await withCheckedContinuation { continuation in
      waiters.append(continuation)
    }
    if let error = firstError {
      throw error
    }
  }

  func release(error: Error? = nil) {
    if let error, firstError == nil {
      firstError = error
    }
    inFlight -= 1
    if !waiters.isEmpty {
      inFlight += 1
      let next = waiters.removeFirst()
      next.resume()
    } else if inFlight == 0 {
      let drained = emptyWaiters
      emptyWaiters.removeAll()
      for w in drained {
        w.resume()
      }
    }
  }

  func waitUntilDrained() async throws {
    if let error = firstError {
      throw error
    }
    if inFlight == 0 {
      return
    }
    await withCheckedContinuation { continuation in
      emptyWaiters.append(continuation)
    }
    if let error = firstError {
      throw error
    }
  }
}

  public func sendStreamingFile(
    fileURL: URL,
    relativePath: String,
    fileIndex: Int,
    totalFiles: Int,
    totalBytes: Int64,
    transferId: String,
    onProgress: (@Sendable (Int64) -> Void)? = nil
  ) async throws {
    guard let key = self.sessionKey, let connection = self.activeConnections.first else {
      throw CryptoError.encryptionFailed
    }
    guard let fileValues = try? fileURL.resourceValues(forKeys: [.fileSizeKey]),
      let fileSize = fileValues.fileSize
    else {
      throw CryptoError.encryptionFailed
    }
    let fileName = fileURL.lastPathComponent

    let startJson: [String: Any] = [
      "transferId": transferId,
      "fileIndex": fileIndex,
      "totalFiles": totalFiles,
      "fileName": fileName,
      "relativePath": relativePath,
      "fileSize": Int64(fileSize),
      "totalBytes": totalBytes
    ]
    let startData = try JSONSerialization.data(withJSONObject: startJson)
    let startEnc = try CryptoEngine.encrypt(payload: startData, using: key)
    let startFrame = WireFrame(
      type: .fileStart,
      nonce: startEnc.nonce,
      ciphertext: startEnc.ciphertext,
      tag: startEnc.tag
    )
    try await sendRawData(startFrame.serialize(), on: connection)

    let fileHandle = try FileHandle(forReadingFrom: fileURL)
    defer { try? fileHandle.close() }

    var hasher = SHA256()
    var chunkIndex: UInt64 = 0
    var bytesSentForFile: Int64 = 0
    let chunkSize = WireFrame.streamingChunkSize
    let window = SendWindow(maxInFlight: 4)

    while true {
      if Task.isCancelled {
        throw CancellationError()
      }
      let rawChunk = try fileHandle.read(upToCount: chunkSize) ?? Data()
      if rawChunk.isEmpty { break }
      hasher.update(data: rawChunk)

      var chunkHeader = Data(capacity: 28)
      var idData = Data(transferId.utf8)
      if idData.count < 16 {
        idData.append(Data(repeating: 0, count: 16 - idData.count))
      } else {
        idData = idData.prefix(16)
      }
      chunkHeader.append(idData)

      var fileIndexBig = UInt32(fileIndex).bigEndian
      chunkHeader.append(Data(bytes: &fileIndexBig, count: 4))

      var chunkIndexBig = chunkIndex.bigEndian
      chunkHeader.append(Data(bytes: &chunkIndexBig, count: 8))

      var chunkPayload = chunkHeader
      chunkPayload.append(rawChunk)

      let chunkEnc = try CryptoEngine.encrypt(payload: chunkPayload, using: key)
      let chunkFrame = WireFrame(
        type: .fileChunk,
        nonce: chunkEnc.nonce,
        ciphertext: chunkEnc.ciphertext,
        tag: chunkEnc.tag
      )
      let serialized = chunkFrame.serialize()

      try await window.acquire()
      connection.send(
        content: serialized,
        completion: .contentProcessed { error in
          Task {
            await window.release(error: error)
          }
        })

      chunkIndex += 1
      bytesSentForFile += Int64(rawChunk.count)
      onProgress?(bytesSentForFile)
    }

    try await window.waitUntilDrained()

    let fileDigest = hasher.finalize().map { String(format: "%02x", $0) }.joined()
    let endJson: [String: Any] = [
      "transferId": transferId,
      "fileIndex": fileIndex,
      "sha256": fileDigest,
      "status": "OK"
    ]
    let endData = try JSONSerialization.data(withJSONObject: endJson)
    let endEnc = try CryptoEngine.encrypt(payload: endData, using: key)
    let endFrame = WireFrame(
      type: .fileEnd,
      nonce: endEnc.nonce,
      ciphertext: endEnc.ciphertext,
      tag: endEnc.tag
    )
    try await sendRawData(endFrame.serialize(), on: connection)
  }

  public func sendCancelTransfer(transferId: String, reason: String = "user_cancelled") async {
    guard let key = self.sessionKey, let connection = self.activeConnections.first else { return }
    let cancelJson: [String: Any] = [
      "transferId": transferId,
      "reason": reason
    ]
    guard let cancelData = try? JSONSerialization.data(withJSONObject: cancelJson),
          let cancelEnc = try? CryptoEngine.encrypt(payload: cancelData, using: key) else { return }
    let cancelFrame = WireFrame(
      type: .fileCancel,
      nonce: cancelEnc.nonce,
      ciphertext: cancelEnc.ciphertext,
      tag: cancelEnc.tag
    )
    try? await sendRawData(cancelFrame.serialize(), on: connection)
  }

  public func cancelIncomingTransfer() {
    if let transfer = self.activeIncomingTransfer {
      let handle = transfer.fileHandle
      let part = transfer.partURL
      ioQueue.async {
        try? handle.close()
        try? FileManager.default.removeItem(at: part)
      }
      self.activeIncomingTransfer = nil
      let tid = transfer.transferId
      delegate?.networkEngineDidCancelTransfer(self, transferId: tid, reason: .userCancelled)
    }
  }

  private func sendRawData(_ data: Data, on connection: NWConnection) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      connection.send(
        content: data,
        completion: .contentProcessed { error in
          if let error {
            continuation.resume(throwing: error)
          } else {
            continuation.resume()
          }
        })
    }
  }

  private func getLocalIPv4() -> String? {
    var address: String?
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return nil }
    defer { freeifaddrs(ifaddr) }
    for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
      let flags = Int32(ptr.pointee.ifa_flags)
      let addr = ptr.pointee.ifa_addr.pointee
      if (flags & (IFF_UP | IFF_RUNNING | IFF_LOOPBACK)) == (IFF_UP | IFF_RUNNING) {
        if addr.sa_family == UInt8(AF_INET) {
          var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
          if getnameinfo(
            ptr.pointee.ifa_addr, socklen_t(addr.sa_len), &hostname, socklen_t(hostname.count), nil,
            0, NI_NUMERICHOST) == 0
          {
            let ip =
              hostname.withUnsafeBufferPointer { ptr in ptr.baseAddress.map { String(cString: $0) }
              } ?? ""
            let name = ptr.pointee.ifa_name.withMemoryRebound(to: CChar.self, capacity: 16) {
              String(cString: $0)
            }
            if name.hasPrefix("en") {
              return ip
            }
            if address == nil && !ip.hasPrefix("127.") {
              address = ip
            }
          }
        }
      }
    }
    return address
  }

  public func sendPairInvite(to device: DiscoveredClientDevice, pin: String = "", host: String? = nil, port: Int = 0) {
    queue.async { [weak self] in
      guard let self else { return }
      let (priv, pubHex) = CryptoEngine.generateEphemeralKeypair()
      let nonceData = CryptoEngine.generateNonce(count: 16)
      let nonceHex = nonceData.map { String(format: "%02x", $0) }.joined()
      self.pendingInitiatorKeypair = (priv, pubHex, nonceHex)

      let targetHost = host?.isEmpty == false ? host : device.host
      let targetPort = port > 0 ? port : (device.port ?? Int(WireFrame.defaultClientPort))
      let endpoint: NWEndpoint
      if let targetHost = targetHost, !targetHost.isEmpty {
        endpoint = NWEndpoint.hostPort(
          host: NWEndpoint.Host(targetHost),
          port: NWEndpoint.Port(rawValue: UInt16(targetPort)) ?? NWEndpoint.Port(rawValue: WireFrame.defaultClientPort)!)
      } else {
        endpoint =
          device.endpoint
          ?? NWEndpoint.service(
            name: device.name, type: Self.clientServiceType, domain: "local", interface: nil)
      }
      let conn = NWConnection(to: endpoint, using: Self.createTcpParameters())
      self.pendingHandshakeConnections.append(conn)
      conn.stateUpdateHandler = { [weak self, weak conn] state in
        guard let self, let conn else { return }
        switch state {
        case .ready:
          let desc = DeviceDescriptor.current()
          let payloadDict: [String: Any] = [
            "version": 1,
            "id": Self.localDeviceId,
            "fromDeviceName": desc.deviceName,
            "deviceType": "mac",
            "model": desc.modelId,
            "chip": desc.chip,
            "publicKey": pubHex,
            "nonce": nonceHex,
            "host": self.getLocalIPv4() ?? "",
            "port": Int(Self.servicePort),
          ]
          if let jsonData = try? JSONSerialization.data(withJSONObject: payloadDict) {
            let frame = WireFrame(
              type: .pairInvite,
              nonce: Data(repeating: 0, count: 12),
              ciphertext: jsonData,
              tag: Data(repeating: 0, count: 16)
            )
            let serialized = frame.serialize()
            conn.send(content: serialized, completion: .contentProcessed { [weak self, weak conn] _ in
              guard let self, let conn else { return }
              self.queue.asyncAfter(deadline: .now() + 0.25) {
                conn.cancel()
                self.pendingHandshakeConnections.removeAll { $0 === conn }
              }
            })
          }
        case .failed, .cancelled:
          self.pendingHandshakeConnections.removeAll { $0 === conn }
        default:
          break
        }
      }
      conn.start(queue: self.queue)
    }
  }
  public func initiatePairing(with device: DiscoveredClientDevice, pin: String) {
    queue.async { [weak self] in
      guard let self else { return }
      self.pendingPairingConnection?.cancel()
      let key = CryptoEngine.deriveKey(from: pin)
      self.sessionKey = key

      let endpoint =
        device.endpoint
        ?? NWEndpoint.service(
          name: device.name, type: Self.clientServiceType, domain: "local", interface: nil)
      let conn = NWConnection(to: endpoint, using: Self.createTcpParameters())
      self.pendingPairingConnection = conn
      conn.stateUpdateHandler = { [weak self, weak conn] state in
        guard let self, let conn else { return }
        if case .ready = state {
          self.activeConnections.append(conn)
          self.receiveFrame(from: conn)
          self.sendPairRequest(to: conn, using: key)
        } else if case .failed = state, conn === self.pendingPairingConnection {
          self.pendingPairingConnection = nil
        } else if case .cancelled = state, conn === self.pendingPairingConnection {
          self.pendingPairingConnection = nil
        }
      }
      conn.start(queue: self.queue)
    }
  }

  public func cancelPendingPairingConnections() {
    queue.async { [weak self] in
      guard let self else { return }
      self.pendingInitiatorKeypair = nil
      self.pendingReceiverState = nil
      self.pendingReceiverAcceptPayload = nil
      self.pendingPairingConnection?.cancel()
      self.pendingPairingConnection = nil
      for conn in self.pendingHandshakeConnections {
        conn.cancel()
      }
      self.pendingHandshakeConnections.removeAll()
      for conn in self.activeConnections {
        conn.cancel()
      }
      self.activeConnections.removeAll()
    }
  }
  public func connect(to device: DiscoveredClientDevice) {
    let endpoint = device.endpoint ?? NWEndpoint.service(
      name: device.name, type: Self.serviceType, domain: "local", interface: nil)
    connect(to: endpoint)
  }

  public func connect(to host: String, port: UInt16) {
    let endpoint = NWEndpoint.hostPort(
      host: NWEndpoint.Host(host),
      port: NWEndpoint.Port(rawValue: port) ?? NWEndpoint.Port(rawValue: WireFrame.defaultPort)!)
    connect(to: endpoint)
  }

  public func connect(to endpoint: NWEndpoint) {
    queue.async { [weak self] in
      guard let self else { return }
      self.connectionGeneration &+= 1
      let currentGen = self.connectionGeneration
      self.currentOutgoingConnection?.cancel()
      for oldConn in self.activeConnections {
        oldConn.cancel()
      }
      self.activeConnections.removeAll()

      let conn = NWConnection(to: endpoint, using: Self.createTcpParameters())
      self.currentOutgoingConnection = conn
      conn.stateUpdateHandler = { [weak self, weak conn] state in
        guard let self, let conn else { return }
        guard self.connectionGeneration == currentGen else {
          conn.cancel()
          return
        }
        switch state {
        case .ready:
          self.lastActivityTimestamp = Date()
          if !self.activeConnections.contains(where: { $0 === conn }) {
            self.activeConnections.append(conn)
          }
          self.receiveFrame(from: conn)
          if let key = self.sessionKey {
            self.sendPairRequest(to: conn, using: key)
            self.sendDeviceInfo(to: conn)
          }
        case .failed, .cancelled:
          self.activeConnections.removeAll { $0 === conn }
          if self.activeConnections.isEmpty {
            if let transfer = self.activeIncomingTransfer {
              let tid = transfer.transferId
              try? transfer.fileHandle.close()
              try? FileManager.default.removeItem(at: transfer.partURL)
              self.activeIncomingTransfer = nil
              self.delegate?.networkEngineDidCancelTransfer(self, transferId: tid, reason: .connectionLost)
            }
            self.notifyConnectionState(isConnected: false, peerName: nil)
          }
        default:
          break
        }
      }
      conn.start(queue: self.queue)
    }
  }
  public func sendPairAccept(to host: String, port: UInt16) {
    queue.async { [weak self] in
      guard let self else { return }
      if let state = self.pendingReceiverState {
        self.sessionKey = state.sessionKey
      }
      let endpoint = NWEndpoint.hostPort(
        host: NWEndpoint.Host(host),
        port: NWEndpoint.Port(rawValue: port) ?? NWEndpoint.Port(
          rawValue: WireFrame.defaultClientPort)!)
      let conn = NWConnection(to: endpoint, using: Self.createTcpParameters())
      self.pendingHandshakeConnections.append(conn)
      conn.stateUpdateHandler = { [weak self, weak conn] state in
        guard let self, let conn else { return }
        switch state {
        case .ready:
          let payloadDict = self.pendingReceiverAcceptPayload ?? [
            "version": 1,
            "id": Self.localDeviceId,
            "status": "accepted",
            "fromDeviceName": DeviceDescriptor.current().deviceName,
            "deviceType": "mac",
            "host": self.getLocalIPv4() ?? "",
            "port": Int(Self.servicePort),
          ]
          if let jsonData = try? JSONSerialization.data(withJSONObject: payloadDict) {
            let frame = WireFrame(
              type: .pairAccept,
              nonce: Data(repeating: 0, count: 12),
              ciphertext: jsonData,
              tag: Data(repeating: 0, count: 16)
            )
            let serialized = frame.serialize()
            conn.send(
              content: serialized,
              completion: .contentProcessed { [weak self, weak conn] _ in
                guard let self, let conn else { return }
                self.queue.asyncAfter(deadline: .now() + 0.25) {
                  conn.cancel()
                  self.pendingHandshakeConnections.removeAll { $0 === conn }
                }
              })
          }
        case .failed, .cancelled:
          self.pendingHandshakeConnections.removeAll { $0 === conn }
        default:
          break
        }
      }
      conn.start(queue: self.queue)
    }
  }

  private func sendPairOffer(to host: String, port: UInt16, payload: [String: Any]) {
    guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else { return }
    let endpoint = NWEndpoint.hostPort(
      host: NWEndpoint.Host(host),
      port: NWEndpoint.Port(rawValue: port) ?? NWEndpoint.Port(
        rawValue: WireFrame.defaultClientPort)!)
    let conn = NWConnection(to: endpoint, using: Self.createTcpParameters())
    self.pendingHandshakeConnections.append(conn)
    conn.stateUpdateHandler = { [weak self, weak conn] state in
      guard let self, let conn else { return }
      switch state {
      case .ready:
        let frame = WireFrame(
          type: .pairAccept,
          nonce: Data(repeating: 0, count: 12),
          ciphertext: jsonData,
          tag: Data(repeating: 0, count: 16)
        )
        let serialized = frame.serialize()
        conn.send(
          content: serialized,
          completion: .contentProcessed { [weak self, weak conn] _ in
            guard let self, let conn else { return }
            self.queue.asyncAfter(deadline: .now() + 0.25) {
              conn.cancel()
              self.pendingHandshakeConnections.removeAll { $0 === conn }
            }
          })
      case .failed, .cancelled:
        self.pendingHandshakeConnections.removeAll { $0 === conn }
      default:
        break
      }
    }
    conn.start(queue: self.queue)
  }

  public func sendPairDecline(to host: String, port: UInt16, reason: String = "DECLINED") {
    queue.async { [weak self] in
      guard let self else { return }
      self.pendingReceiverState = nil
      let endpoint = NWEndpoint.hostPort(
        host: NWEndpoint.Host(host),
        port: NWEndpoint.Port(rawValue: port) ?? NWEndpoint.Port(
          rawValue: WireFrame.defaultClientPort)!)
      let conn = NWConnection(to: endpoint, using: Self.createTcpParameters())
      conn.stateUpdateHandler = { [weak conn] state in
        guard let conn else { return }
        if case .ready = state {
          let payload: [String: Any] = ["version": 1, "reason": reason]
          if let jsonData = try? JSONSerialization.data(withJSONObject: payload) {
            let frame = WireFrame(
              type: .pairFail,
              nonce: Data(repeating: 0, count: 12),
              ciphertext: jsonData,
              tag: Data(repeating: 0, count: 16)
            )
            let serialized = frame.serialize()
            conn.send(
              content: serialized,
              completion: .contentProcessed { _ in
                conn.cancel()
              })
          }
        }
      }
      conn.start(queue: self.queue)
    }
  }

  public func sendPairCancel(to device: DiscoveredClientDevice) {
    queue.async { [weak self] in
      guard let self else { return }
      self.pendingInitiatorKeypair = nil
      let endpoint =
        device.endpoint
        ?? NWEndpoint.service(
          name: device.name, type: Self.clientServiceType, domain: "local", interface: nil)
      let conn = NWConnection(to: endpoint, using: Self.createTcpParameters())
      conn.stateUpdateHandler = { [weak conn] state in
        guard let conn else { return }
        if case .ready = state {
          let payload: [String: Any] = ["version": 1, "reason": "CANCELLED"]
          if let jsonData = try? JSONSerialization.data(withJSONObject: payload) {
            let frame = WireFrame(
              type: .pairFail,
              nonce: Data(repeating: 0, count: 12),
              ciphertext: jsonData,
              tag: Data(repeating: 0, count: 16)
            )
            let serialized = frame.serialize()
            conn.send(
              content: serialized,
              completion: .contentProcessed { _ in
                conn.cancel()
              })
          }
        }
      }
      conn.start(queue: self.queue)
    }
  }

  public func sendDisconnect() {
    queue.async { [weak self] in
      guard let self else { return }
      self.pendingPairingConnection?.cancel()
      self.pendingPairingConnection = nil
      let frame = WireFrame(
        type: .disconnect,
        nonce: Data(repeating: 0, count: 12),
        ciphertext: Data("UNPAIR".utf8),
        tag: Data(repeating: 0, count: 16)
      )
      let serialized = frame.serialize()
      for connection in self.activeConnections {
        connection.send(
          content: serialized,
          completion: .contentProcessed { [weak connection] _ in
            connection?.cancel()
          })
      }
      self.activeConnections.removeAll()
      self.sessionKey = nil
      self.notifyConnectionState(isConnected: false, peerName: nil)
    }
  }
  public func sendConfigSync(privacyMode: Bool) {
    queue.async { [weak self] in
      guard let self, let key = self.sessionKey, !self.activeConnections.isEmpty else {
        return
      }
      let dict: [String: Any] = ["privacyMode": privacyMode]
      guard let jsonData = try? JSONSerialization.data(withJSONObject: dict),
        let encrypted = try? CryptoEngine.encrypt(payload: jsonData, using: key)
      else {
        return
      }
      let frame = WireFrame(
        type: .configSync,
        nonce: encrypted.nonce,
        ciphertext: encrypted.ciphertext,
        tag: encrypted.tag
      )
      let serialized = frame.serialize()
      for connection in self.activeConnections {
        connection.send(content: serialized, completion: .contentProcessed { _ in })
      }
    }
  }

  private func sendPairRequest(to connection: NWConnection, using key: SymmetricKey) {
    let descriptor = DeviceDescriptor.current()
    guard let payload = try? JSONEncoder().encode(descriptor),
      let encrypted = try? CryptoEngine.encrypt(payload: payload, using: key)
    else {
      return
    }
    let frame = WireFrame(
      type: .pairRequest,
      nonce: encrypted.nonce,
      ciphertext: encrypted.ciphertext,
      tag: encrypted.tag
    )
    let serialized = frame.serialize()
    connection.send(content: serialized, completion: .contentProcessed { _ in })
  }

  private func notifyConnectionState(isConnected: Bool, peerName: String?) {
    if isConnected {
      if !isConnectionNotified {
        isConnectionNotified = true
        delegate?.networkEngine(self, didUpdateConnectionState: true, peerName: peerName)
      }
    } else {
      if isConnectionNotified {
        isConnectionNotified = false
        delegate?.networkEngine(self, didUpdateConnectionState: false, peerName: nil)
      }
    }
  }
}
