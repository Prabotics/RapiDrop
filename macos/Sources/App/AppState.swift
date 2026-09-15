import ServiceManagement
import CryptoKit
import Foundation
import SwiftUI

public enum MediaDestinationMode: String, CaseIterable, Sendable, Codable {
  case both = "both"
  case folderOnly = "folderOnly"
  case clipboardOnly = "clipboardOnly"

  public var displayLabel: String {
    switch self {
    case .both: return "Folder & Clipboard"
    case .folderOnly: return "Folder Only"
    case .clipboardOnly: return "Clipboard Only"
    }
  }

  public var subtitle: String {
    switch self {
    case .both: return "Saves to folder and sets on pasteboard"
    case .folderOnly: return "Saves to folder without changing pasteboard"
    case .clipboardOnly: return "Sets on pasteboard only (no disk file)"
    }
  }
}

final class TransferSpeedTracker: @unchecked Sendable {
  private let lock = NSLock()
  private var lastProgressUpdateTime: TimeInterval = 0
  private var lastBytesCheckpoint: Int64 = 0
  private var lastCheckpointTime: TimeInterval = ProcessInfo.processInfo.systemUptime
  private var smoothedSpeed: Double = 0.0

  func reset(initialBytes: Int64 = 0) {
    lock.lock()
    defer { lock.unlock() }
    let now = ProcessInfo.processInfo.systemUptime
    lastProgressUpdateTime = now
    lastBytesCheckpoint = initialBytes
    lastCheckpointTime = now
    smoothedSpeed = 0.0
  }

  func update(bytes: Int64, totalBytes: Int64) -> (shouldUpdate: Bool, speed: Double) {
    lock.lock()
    defer { lock.unlock() }
    let now = ProcessInfo.processInfo.systemUptime
    let isFinished = bytes >= totalBytes
    guard isFinished || now - lastProgressUpdateTime >= 0.25 else {
      return (false, smoothedSpeed)
    }
    let timeDelta = now - lastCheckpointTime
    if timeDelta >= 0.2 {
      let bytesDelta = bytes - lastBytesCheckpoint
      let instantSpeed = Double(bytesDelta) / timeDelta
      smoothedSpeed = smoothedSpeed == 0.0 ? instantSpeed : (smoothedSpeed * 0.7 + instantSpeed * 0.3)
      lastBytesCheckpoint = bytes
      lastCheckpointTime = now
    }
    lastProgressUpdateTime = now
    return (true, smoothedSpeed)
  }
}

@MainActor
public final class AppState: ObservableObject, NetworkEngineDelegate {
  @Published public var isConnected: Bool = false
  @Published public var peerName: String? = nil
  @Published public var pairedPeerName: String? = nil
  @Published public var recentClips: [ClipItem] = []
  @Published public var pinnedClipIds: Set<UUID> = []
  @Published public var showSyncHistory: Bool = true {
    didSet {
      UserDefaults.standard.set(showSyncHistory, forKey: "rapidrop_show_sync_history")
      if !showSyncHistory {
        recentClips.removeAll { !pinnedClipIds.contains($0.id) }
      }
    }
  }
  @Published public var isSyncing: Bool = false
  @Published public var isSoundEnabled: Bool {
    didSet {
      UserDefaults.standard.set(isSoundEnabled, forKey: "rapidrop_sound_enabled")
    }
  }
  @Published public var isLaunchAtLogin: Bool = false
  public var onRequestClosePopover: (() -> Void)?
  private var syncResetTask: Task<Void, Never>? = nil
  private var lastReceivedClipContentHash: Int = 0
  private var lastAutoReconnectAttempt: Date = .distantPast
  private var reconnectAttempts: Int = 0
  private var autoReconnectTask: Task<Void, Never>? = nil
  private var pairingTimeoutTask: Task<Void, Never>? = nil
  @Published public var pairingPin: String = ""
  @Published public var discoveredDevices: [DiscoveredClientDevice] = []
  @Published public var selectedDeviceForPairing: DiscoveredClientDevice? = nil
  @Published public var incomingPairInvite:
    (deviceName: String, pin: String?, host: String?, port: Int)? = nil
  @Published public var pendingPairingSasCode: String? = nil
  @Published public var pairingErrorMessage: String? = nil
  @Published public var transferErrorMessage: String? = nil
  private var transferErrorMessageResetTask: Task<Void, Never>? = nil
  @Published public var toastMessage: String? = nil
  private var toastDismissTask: Task<Void, Never>? = nil
  public struct TransferProgressInfo: Equatable, Sendable {
    public let transferId: String
    public let fileName: String
    public let bytesTransferred: Int64
    public let totalBytes: Int64
    public let fileIndex: Int
    public let totalFiles: Int
    public let isSending: Bool
    public let speedBytesPerSec: Double

