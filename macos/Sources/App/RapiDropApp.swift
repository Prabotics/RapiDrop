import AppKit
import Combine
import SwiftUI
import UserNotifications

@main
struct RapiDropApp: App {
  @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

  var body: some Scene {
    Settings {
      EmptyView()
    }
  }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSPopoverDelegate,
  UNUserNotificationCenterDelegate, NSMenuDelegate
{
  private var lockFileDescriptor: Int32 = -1
  let appState = AppState()
  private var statusItem: NSStatusItem?
  private var popover: NSPopover?
  private var hostingController: NSHostingController<MenuBarView>?
  private var cancellables = Set<AnyCancellable>()
  private var lastCloseTime: TimeInterval = 0

  func applicationDidFinishLaunching(_ notification: Notification) {
    NSApp.setActivationPolicy(.accessory)
    if let iconURL = Bundle.main.url(forResource: "AppIcon", withExtension: "icns"),
       let iconImage = NSImage(contentsOf: iconURL) {
      NSApp.applicationIconImage = iconImage
    }

    let lockPath = (NSTemporaryDirectory() as NSString).appendingPathComponent("rapidrop_app.lock")
    lockFileDescriptor = open(lockPath, O_CREAT | O_RDWR, 0o666)
    if lockFileDescriptor >= 0 {
      if flock(lockFileDescriptor, LOCK_EX | LOCK_NB) != 0 {
        close(lockFileDescriptor)
        lockFileDescriptor = -1
        DistributedNotificationCenter.default().postNotificationName(
          NSNotification.Name("com.prabotics.rapidrop.showPopover"),
          object: nil,
          userInfo: nil,
          deliverImmediately: true
        )
        exit(0)
      }
    }

    NSApp.servicesProvider = self
    NSUpdateDynamicServices()

    setupStatusItem()
    setupPopover()

    if Bundle.main.bundleIdentifier != nil {
      UNUserNotificationCenter.current().delegate = self
      Task {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [
          .alert, .sound,
        ])
      }
    }
    DistributedNotificationCenter.default().addObserver(
      self,
      selector: #selector(appearanceDidChange),
      name: NSNotification.Name("AppleInterfaceThemeChangedNotification"),
      object: nil
    )
    DistributedNotificationCenter.default().addObserver(
      self,
      selector: #selector(showPopoverFromDistributedNotification),
      name: NSNotification.Name("com.prabotics.rapidrop.showPopover"),
      object: nil
    )

    NSWorkspace.shared.notificationCenter.addObserver(
      self,
      selector: #selector(systemDidWake),
      name: NSWorkspace.didWakeNotification,
      object: nil
    )
  }

  func applicationWillTerminate(_ notification: Notification) {
    NSWorkspace.shared.notificationCenter.removeObserver(self)
    DistributedNotificationCenter.default().removeObserver(self)
    if lockFileDescriptor >= 0 {
      flock(lockFileDescriptor, LOCK_UN)
      close(lockFileDescriptor)
      lockFileDescriptor = -1
    }
  }

  private func setupStatusItem() {
    let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    if let button = item.button {
      button.image = customMenuBarIcon(
        isConnected: appState.isConnected, isSyncing: appState.isSyncing)
      let dropView = StatusItemDropView(frame: button.bounds)
      dropView.autoresizingMask = [.width, .height]
      dropView.appDelegate = self
      button.addSubview(dropView)
    }
    self.statusItem = item

    Publishers.CombineLatest(appState.$isConnected, appState.$isSyncing)
      .receive(on: DispatchQueue.main)
      .sink { [weak self] isConnected, isSyncing in
        self?.statusItem?.button?.image = self?.customMenuBarIcon(
          isConnected: isConnected, isSyncing: isSyncing)
      }
      .store(in: &cancellables)

    appState.$incomingPairInvite
      .receive(on: DispatchQueue.main)
      .sink { [weak self] invite in
        guard let self, invite != nil else { return }
        self.showPopover()
        NSSound(named: "Glass")?.play()
      }
      .store(in: &cancellables)
  }

  func showPopover() {
    guard let popover = popover, let button = statusItem?.button else { return }
    if !popover.isShown {
      updatePopoverAppearance()
      appState.refreshDiscovery()
      popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
      popover.contentViewController?.view.window?.makeKey()
      NSApp.activate(ignoringOtherApps: true)
    }
  }
  @objc private func showPopoverFromDistributedNotification() {
    showPopover()
  }

  private func setupPopover() {
    let pop = NSPopover()
    pop.behavior = .transient
    pop.animates = true
    pop.delegate = self
    pop.contentSize = NSSize(width: 336, height: 500)
    let hosting = NSHostingController(rootView: MenuBarView(appState: appState))
    pop.contentViewController = hosting
    self.hostingController = hosting
    self.popover = pop
    appState.onRequestClosePopover = { [weak self] in
      self?.popover?.performClose(nil)
    }
    updatePopoverAppearance()
  }

  @objc func sendWithRapiDrop(
    _ pboard: NSPasteboard,
    userData: String,
    error: AutoreleasingUnsafeMutablePointer<NSString?>
  ) {
    let options: [NSPasteboard.ReadingOptionKey: Any] = [.urlReadingFileURLsOnly: true]
    guard
      let nsUrls = pboard.readObjects(forClasses: [NSURL.self], options: options) as? [NSURL],
      !nsUrls.isEmpty
    else {
      return
    }
    let urls = nsUrls.compactMap { $0 as URL }
    handleExternalFiles(urls)
  }

