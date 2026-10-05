import Foundation

public struct MirroredNotification: Identifiable, Sendable, Equatable {
  public let id: String
  public let packageName: String
  public let appName: String
  public let title: String
  public let text: String
  public let timestamp: Date
  public let canDismiss: Bool
  public let otpCode: String?
  public let iconBase64: String?

  public init(
    id: String,
    packageName: String,
    appName: String,
    title: String,
    text: String,
    timestamp: Date = Date(),
    canDismiss: Bool = true,
    otpCode: String? = nil,
    iconBase64: String? = nil
  ) {
    self.id = id
    self.packageName = packageName
    self.appName = appName
    self.title = title
    self.text = text
    self.timestamp = timestamp
    self.canDismiss = canDismiss
    self.otpCode = otpCode
    self.iconBase64 = iconBase64
  }
}