    public var progressFraction: Double {
      guard totalBytes > 0 else { return 0 }
      return min(1.0, max(0.0, Double(bytesTransferred) / Double(totalBytes)))
    }

    public var remainingSeconds: TimeInterval? {
      guard speedBytesPerSec > 10_000, totalBytes > bytesTransferred else { return nil }
      let remainingBytes = Double(totalBytes - bytesTransferred)
      return remainingBytes / speedBytesPerSec
    }

    public var formattedEta: String? {
      guard let sec = remainingSeconds, sec >= 1 else { return nil }
      if sec < 60 {
        return "\(Int(sec))s left"
      } else {
        let mins = Int(sec) / 60
        let remSec = Int(sec) % 60
        return "\(mins)m \(remSec)s left"
      }
    }
  }
  private var activeOutgoingTransferTask: Task<Void, Never>? = nil
  @Published public var activeTransferProgress: TransferProgressInfo? = nil
  private let rxSpeedTracker = TransferSpeedTracker()
  private var currentRxTransferId: String? = nil
  @Published public var mediaDestinationMode: MediaDestinationMode {
    didSet {
      UserDefaults.standard.set(mediaDestinationMode.rawValue, forKey: "rapidrop_media_destination")
      network.downloadFolderURL = mediaFolderURL
    }
  }
  @Published public var customMediaFolderURL: URL? {
    didSet {
      if let url = customMediaFolderURL {
        UserDefaults.standard.set(url.path, forKey: "rapidrop_media_folder_path")
      } else {
        UserDefaults.standard.removeObject(forKey: "rapidrop_media_folder_path")
      }
    }
  }

  private var storedPairedPin: String? {
    if let secure = CryptoEngine.loadSecureValue(key: "rapidrop_paired_pin") {
      return secure
    }
    if let legacy = UserDefaults.standard.string(forKey: "rapidrop_paired_pin") {
      CryptoEngine.saveSecureValue(key: "rapidrop_paired_pin", value: legacy)
      UserDefaults.standard.removeObject(forKey: "rapidrop_paired_pin")
      return legacy
    }
    return nil
  }

  private var storedPairedKey: String? {
    if let secure = CryptoEngine.loadSecureValue(key: "rapidrop_paired_session_key") {
      return secure
    }
    if let legacy = UserDefaults.standard.string(forKey: "rapidrop_paired_session_key") {
      CryptoEngine.saveSecureValue(key: "rapidrop_paired_session_key", value: legacy)
      UserDefaults.standard.removeObject(forKey: "rapidrop_paired_session_key")
      return legacy
    }
    return nil
  }

  public var isPaired: Bool {
    storedPairedKey != nil || (storedPairedPin != nil && pairedPeerName != nil)
  }

  public var unpairedDiscoveredDevices: [DiscoveredClientDevice] {
    let list: [DiscoveredClientDevice]
    if let paired = pairedPeerName, isPaired {
      let normPaired = normalizeDeviceName(paired)
      list = discoveredDevices.filter { dev in
        let normDev = normalizeDeviceName(dev.name)
        return normDev != normPaired
      }
    } else {
      list = discoveredDevices
    }
    var seen = Set<String>()
    var unique: [DiscoveredClientDevice] = []
    for dev in list {
      let key = dev.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
      if !seen.contains(key) {
        seen.insert(key)
        unique.append(dev)
      }
    }
    return unique
  }

  public var defaultMediaFolderURL: URL {
    if let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
    {
      return downloads.appendingPathComponent("RapiDrop")
    }
    return URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("RapiDrop")
  }

  public var mediaFolderURL: URL {
    customMediaFolderURL ?? defaultMediaFolderURL
  }

  public let monitor: PasteboardMonitor
  public let network: NetworkEngine

  public init() {
    let savedModeStr =
      UserDefaults.standard.string(forKey: "rapidrop_media_destination")

      ?? "both"
    self.mediaDestinationMode = MediaDestinationMode(rawValue: savedModeStr) ?? .both
    if let savedPath = UserDefaults.standard.string(forKey: "rapidrop_media_folder_path")

    {
      self.customMediaFolderURL = URL(fileURLWithPath: savedPath)
    } else {
      self.customMediaFolderURL = nil
    }
    let savedPinned =
      UserDefaults.standard.stringArray(forKey: "rapidrop_pinned_clip_ids")

      ?? []
    self.pinnedClipIds = Set(savedPinned.compactMap { UUID(uuidString: $0) })
    self.showSyncHistory =
      UserDefaults.standard.object(forKey: "rapidrop_show_sync_history") as? Bool

      ?? true
    self.isSoundEnabled =
      UserDefaults.standard.object(forKey: "rapidrop_sound_enabled") as? Bool

      ?? true
    if #available(macOS 13.0, *) {
      self.isLaunchAtLogin = SMAppService.mainApp.status == .enabled
    }
    self.monitor = PasteboardMonitor()
    self.network = NetworkEngine()
    let savedPin = storedPairedPin
    let savedPeer = UserDefaults.standard.string(forKey: "rapidrop_paired_peer_name")
    let savedKeyHex = storedPairedKey
    self.pairedPeerName = savedPeer