  func application(_ application: NSApplication, openFiles filenames: [String]) {
    let urls = filenames.map { URL(fileURLWithPath: $0) }
    handleExternalFiles(urls)
    application.reply(toOpenOrPrint: .success)
  }

  private func handleExternalFiles(_ urls: [URL]) {
    guard !urls.isEmpty else { return }
    showPopover()
    if appState.isConnected {
      appState.sendFiles(at: urls)
    } else {
      appState.transferErrorMessage = "Connect to a device to send files."
    }
  }
  @objc private func appearanceDidChange() {
    updatePopoverAppearance()
  }

  @objc private func systemDidWake() {
    appState.refreshDiscovery()
  }
  private func updatePopoverAppearance() {
    let isDark: Bool = {
      if let style = UserDefaults.standard.string(forKey: "AppleInterfaceStyle") {
        return style.lowercased().contains("dark")
      }
      return NSApp.effectiveAppearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
    }()
    let targetAppearance = isDark ? NSAppearance(named: .darkAqua) : NSAppearance(named: .aqua)
    popover?.appearance = targetAppearance
    hostingController?.view.appearance = targetAppearance
  }

  func popoverDidClose(_ notification: Notification) {
    lastCloseTime = ProcessInfo.processInfo.systemUptime
  }

  @objc func statusBarButtonClicked(_ sender: Any?) {
    guard let popover = popover, let button = statusItem?.button else { return }
    let now = ProcessInfo.processInfo.systemUptime
    if now - lastCloseTime < 0.25 {
      return
    }

    if popover.isShown {
      popover.performClose(sender)
    } else {
      updatePopoverAppearance()
      appState.refreshDiscovery()
      popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
      popover.contentViewController?.view.window?.makeKey()
      NSApp.activate(ignoringOtherApps: true)
    }
  }
  func statusItemRightClicked(_ button: NSButton) {
    if let popover = popover, popover.isShown {
      popover.performClose(nil)
    }

    let menu = NSMenu()
    menu.delegate = self
    menu.autoenablesItems = false

    let openItem = NSMenuItem(title: "Open RapiDrop", action: #selector(openRapiDropAction), keyEquivalent: "")
    openItem.target = self
    menu.addItem(openItem)

    menu.addItem(NSMenuItem.separator())

    let quitItem = NSMenuItem(
      title: "Quit RapiDrop",
      action: #selector(quitAppAction),
      keyEquivalent: "q"
    )
    quitItem.target = self
    menu.addItem(quitItem)

    let y = button.isFlipped ? button.bounds.height : 0
    let location = NSPoint(x: 0, y: y)
    menu.popUp(positioning: nil, at: location, in: button)
  }

  func menuWillOpen(_ menu: NSMenu) {
    statusItem?.button?.highlight(true)
  }

  func menuDidClose(_ menu: NSMenu) {
    statusItem?.button?.highlight(false)
  }

  @objc func openRapiDropAction() {
    showPopover()
  }

  @objc func quitAppAction() {
    NSApplication.shared.terminate(nil)
  }
  private func customMenuBarIcon(isConnected: Bool, isSyncing: Bool = false) -> NSImage {
    let size = NSSize(width: 20, height: 16)
    if let image = Bundle.main.image(forResource: "tray_icon") ?? NSImage(named: "tray_icon") {
      image.size = size
      image.isTemplate = true
      return image
    }
    let fallback = NSImage(systemSymbolName: isSyncing ? "arrow.triangle.2.circlepath" : "paperplane", accessibilityDescription: "RapiDrop") ?? NSImage()
    fallback.size = size
    fallback.isTemplate = true
    return fallback
  }

  nonisolated func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    willPresent notification: UNNotification,
    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) {
    completionHandler([.banner, .sound])
  }
}

final class StatusItemDropView: NSView {
  weak var appDelegate: AppDelegate?

  override init(frame: NSRect) {
    super.init(frame: frame)
    registerForDraggedTypes([.fileURL])
  }

  required init?(coder: NSCoder) {
    super.init(coder: coder)
    registerForDraggedTypes([.fileURL])
  }

  override func mouseDown(with event: NSEvent) {
    guard let button = superview as? NSButton else { return }
    button.highlight(true)
  }

  override func mouseUp(with event: NSEvent) {
    guard let button = superview as? NSButton else { return }
    button.highlight(false)
    let loc = convert(event.locationInWindow, from: nil)
    if bounds.contains(loc) {
      if event.modifierFlags.contains(.control) {
        appDelegate?.statusItemRightClicked(button)
      } else {
        appDelegate?.statusBarButtonClicked(button)
      }
    }
  }

  override func rightMouseDown(with event: NSEvent) {
    guard let button = superview as? NSButton else { return }
    button.highlight(true)
  }

  override func rightMouseUp(with event: NSEvent) {
    guard let button = superview as? NSButton else { return }
    button.highlight(false)
    let loc = convert(event.locationInWindow, from: nil)
    if bounds.contains(loc) {
      appDelegate?.statusItemRightClicked(button)
    }
  }

  override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
    guard let appDelegate, appDelegate.appState.isConnected else { return [] }
    return .copy
  }

  override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
    guard let appDelegate, appDelegate.appState.isConnected else { return false }
    guard
      let urls = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: nil)
        as? [URL],
      let first = urls.first
    else {
      return false
    }
    NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .now)
    Task { @MainActor in
      appDelegate.appState.sendFile(at: first)
    }
    return true
  }
}
