import AppKit
import SwiftUI

public struct WelcomeView: View {
  @Environment(\.colorScheme) private var colorScheme
  private let onContinue: () -> Void

  public init(onContinue: @escaping () -> Void) {
    self.onContinue = onContinue
  }

  public var body: some View {
    ZStack {
      (colorScheme == .dark
        ? Color(red: 0.13, green: 0.13, blue: 0.14)
        : Color(red: 0.98, green: 0.98, blue: 0.99))
        .ignoresSafeArea()

      VStack(spacing: 0) {
        appIconHeader
          .padding(.top, 36)

        Text("Welcome to RapiDrop")
          .font(.system(size: 26, weight: .bold))
          .foregroundColor(AppColors.textPrimary)
          .padding(.top, 14)

        VStack(spacing: 24) {
          featureRow(
            icon: "wifi",
            iconColor: AppColors.accent,
            title: "Local Wi-Fi Only",
            description: "Direct encrypted transfer. No accounts, cloud storage, or tracking."
          )

          featureRow(
            icon: "doc.on.clipboard",
            iconColor: Color(red: 0.2, green: 0.55, blue: 0.95),
            title: "Instant Clipboard",
            description: "Copy on your Mac, paste on your phone. Supports text, links, and photos."
          )

          featureRow(
            icon: "arrow.down.doc",
            iconColor: Color(red: 0.95, green: 0.55, blue: 0.15),
            title: "File Transfers",
            description: "Drop files into the menu bar. Incoming items land directly in Downloads."
          )
        }
        .padding(.horizontal, 44)
        .padding(.top, 28)

        Spacer(minLength: 20)

        VStack(spacing: 12) {
          Button(action: onContinue) {
            Text("Continue")
              .font(.system(size: 13, weight: .semibold))
              .frame(width: 140)
          }
          .buttonStyle(.borderedProminent)
          .controlSize(.large)
          .tint(AppColors.accent)
          .keyboardShortcut(.defaultAction)

          Text("Access anytime from your menu bar")
            .font(.system(size: 11, weight: .regular))
            .foregroundColor(AppColors.textSecondary)
        }
        .padding(.bottom, 28)
      }

      Button("") {
        onContinue()
      }
      .keyboardShortcut(.cancelAction)
      .opacity(0)
      .frame(width: 0, height: 0)
    }
    .frame(width: 520, height: 460)
  }

  private var appIconHeader: some View {
    Group {
      if let iconImage = NSApp.applicationIconImage {
        Image(nsImage: iconImage)
          .resizable()
          .aspectRatio(contentMode: .fit)
      } else if let iconURL = Bundle.main.url(forResource: "AppIcon", withExtension: "icns"),
        let img = NSImage(contentsOf: iconURL)
      {
        Image(nsImage: img)
          .resizable()
          .aspectRatio(contentMode: .fit)
      } else if let logoURL = Bundle.main.url(forResource: "popover_logo", withExtension: "png"),
        let img = NSImage(contentsOf: logoURL)
      {
        Image(nsImage: img)
          .resizable()
          .aspectRatio(contentMode: .fit)
      } else {
        Image(systemName: "paperplane.circle.fill")
          .resizable()
          .aspectRatio(contentMode: .fit)
          .foregroundColor(AppColors.accent)
      }
    }
    .frame(width: 64, height: 64)
    .shadow(color: Color.black.opacity(colorScheme == .dark ? 0.35 : 0.12), radius: 5, x: 0, y: 2)
  }

  private func featureRow(
    icon: String,
    iconColor: Color,
    title: String,
    description: String
  ) -> some View {
    HStack(alignment: .top, spacing: 18) {
      Image(systemName: icon)
        .font(.system(size: 26, weight: .regular))
        .foregroundColor(iconColor)
        .frame(width: 36, height: 36, alignment: .center)
        .offset(y: -3)

      VStack(alignment: .leading, spacing: 3) {
        Text(title)
          .font(.system(size: 14.5, weight: .semibold))
          .foregroundColor(AppColors.textPrimary)

        Text(description)
          .font(.system(size: 12.5, weight: .regular))
          .foregroundColor(AppColors.textSecondary)
          .fixedSize(horizontal: false, vertical: true)
          .lineSpacing(2)
      }

      Spacer(minLength: 0)
    }
  }
}

@MainActor
public final class WelcomeWindowController: NSObject, NSWindowDelegate {
  public static let shared = WelcomeWindowController()

  private var window: NSWindow?
  private var onContinueCallback: (() -> Void)?

  public func show(onContinue: (() -> Void)? = nil) {
    self.onContinueCallback = onContinue

    if let win = window {
      win.makeKeyAndOrderFront(nil)
      NSApp.activate(ignoringOtherApps: true)
      return
    }

    let welcomeView = WelcomeView { [weak self] in
      self?.dismiss(completed: true)
    }

    let hostingView = NSHostingView(rootView: welcomeView)
    hostingView.frame = NSRect(x: 0, y: 0, width: 520, height: 460)
    hostingView.autoresizingMask = [.width, .height]
    let newWindow = NSWindow(
      contentRect: NSRect(x: 0, y: 0, width: 520, height: 460),
      styleMask: [.titled, .fullSizeContentView],
      backing: .buffered,
      defer: false
    )
    newWindow.isOpaque = true
    newWindow.backgroundColor = NSColor(deviceWhite: 0.13, alpha: 1.0)
    newWindow.titleVisibility = .hidden
    newWindow.titlebarAppearsTransparent = true
    newWindow.isMovableByWindowBackground = true
    newWindow.isReleasedWhenClosed = false
    newWindow.delegate = self
    newWindow.contentView = hostingView
    newWindow.center()
    newWindow.level = .floating
    newWindow.hasShadow = true
    newWindow.standardWindowButton(.closeButton)?.isHidden = true
    newWindow.standardWindowButton(.miniaturizeButton)?.isHidden = true
    newWindow.standardWindowButton(.zoomButton)?.isHidden = true

    self.window = newWindow
    newWindow.makeKeyAndOrderFront(nil)
    NSApp.activate(ignoringOtherApps: true)
  }

  public func windowWillClose(_ notification: Notification) {
    dismiss(completed: true)
  }

  private func dismiss(completed: Bool) {
    guard let win = window else { return }
    window = nil
    win.delegate = nil
    win.orderOut(nil)
    if completed {
      let cb = onContinueCallback
      onContinueCallback = nil
      cb?()
    }
  }
}
