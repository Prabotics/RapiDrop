import AppKit
import Foundation

public enum ClipContentType: String, Sendable, Codable {
  case text
  case url
  case image
  case file
}

public struct ClipItem: Sendable, Identifiable, Equatable {
  public let id: UUID
  public let type: ClipContentType
  public let textContent: String?
  public let fileName: String?
  public let rawData: Data?
  public let timestamp: Date

  public init(
    id: UUID = UUID(),
    type: ClipContentType,
    textContent: String? = nil,
    fileName: String? = nil,
    rawData: Data? = nil,
    timestamp: Date = Date()
  ) {
    self.id = id
    self.type = type
    self.textContent = textContent
    self.fileName = fileName
    self.rawData = rawData
    self.timestamp = timestamp
  }

  public var previewText: String {
    switch type {
    case .text:
      return textContent ?? ""
    case .url:
      return textContent ?? ""
    case .image:
      let bytes = rawData?.count ?? 0
      return fileName ?? "Image (\(bytes / 1024) KB)"
    case .file:
      let bytes = rawData?.count ?? 0
      return "\(fileName ?? "File") (\(bytes / 1024) KB)"
    }
  }
}