    let pin = savedPin ?? String(format: "%06d", Int.random(in: 100000...999999))
    self.pairingPin = pin
    self.network.delegate = self
    self.network.downloadFolderURL = self.mediaFolderURL
    if let hex = savedKeyHex, let keyData = CryptoEngine.hexToData(hex), keyData.count == 32 {
      self.network.setSessionKey(SymmetricKey(data: keyData))
    } else if let pin = savedPin {
      let key = CryptoEngine.deriveKey(from: pin)
      self.network.setSessionKey(key)
    }
    self.monitor.onClipCaptured = { [weak self] item in
      Task { @MainActor in
        self?.handleClipCaptured(item)
      }
    }

    self.network.onDiscoveredDevicesChanged = { [weak self] devices in
      DispatchQueue.main.async {
        guard let self else { return }
        self.discoveredDevices = devices
        self.checkAutoReconnect(devices: devices)
      }
    }

    self.monitor.start()
    self.monitor.setConnected(self.isConnected)
    self.network.start()

  }

  public func refreshDiscovery() {
    discoveredDevices.removeAll()
    network.restartBrowser()
  }
  public func selectDeviceForPairing(_ device: DiscoveredClientDevice) {
    pairingTimeoutTask?.cancel()
    self.selectedDeviceForPairing = device
    self.pendingPairingSasCode = nil
    network.sendPairInvite(to: device, host: device.host, port: device.port ?? 0)
    pairingTimeoutTask = Task { @MainActor in
      try? await Task.sleep(nanoseconds: 30_000_000_000)
      if self.selectedDeviceForPairing?.id == device.id {
        self.cancelPairingSelection()
      }
    }
  }
  public func acceptPairInvite() {
    pairingTimeoutTask?.cancel()
    pairingTimeoutTask = nil
    guard let invite = incomingPairInvite else { return }
    self.pairedPeerName = invite.deviceName
    self.peerName = invite.deviceName

    if let host = invite.host, !host.isEmpty {
      self.network.sendPairAccept(to: host, port: UInt16(invite.port))
    }
    if let keyData = self.network.getSessionKeyData() {
      let hex = keyData.map { String(format: "%02x", $0) }.joined()
      CryptoEngine.saveSecureValue(key: "rapidrop_paired_session_key", value: hex)
      UserDefaults.standard.removeObject(forKey: "rapidrop_paired_session_key")
    }
    if let sas = invite.pin, !sas.isEmpty {
      CryptoEngine.saveSecureValue(key: "rapidrop_paired_pin", value: sas)
      UserDefaults.standard.removeObject(forKey: "rapidrop_paired_pin")
      self.pairingPin = sas
    }
    UserDefaults.standard.set(invite.deviceName, forKey: "rapidrop_paired_peer_name")
    triggerHapticFeedback(.alignment)
    incomingPairInvite = nil
    pendingPairingSasCode = nil
  }
  public func declinePairInvite() {
    pairingTimeoutTask?.cancel()
    pairingTimeoutTask = nil
    if let invite = incomingPairInvite, let host = invite.host, !host.isEmpty {
      network.sendPairDecline(to: host, port: UInt16(invite.port), reason: "DECLINED")
    }
    incomingPairInvite = nil
    pendingPairingSasCode = nil
  }

  public func cancelPairingSelection() {
    pairingTimeoutTask?.cancel()
    pairingTimeoutTask = nil
    autoReconnectTask?.cancel()
    if let device = selectedDeviceForPairing {
      network.sendPairCancel(to: device)
    }
    network.cancelPendingPairingConnections()
    selectedDeviceForPairing = nil
    pendingPairingSasCode = nil
  }
  public func regeneratePin() {
    let newPin = String(format: "%06d", Int.random(in: 100000...999999))
    setPin(newPin)
  }

  public func setPin(_ pin: String) {
    let clean = String(pin.filter { $0.isNumber }.prefix(6))
    if clean.count == 6 {
      pairingPin = clean
      let key = CryptoEngine.deriveKey(from: clean)
      network.setSessionKey(key)
    }
  }

  public func unpair() {
    network.sendDisconnect()
    CryptoEngine.deleteSecureValue(key: "rapidrop_paired_pin")
    CryptoEngine.deleteSecureValue(key: "rapidrop_paired_session_key")
    UserDefaults.standard.removeObject(forKey: "rapidrop_paired_pin")
    UserDefaults.standard.removeObject(forKey: "rapidrop_paired_session_key")
    UserDefaults.standard.removeObject(forKey: "rapidrop_paired_peer_name")
    self.network.setSessionKey(nil)
    pairedPeerName = nil
    peerName = nil
    isConnected = false
    selectedDeviceForPairing = nil
    reconnectAttempts = 0
    regeneratePin()
  }

  private func checkAutoReconnect(devices: [DiscoveredClientDevice]) {
    guard !isConnected, isPaired, selectedDeviceForPairing == nil, incomingPairInvite == nil else {
      return
    }
    guard let paired = pairedPeerName, !paired.isEmpty else { return }
    let normPaired = normalizeDeviceName(paired)
    guard
      let match = devices.first(where: {
        let normDev = normalizeDeviceName($0.name)
        return normDev == normPaired
      })
    else { return }

    let now = Date()
    let backoffDelay = min(30.0, 3.0 * pow(1.5, Double(reconnectAttempts)))
    guard now.timeIntervalSince(lastAutoReconnectAttempt) >= backoffDelay else { return }
    lastAutoReconnectAttempt = now
    reconnectAttempts += 1
    autoReconnectTask?.cancel()
    autoReconnectTask = Task { @MainActor in
      guard !self.isConnected, self.selectedDeviceForPairing == nil else { return }
      self.network.connect(to: match)
    }
  }
  public func setSoundEnabled(_ enabled: Bool) {
    isSoundEnabled = enabled
  }

  public func toggleLaunchAtLogin() {
    if #available(macOS 13.0, *) {
      do {
        if SMAppService.mainApp.status == .enabled {
          try SMAppService.mainApp.unregister()
          isLaunchAtLogin = false
        } else {
          try SMAppService.mainApp.register()
          isLaunchAtLogin = SMAppService.mainApp.status == .enabled
        }
      } catch {
        isLaunchAtLogin = SMAppService.mainApp.status == .enabled
      }
    }
  }

  public func playSound(_ name: String) {
    guard isSoundEnabled else { return }
    NSSound(named: name)?.play()
  }

  public func triggerHapticFeedback(_ pattern: NSHapticFeedbackManager.FeedbackPattern = .generic) {
    NSHapticFeedbackManager.defaultPerformer.perform(pattern, performanceTime: .default)
  }

  private func handleClipCaptured(_ item: ClipItem) {
    guard isConnected else { return }
    let contentHash = computeContentHash(for: item)
    if contentHash != 0 && contentHash == lastReceivedClipContentHash {
      return
    }
    lastReceivedClipContentHash = 0
    addRecentClip(item)
    network.sendClip(item)
    triggerSyncFeedback()
  }

  private func computeContentHash(for item: ClipItem) -> Int {
    if let text = item.textContent, !text.isEmpty {
      return text.hashValue
    }
    if let data = item.rawData, !data.isEmpty {
      return data.hashValue
    }
    return 0
  }
  @discardableResult
  public func sendClipboardNow() -> Bool {
    guard isConnected else { return false }
    guard let item = monitor.readCurrentClip() else {
      playSound("Basso")
      return false
    }
    addRecentClip(item)
    network.sendClip(item)
    playSound("Glass")
    triggerHapticFeedback(.generic)
    triggerSyncFeedback()
    return true
  }

  @discardableResult
  public func sendFile(at url: URL) -> Bool {
    sendFiles(at: [url])
    return true
  }

  public func cancelActiveTransfer() {
    guard let progress = activeTransferProgress else { return }
    let tid = progress.transferId
    let isSending = progress.isSending
    withAnimation(.easeOut(duration: 0.25)) {
      self.activeTransferProgress = nil
    }
    if isSending {
      activeOutgoingTransferTask?.cancel()
      activeOutgoingTransferTask = nil
    } else {
      network.cancelIncomingTransfer()
    }
    Task {
      await network.sendCancelTransfer(transferId: tid, reason: "user_cancelled")
    }
    triggerHapticFeedback(.generic)
    playSound("Basso")
    showToast("Transfer cancelled")
  }

  public func sendFiles(at urls: [URL]) {
    guard isConnected else {
      setTransferError("No device connected")
      playSound("Basso")
      return
    }
    let files = collectFiles(from: urls)
    guard !files.isEmpty else {
      setTransferError("No files found to send")
      return
    }
    let totalBytes = files.reduce(0) { $0 + $1.size }
    let transferId = UUID().uuidString

    self.triggerSyncFeedback()
    self.playSound("Glass")
    self.triggerHapticFeedback(.generic)

    activeOutgoingTransferTask?.cancel()
    activeOutgoingTransferTask = Task.detached(priority: .utility) {
      var overallBytesSent: Int64 = 0
      let speedTracker = TransferSpeedTracker()

      for (index, file) in files.enumerated() {
        do {
          let currentFileOffset = overallBytesSent
          try await self.network.sendStreamingFile(
            fileURL: file.url,
            relativePath: file.relativePath,
            fileIndex: index,
            totalFiles: files.count,
            totalBytes: totalBytes,
            transferId: transferId
          ) { bytesInFile in
            let currentOverall = currentFileOffset + bytesInFile
            let (shouldUpdate, speed) = speedTracker.update(bytes: currentOverall, totalBytes: totalBytes)
            if shouldUpdate {
              Task { @MainActor in
                self.activeTransferProgress = TransferProgressInfo(
                  transferId: transferId,
                  fileName: file.url.lastPathComponent,
                  bytesTransferred: currentOverall,
                  totalBytes: totalBytes,
                  fileIndex: index + 1,
                  totalFiles: files.count,
                  isSending: true,
                  speedBytesPerSec: speed
                )
              }
            }
          }
          overallBytesSent += file.size
        } catch {
          await MainActor.run {
            self.setTransferError("Failed to send \(file.url.lastPathComponent)")
            self.activeTransferProgress = nil
          }
          return
        }
      }
      await MainActor.run {
        self.activeTransferProgress = nil
        self.showToast("Sent \(files.count) file\(files.count > 1 ? "s" : "")")
        let clip = ClipItem(type: .file, fileName: files.first?.url.lastPathComponent ?? "File", rawData: nil)
        self.addRecentClip(clip)
      }
    }
  }

  private func collectFiles(from urls: [URL]) -> [(url: URL, relativePath: String, size: Int64)] {
    var result: [(url: URL, relativePath: String, size: Int64)] = []
    let fm = FileManager.default
    for url in urls {
      var isDir: ObjCBool = false
      if fm.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue {
        let baseFolder = url.deletingLastPathComponent()
        if let enumerator = fm.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey, .isDirectoryKey]) {
          while let fileURL = enumerator.nextObject() as? URL {
            var subIsDir: ObjCBool = false
            if fm.fileExists(atPath: fileURL.path, isDirectory: &subIsDir), !subIsDir.boolValue {
              let rel = fileURL.path.replacingOccurrences(of: baseFolder.path + "/", with: "")
              let size = (try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
              result.append((fileURL, rel, size))
            }
          }
        }
      } else {
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
        result.append((url, url.lastPathComponent, size))
      }
    }
    return result
  }

  private func setTransferError(_ message: String) {
    transferErrorMessage = message
    transferErrorMessageResetTask?.cancel()
    transferErrorMessageResetTask = Task { @MainActor in
      try? await Task.sleep(nanoseconds: 3_000_000_000)
      self.transferErrorMessage = nil
    }
  }

  public func triggerSyncFeedback() {
    isSyncing = true
    syncResetTask?.cancel()
    syncResetTask = Task { @MainActor in
      try? await Task.sleep(nanoseconds: 1_200_000_000)
      self.isSyncing = false
    }
  }
  public func showToast(_ message: String) {
    toastDismissTask?.cancel()
    toastMessage = message
    toastDismissTask = Task { @MainActor in
      try? await Task.sleep(nanoseconds: 2_000_000_000)
      withAnimation(.easeOut(duration: 0.25)) {
        self.toastMessage = nil
      }
    }
  }

  private func checkGitHubRelease() async throws -> (tagName: String, htmlUrl: URL, assetUrl: URL?, assetExt: String)? {
    guard let url = URL(string: "https://api.github.com/repos/Prabotics/RapiDrop/releases/latest") else { return nil }
    var request = URLRequest(url: url)
    request.setValue("RapiDrop/1.0.0", forHTTPHeaderField: "User-Agent")
    request.setValue("application/vnd.github.v3+json", forHTTPHeaderField: "Accept")
    request.timeoutInterval = 8
    if let etag = UserDefaults.standard.string(forKey: "rapidrop_update_etag") {
      request.setValue(etag, forHTTPHeaderField: "If-None-Match")
    }
    let (data, response) = try await URLSession.shared.data(for: request)
    if let http = response as? HTTPURLResponse {
      if http.statusCode == 304 || http.statusCode == 404 { return nil }
      guard (200...299).contains(http.statusCode) else {
        throw URLError(.badServerResponse)
      }
      if let newEtag = http.value(forHTTPHeaderField: "ETag") {
        UserDefaults.standard.set(newEtag, forKey: "rapidrop_update_etag")
      }
    }
    guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let tagName = json["tag_name"] as? String else { return nil }
    let defaultReleaseUrl = URL(string: "https://github.com/Prabotics/RapiDrop/releases")!
    let htmlUrl = (json["html_url"] as? String).flatMap { URL(string: $0) } ?? defaultReleaseUrl

    var assetDownloadUrl: URL? = nil
    var assetExt = "dmg"
    if let assets = json["assets"] as? [[String: Any]] {
      for asset in assets {
        if let name = asset["name"] as? String,
           let downloadUrlStr = asset["browser_download_url"] as? String,
           let downloadUrl = URL(string: downloadUrlStr) {
          if name.hasSuffix(".dmg") {
            assetDownloadUrl = downloadUrl
            assetExt = "dmg"
            break
          } else if name.hasSuffix(".zip") && assetDownloadUrl == nil {
            assetDownloadUrl = downloadUrl
            assetExt = "zip"
          }
        }
      }
    }
    return (tagName, htmlUrl, assetDownloadUrl, assetExt)
  }

  public func checkForUpdates() {
    showToast("Checking for updates...")
    Task { @MainActor in
      do {
        guard let release = try await checkGitHubRelease() else {
          showToast("RapiDrop is up to date (v1.0.0)")
          return
        }
        if isNewerVersion(release.tagName, current: "1.0.0") {
          if let downloadUrl = release.assetUrl {
            showToast("Downloading \(release.tagName)...")
            do {
              let (tempUrl, _) = try await URLSession.shared.download(from: downloadUrl)
              let downloadsDir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
              let destUrl = downloadsDir.appendingPathComponent("RapiDrop-\(release.tagName).\(release.assetExt)")
              try? FileManager.default.removeItem(at: destUrl)
              try FileManager.default.moveItem(at: tempUrl, to: destUrl)
              NSWorkspace.shared.open(destUrl)
              showToast("Update downloaded to Downloads")
            } catch {
              NSWorkspace.shared.open(release.htmlUrl)
              showToast("Update \(release.tagName) available")
            }
          } else {
            NSWorkspace.shared.open(release.htmlUrl)
            showToast("Update \(release.tagName) available")
          }
        } else {
          showToast("RapiDrop is up to date (v1.0.0)")
        }
      } catch {
        showToast("Unable to check for updates while offline")
      }
    }
  }

  public func isNewerVersion(_ latestTag: String, current: String) -> Bool {
    if latestTag.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return false }
    let cleanLatest = latestTag.trimmingCharacters(in: CharacterSet(charactersIn: "vV "))
    let cleanCurrent = current.trimmingCharacters(in: CharacterSet(charactersIn: "vV "))
    let latestParts = cleanLatest.split(separator: ".").compactMap { Int($0) }
    let currentParts = cleanCurrent.split(separator: ".").compactMap { Int($0) }
    guard !latestParts.isEmpty && !currentParts.isEmpty else { return false }
    let maxLen = max(latestParts.count, currentParts.count)
    for i in 0..<maxLen {
      let l = i < latestParts.count ? latestParts[i] : 0
      let c = i < currentParts.count ? currentParts[i] : 0
      if l > c { return true }
      if l < c { return false }
    }
    return false
  }

  public func togglePinClip(_ clip: ClipItem) {
    if pinnedClipIds.contains(clip.id) {
      pinnedClipIds.remove(clip.id)
    } else {
      pinnedClipIds.insert(clip.id)
    }
    let strings = pinnedClipIds.map { $0.uuidString }
    UserDefaults.standard.set(strings, forKey: "rapidrop_pinned_clip_ids")
  }

  public func addRecentClip(_ item: ClipItem) {
    guard showSyncHistory else { return }
    recentClips.removeAll { existing in
      guard existing.type == item.type else { return false }
      switch item.type {
      case .text, .url:
        return existing.textContent != nil && existing.textContent == item.textContent
      case .image:
        return existing.rawData != nil && existing.rawData == item.rawData
      case .file:
        return existing.fileName != nil && existing.fileName == item.fileName
      }
    }
    recentClips.insert(item, at: 0)
    if item.type == .image, let rawData = item.rawData {
      let clipId = item.id
      Task.detached(priority: .userInitiated) {
        _ = ThumbnailCache.shared.thumbnail(for: clipId, rawData: rawData)
      }
    }
    if recentClips.count > 20 {
      let pinned = recentClips.filter { pinnedClipIds.contains($0.id) }
      let unpinned = recentClips.filter { !pinnedClipIds.contains($0.id) }
      recentClips = Array((pinned + unpinned).prefix(20))
    }
  }

  public func copyRecentClip(_ item: ClipItem) {
    monitor.writeToPasteboard(item: item)
  }

  public func clearHistory() {
    recentClips.removeAll { !pinnedClipIds.contains($0.id) }
  }
  public func deleteClip(_ clip: ClipItem) {
    recentClips.removeAll { $0.id == clip.id }
    pinnedClipIds.remove(clip.id)
    let strings = pinnedClipIds.map { $0.uuidString }
    UserDefaults.standard.set(strings, forKey: "rapidrop_pinned_clip_ids")
  }
  nonisolated public static func saveMediaToDisk(item: ClipItem, folder: URL) -> URL? {
    guard let data = item.rawData else { return nil }
    do {
      try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
      let filename: String
      if let name = item.fileName, !name.isEmpty {
        let sanitized = URL(fileURLWithPath: name).lastPathComponent
        filename =
          sanitized.isEmpty ? "RapiDrop_\(Int(Date().timeIntervalSince1970 * 1000)).bin" : sanitized
      } else {
        let ext = item.type == .image ? "png" : "bin"
        filename = "RapiDrop_\(Int(Date().timeIntervalSince1970 * 1000)).\(ext)"
      }
      let targetURL = folder.appendingPathComponent(filename)
      try data.write(to: targetURL)
      return targetURL
    } catch {
      return nil
    }
  }

  nonisolated public func networkEngine(_ engine: NetworkEngine, didReceiveClip item: ClipItem) {
    Task {
      let (mode, folder) = await MainActor.run { (self.mediaDestinationMode, self.mediaFolderURL) }
      var savedURL: URL? = nil
      if (item.type == .image || item.type == .file) && (mode == .both || mode == .folderOnly) {
        savedURL = await Task.detached(priority: .utility) {
          Self.saveMediaToDisk(item: item, folder: folder)
        }.value
      }

      await MainActor.run {
        switch item.type {
        case .image, .file:
          if let name = item.fileName, savedURL != nil {
            self.showToast("Saved \(name) to Downloads")
          }
          if mode == .both || mode == .clipboardOnly {
            self.monitor.writeToPasteboard(item: item, savedFileURL: savedURL)
          }
        case .text, .url:
          self.monitor.writeToPasteboard(item: item)
        }
        self.lastReceivedClipContentHash = self.computeContentHash(for: item)
        self.addRecentClip(item)
        self.playSound("Glass")
        self.triggerSyncFeedback()
      }
    }
  }
  nonisolated public func networkEngine(
    _ engine: NetworkEngine,
    didUpdateTransferProgress transferId: String,
    fileName: String,
    bytesTransferred: Int64,
    totalBytes: Int64,
    fileIndex: Int,
    totalFiles: Int,
    isComplete: Bool
  ) {
    Task { @MainActor in
      if isComplete {
        withAnimation(.easeOut(duration: 0.25)) {
          self.activeTransferProgress = nil
        }
        self.showToast("Received \(fileName)")
      } else {
        if self.currentRxTransferId != transferId {
          self.currentRxTransferId = transferId
          self.rxSpeedTracker.reset(initialBytes: bytesTransferred)
        }
        let (shouldUpdate, speed) = self.rxSpeedTracker.update(bytes: bytesTransferred, totalBytes: totalBytes)
        if shouldUpdate {
          self.activeTransferProgress = TransferProgressInfo(
            transferId: transferId,
            fileName: fileName,
            bytesTransferred: bytesTransferred,
            totalBytes: totalBytes,
            fileIndex: fileIndex + 1,
            totalFiles: totalFiles,
            isSending: false,
            speedBytesPerSec: speed
          )
        }
      }
    }
  }
  nonisolated public func networkEngineDidCancelTransfer(_ engine: NetworkEngine, transferId: String, reason: TransferFailureReason) {
    Task { @MainActor in
      withAnimation(.easeOut(duration: 0.25)) {
        self.activeTransferProgress = nil
      }
      self.showToast(reason.rawValue)
    }
  }

  nonisolated public func networkEngine(
    _ engine: NetworkEngine, didUpdateConnectionState isConnected: Bool, peerName: String?
  ) {
    Task { @MainActor in
      let wasConnected = self.isConnected
      self.isConnected = isConnected
      self.monitor.setConnected(isConnected)
      if isConnected {
        self.reconnectAttempts = 0
        self.peerName = peerName
        CryptoEngine.saveSecureValue(key: "rapidrop_paired_pin", value: self.pairingPin)
        UserDefaults.standard.removeObject(forKey: "rapidrop_paired_pin")
        if let name = peerName {
          self.pairedPeerName = name
          UserDefaults.standard.set(name, forKey: "rapidrop_paired_peer_name")
        }
        self.selectedDeviceForPairing = nil
        self.incomingPairInvite = nil
        self.pairingTimeoutTask?.cancel()
        self.pairingTimeoutTask = nil
        if !wasConnected {
          self.playSound("Hero")
          self.triggerHapticFeedback(.alignment)
          let peer = (peerName ?? "device").trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\"", with: "").replacingOccurrences(of: "\\", with: "")
          self.showToast("Connected to \(peer)")
          if let currentClip = self.monitor.readCurrentClip() {
            self.network.sendClip(currentClip)
          }
        }
      } else {
        self.peerName = nil
        withAnimation(.easeOut(duration: 0.25)) {
          self.activeTransferProgress = nil
        }
        if let savedKeyHex = self.storedPairedKey, let keyData = CryptoEngine.hexToData(savedKeyHex), keyData.count == 32 {
          self.network.setSessionKey(SymmetricKey(data: keyData))
        } else if let savedPin = self.storedPairedPin, self.isPaired {
          self.pairingPin = savedPin
          let key = CryptoEngine.deriveKey(from: savedPin)
          self.network.setSessionKey(key)
        }
      }
    }
  }

  nonisolated public func networkEngine(
    _ engine: NetworkEngine, didReceivePairInviteFrom device: DeviceDescriptor, pin: String?,
    host: String?, port: Int
  ) {
    Task { @MainActor in
      self.incomingPairInvite = (deviceName: device.deviceName, pin: pin, host: host, port: port)
    }
  }
  nonisolated public func networkEngine(
    _ engine: NetworkEngine, didReceivePairOfferFrom fromName: String, sasCode: String
  ) {
    Task { @MainActor in
      self.pendingPairingSasCode = sasCode
    }
  }
  nonisolated public func networkEngine(
    _ engine: NetworkEngine, didReceivePairAcceptFrom fromName: String, sasCode: String, host: String?, port: Int
  ) {
    Task { @MainActor in
      self.pairingTimeoutTask?.cancel()
      self.pairingTimeoutTask = nil
      if let keyData = self.network.getSessionKeyData() {
        let hex = keyData.map { String(format: "%02x", $0) }.joined()
        CryptoEngine.saveSecureValue(key: "rapidrop_paired_session_key", value: hex)
        UserDefaults.standard.removeObject(forKey: "rapidrop_paired_session_key")
      }
      CryptoEngine.saveSecureValue(key: "rapidrop_paired_pin", value: sasCode)
      UserDefaults.standard.removeObject(forKey: "rapidrop_paired_pin")
      self.pairingPin = sasCode
      UserDefaults.standard.set(fromName, forKey: "rapidrop_paired_peer_name")
      self.pairedPeerName = fromName
      self.peerName = fromName
      self.pendingPairingSasCode = sasCode
      self.selectedDeviceForPairing = nil
      self.incomingPairInvite = nil
      if let host = host, !host.isEmpty {
        let connectPort = (port > 0 && port != Int(WireFrame.defaultClientPort)) ? UInt16(port) : WireFrame.defaultPort
        self.network.connect(to: host, port: connectPort)
      }
    }
  }
  nonisolated public func networkEngineDidFailPairing(_ engine: NetworkEngine, reason: String) {
    Task { @MainActor in
      self.pairingTimeoutTask?.cancel()
      self.pairingTimeoutTask = nil
      self.incomingPairInvite = nil
      self.selectedDeviceForPairing = nil
      self.pendingPairingSasCode = nil
      if let savedPin = self.storedPairedPin {
        self.setPin(savedPin)
      }
      if reason == "DECLINED" {
        self.playSound("Basso")
        return
      }
      if reason != "CANCELLED" {
        self.pairingErrorMessage = reason
        self.playSound("Basso")
      }
    }
  }

  nonisolated public func networkEngineDidReceiveDisconnect(_ engine: NetworkEngine) {
    Task { @MainActor in
      CryptoEngine.deleteSecureValue(key: "rapidrop_paired_pin")
      CryptoEngine.deleteSecureValue(key: "rapidrop_paired_session_key")
      UserDefaults.standard.removeObject(forKey: "rapidrop_paired_pin")
      UserDefaults.standard.removeObject(forKey: "rapidrop_paired_session_key")
      UserDefaults.standard.removeObject(forKey: "rapidrop_paired_peer_name")
      self.network.setSessionKey(nil)
      self.pairedPeerName = nil
      self.peerName = nil
      self.isConnected = false
      self.selectedDeviceForPairing = nil
      self.regeneratePin()
      self.refreshDiscovery()
      self.playSound("Blow")
    }
  }
  nonisolated public func networkEngine(
    _ engine: NetworkEngine, didReceivePrivacyModeUpdate privacyMode: Bool
  ) {
  }

  private func normalizeDeviceName(_ s: String) -> String {
    s.replacingOccurrences(of: "’", with: "'")
      .replacingOccurrences(of: "\"", with: "")
      .trimmingCharacters(in: .whitespacesAndNewlines)
      .lowercased()
  }
}
