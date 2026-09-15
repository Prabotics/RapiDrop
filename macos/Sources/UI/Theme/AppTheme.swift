import SwiftUI
import AppKit

public enum AppColors {
  public static let accent = Color(red: 0.063, green: 0.725, blue: 0.506)
  public static let statusConnected = Color(red: 0.063, green: 0.725, blue: 0.506)
  public static let statusWarning = Color(red: 0.961, green: 0.620, blue: 0.043)
  public static let statusError = Color(red: 0.937, green: 0.267, blue: 0.267)
  public static let statusDisconnected = Color.secondary

  public static func surfaceBackground(_ colorScheme: ColorScheme) -> Color {
    colorScheme == .dark
      ? Color(nsColor: .windowBackgroundColor).opacity(0.2)
      : Color(nsColor: .windowBackgroundColor).opacity(0.92)
  }

  public static func surfaceCard(_ colorScheme: ColorScheme) -> Color {
    colorScheme == .dark
      ? Color.primary.opacity(0.04)
      : Color(nsColor: .controlBackgroundColor).opacity(0.85)
  }

  public static func surfaceHover(_ colorScheme: ColorScheme) -> Color {
    colorScheme == .dark
      ? Color.primary.opacity(0.08)
      : Color(nsColor: .controlBackgroundColor)
  }

  public static func surfaceBorder(_ colorScheme: ColorScheme) -> Color {
    colorScheme == .dark
      ? Color.primary.opacity(0.08)
      : Color(nsColor: .separatorColor).opacity(0.5)
  }

  public static func containerSubtle(_ colorScheme: ColorScheme) -> Color {
    colorScheme == .dark
      ? Color.primary.opacity(0.03)
      : Color(nsColor: .controlBackgroundColor).opacity(0.65)
  }

  public static let textPrimary = Color.primary
  public static let textSecondary = Color.secondary
  public static let textMuted = Color.secondary.opacity(0.75)
}

public enum AppTypography {
  public static let screenTitle = Font.system(size: 15, weight: .bold)
  public static let sectionTitle = Font.system(size: 11, weight: .semibold)
  public static let deviceName = Font.system(size: 13, weight: .semibold)
  public static let actionTitle = Font.system(size: 12, weight: .medium)
  public static let actionSubtitle = Font.system(size: 10.5, weight: .regular)
  public static let bodyPrimary = Font.system(size: 12, weight: .regular)
  public static let bodySecondary = Font.system(size: 11, weight: .regular)
  public static let metadata = Font.system(size: 10, weight: .regular)
  public static let statusBadge = Font.system(size: 11, weight: .medium)
  public static let codeSnippet = Font.system(size: 11, weight: .regular, design: .monospaced)
}

public enum AppSpacing {
  public static let spacing2: CGFloat = 2
  public static let spacing4: CGFloat = 4
  public static let spacing6: CGFloat = 6
  public static let spacing8: CGFloat = 8
  public static let spacing10: CGFloat = 10
  public static let spacing12: CGFloat = 12
  public static let spacing14: CGFloat = 14
  public static let spacing16: CGFloat = 16
  public static let spacing20: CGFloat = 20
}

public enum AppRadius {
  public static let small: CGFloat = 4
  public static let medium: CGFloat = 6
  public static let large: CGFloat = 8
  public static let card: CGFloat = 10
  public static let full: CGFloat = 999
}

public enum AppLayout {
  public static let popoverWidth: CGFloat = 336
  public static let minButtonHeight: CGFloat = 28
  public static let minRowHeight: CGFloat = 36
  public static let iconSmall: CGFloat = 14
  public static let iconMedium: CGFloat = 18
  public static let iconLarge: CGFloat = 24
}
