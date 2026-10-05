import SwiftUI
import UniformTypeIdentifiers
import ImageIO

struct InteractiveCardButtonStyle: ButtonStyle {
  func makeBody(configuration: Configuration) -> some View {
    configuration.label
      .scaleEffect(configuration.isPressed ? 0.98 : 1.0)
      .opacity(configuration.isPressed ? 0.92 : 1.0)
      .animation(.spring(response: 0.22, dampingFraction: 0.7), value: configuration.isPressed)
  }
}

struct InteractivePillButtonStyle: ButtonStyle {
  func makeBody(configuration: Configuration) -> some View {
    configuration.label
      .scaleEffect(configuration.isPressed ? 0.95 : 1.0)
      .opacity(configuration.isPressed ? 0.88 : 1.0)
      .animation(.spring(response: 0.22, dampingFraction: 0.7), value: configuration.isPressed)
  }
}

public struct MenuBarView: View {
  @Environment(\.colorScheme) private var colorScheme
  @ObservedObject var appState: AppState
  @State private var copiedClipId: UUID?
  @State private var hoveredClipId: UUID?
  @State private var inspectingClip: ClipItem? = nil
  @State private var isClipboardSynced: Bool = false
  @State private var searchText: String = ""
  @State private var selectedFilter: ClipContentType? = nil
  @State private var isDropTargeted: Bool = false
  @State private var isFileSent: Bool = false
  @State private var syncToastMessage: String? = nil
  @State private var syncToastDismissTask: Task<Void, Never>? = nil
  @State private var showClearConfirmation: Bool = false
  @State private var isViewingHistory: Bool = false
  @State private var isViewingSettings: Bool = false
  @State private var isPairingPulsing: Bool = false
  @Namespace private var storageSegmentNamespace
  public init(appState: AppState) {
    self.appState = appState
  }
  public var body: some View {
    ZStack(alignment: .topLeading) {
      if let selectedDevice = appState.selectedDeviceForPairing {
        pairingPinCard(device: selectedDevice)
          .transition(
            .asymmetric(
              insertion: .move(edge: .trailing).combined(with: .opacity),
              removal: .move(edge: .trailing).combined(with: .opacity)
            )
          )
      } else if let invite = appState.incomingPairInvite {
        incomingPairInviteCard(deviceName: invite.deviceName)
          .transition(
            .asymmetric(
              insertion: .move(edge: .trailing).combined(with: .opacity),
              removal: .move(edge: .trailing).combined(with: .opacity)
            )
          )
      } else if isViewingSettings {
        settingsSection
          .transition(
            .asymmetric(
              insertion: .move(edge: .trailing).combined(with: .opacity),
              removal: .move(edge: .trailing).combined(with: .opacity)
            )
          )
      } else if isViewingHistory {
        VStack(alignment: .leading, spacing: 12) {
          HStack {
            Button(action: {
              withAnimation(.spring(response: 0.35, dampingFraction: 0.84)) {
                isViewingHistory = false
              }
            }) {
              HStack(spacing: 4) {
                Image(systemName: "chevron.backward")
                  .font(.system(size: 11, weight: .semibold))
                Text("Back")
                  .font(AppTypography.bodySecondary.weight(.medium))
              }
              .foregroundColor(AppColors.textSecondary)
            }
            .accessibilityLabel("Back to Paired Device")

            Spacer()

            Text("Sync History")
              .font(AppTypography.sectionTitle)
              .foregroundColor(AppColors.textPrimary)

            Spacer()

            Button(action: { showClearConfirmation = true }) {
              Image(systemName: "trash")
                .font(.system(size: 11))
                .foregroundColor(AppColors.textSecondary)
            }
            .buttonStyle(InteractivePillButtonStyle())
            .accessibilityLabel("Clear all clips")
          }
          Divider()
          recentHistorySection
        }
        .transition(
          .asymmetric(
            insertion: .move(edge: .trailing).combined(with: .opacity),
            removal: .move(edge: .trailing).combined(with: .opacity)
          )
        )
      } else {
        VStack(alignment: .leading, spacing: 12) {
          headerSection

          if let toast = appState.toastMessage ?? syncToastMessage {
            syncToastBanner(toast)
              .transition(.opacity.combined(with: .move(edge: .top)))
          }

          if let errorMsg = appState.transferErrorMessage {
            transferErrorBanner(errorMsg)
              .transition(.opacity.combined(with: .move(edge: .top)))
          }
          if let progress = appState.activeTransferProgress {
            transferProgressCard(progress)
              .transition(.opacity.combined(with: .scale(scale: 0.98, anchor: .top)))
          }
          Group {
            if appState.isPaired {
              pairedDeviceCockpitSection
            } else {
              discoveredDevicesSection
            }
          }
          .transition(.opacity.combined(with: .scale(scale: 0.99, anchor: .top)))
        }
        .transition(
          .asymmetric(
            insertion: .move(edge: .leading).combined(with: .opacity),
            removal: .move(edge: .leading).combined(with: .opacity)
          )
        )
      }
    }
    .padding(AppSpacing.spacing12)
    .frame(width: AppLayout.popoverWidth)
    .background(AppColors.surfaceBackground(colorScheme))
    .onDrop(of: [.fileURL], isTargeted: $isDropTargeted) { providers in
      let accumulator = URLAccumulator()
      let group = DispatchGroup()
      for provider in providers {
        group.enter()
        _ = provider.loadObject(ofClass: URL.self) { url, _ in
          if let url {
            accumulator.append(url)
          }
          group.leave()
        }
      }
      group.notify(queue: .main) {
        let urls = accumulator.get()
        if !urls.isEmpty {
          appState.triggerHapticFeedback(.alignment)
          appState.sendFiles(at: urls)
        }
      }
      return true
    }
    .onExitCommand {
      appState.onRequestClosePopover?()
    }
    .background(
      Button(action: {
        if appState.isConnected {
          if appState.sendClipboardNow() {
            appState.triggerHapticFeedback(.alignment)
            withAnimation(.spring(response: 0.3, dampingFraction: 0.75)) {
              isClipboardSynced = true
            }
            Task {
              try? await Task.sleep(nanoseconds: 1_800_000_000)
              await MainActor.run {
                withAnimation {
                  isClipboardSynced = false
                }
              }
            }
          }
        }
      }) {
        EmptyView()
      }
      .keyboardShortcut("v", modifiers: .command)
      .opacity(0)
      .frame(width: 0, height: 0)
    )
    .overlay(
      Group {
        if isDropTargeted {
          dropOverlay
        }
      }
    )
    .animation(
      .spring(response: 0.35, dampingFraction: 0.8), value: appState.incomingPairInvite != nil
    )
    .animation(
      .spring(response: 0.35, dampingFraction: 0.8), value: appState.selectedDeviceForPairing
    )
    .animation(.spring(response: 0.35, dampingFraction: 0.8), value: appState.isConnected)
    .animation(.spring(response: 0.35, dampingFraction: 0.8), value: appState.isPaired)
    .animation(.spring(response: 0.32, dampingFraction: 0.82), value: appState.mediaDestinationMode)
    .animation(
      .spring(response: 0.32, dampingFraction: 0.82),
      value: appState.unpairedDiscoveredDevices
    )
    .animation(.spring(response: 0.3, dampingFraction: 0.8), value: appState.transferErrorMessage)
    .animation(.spring(response: 0.3, dampingFraction: 0.8), value: appState.toastMessage)
    .animation(.spring(response: 0.3, dampingFraction: 0.8), value: syncToastMessage)
    .animation(.spring(response: 0.35, dampingFraction: 0.75), value: isFileSent)
    .animation(.spring(response: 0.35, dampingFraction: 0.75), value: isClipboardSynced)
    .animation(.spring(response: 0.32, dampingFraction: 0.82), value: inspectingClip != nil)
    .animation(.spring(response: 0.32, dampingFraction: 0.8), value: appState.activeTransferProgress != nil)
    .onChange(of: appState.isSyncing) { _, isSyncing in
      syncToastDismissTask?.cancel()
      if isSyncing {
        let peer = appState.peerName ?? "device"
        syncToastMessage = "Synced with \(friendlyDeviceName(for: peer))"
      } else {
        syncToastDismissTask = Task { @MainActor in
          try? await Task.sleep(nanoseconds: 1_200_000_000)
          withAnimation(.easeOut(duration: 0.25)) {
            syncToastMessage = nil
          }
        }
      }
    }
    .confirmationDialog("Clear all recent clips?", isPresented: $showClearConfirmation, titleVisibility: .visible) {
      Button("Clear All", role: .destructive) {
        inspectingClip = nil
        appState.clearHistory()
      }
      Button("Cancel", role: .cancel) {}
    }
  }
  @ViewBuilder
  private func syncToastBanner(_ message: String) -> some View {
    HStack(spacing: AppSpacing.spacing6) {
      Circle()
        .fill(AppColors.statusConnected)
        .frame(width: 6, height: 6)
      Text(message)
        .font(AppTypography.bodySecondary)
        .foregroundColor(AppColors.statusConnected)
      Spacer()
    }
    .padding(.horizontal, AppSpacing.spacing10)
    .padding(.vertical, 5)
    .background(
      RoundedRectangle(cornerRadius: AppRadius.large)
        .fill(AppColors.statusConnected.opacity(colorScheme == .dark ? 0.12 : 0.08))
        .overlay(
          RoundedRectangle(cornerRadius: AppRadius.large)
            .stroke(AppColors.statusConnected.opacity(0.2), lineWidth: 1)
        )
    )
    .transition(.opacity.combined(with: .move(edge: .top)))
  }

  private func transferErrorBanner(_ message: String) -> some View {
    HStack(spacing: AppSpacing.spacing6) {
      Image(systemName: "exclamationmark.triangle.fill")
        .font(.system(size: 11))
        .foregroundColor(AppColors.statusWarning)
      Text(message)
        .font(AppTypography.bodySecondary)
        .foregroundColor(AppColors.textPrimary)
      Spacer()
      Button(action: {
        appState.transferErrorMessage = nil
      }) {
        Image(systemName: "xmark")
          .font(.system(size: 9, weight: .bold))
          .foregroundColor(AppColors.textSecondary)
      }
      .buttonStyle(.plain)
    }
    .padding(.horizontal, AppSpacing.spacing10)
    .padding(.vertical, 6)
    .background(
      RoundedRectangle(cornerRadius: AppRadius.large)
        .fill(AppColors.statusWarning.opacity(colorScheme == .dark ? 0.15 : 0.12))
        .overlay(
          RoundedRectangle(cornerRadius: AppRadius.large)
            .stroke(AppColors.statusWarning.opacity(0.3), lineWidth: 1)
        )
    )
    .transition(.opacity.combined(with: .move(edge: .top)))
  }

  private var dropOverlay: some View {
    ZStack {
      RoundedRectangle(cornerRadius: 12)
        .fill(colorScheme == .dark ? Color.black.opacity(0.88) : Color.white.opacity(0.92))
      RoundedRectangle(cornerRadius: 12)
        .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 2, dash: [6]))

      VStack(spacing: 8) {
        Image(systemName: "arrow.down.doc.fill")
          .font(.system(size: 28, weight: .semibold))
          .foregroundColor(.accentColor)
        Text(
          appState.isConnected
            ? "Drop to send to \(appState.peerName ?? "device")" : "Connect a device to send files"
        )
        .font(.system(size: 12, weight: .semibold))
        .foregroundColor(.primary)
        .multilineTextAlignment(.center)
        .padding(.horizontal, 16)
      }
    }
    .padding(6)
    .animation(.easeInOut(duration: 0.15), value: isDropTargeted)
  }
  private var headerSection: some View {
    HStack(alignment: .center, spacing: 8) {
      brandmarkLogo(size: 20)
      Text("RapiDrop")
        .font(.system(size: 15, weight: .bold))
        .foregroundColor(.primary)

      Spacer()

      Button(action: {
        withAnimation(.spring(response: 0.30, dampingFraction: 0.86)) {
          isViewingSettings = true
        }
      }) {
        Image(systemName: "gearshape")
          .font(.system(size: 13, weight: .medium))
          .foregroundColor(AppColors.textSecondary)
          .frame(width: 26, height: 26)
          .background(
            Circle()
              .fill(AppColors.surfaceHover(colorScheme))
          )
      }
      .buttonStyle(InteractivePillButtonStyle())
      .accessibilityLabel("Settings")
    }
  }
  private var settingsSection: some View {
    VStack(alignment: .leading, spacing: AppSpacing.spacing10) {
      HStack {
        Button(action: {
          withAnimation(.spring(response: 0.30, dampingFraction: 0.86)) {
            isViewingSettings = false
          }
        }) {
          HStack(spacing: 4) {
            Image(systemName: "chevron.backward")
              .font(.system(size: 11, weight: .semibold))
            Text("Back")
              .font(AppTypography.bodySecondary.weight(.medium))
          }
          .foregroundColor(AppColors.textSecondary)
          .padding(.horizontal, 8)
          .frame(height: 26)
          .background(
            Capsule()
              .fill(AppColors.surfaceHover(colorScheme))
          )
        }
        .buttonStyle(InteractivePillButtonStyle())
        .accessibilityLabel("Back to Devices")

        Spacer()

        Text("Settings")
          .font(AppTypography.sectionTitle)
          .foregroundColor(AppColors.textPrimary)

        Spacer()

        Color.clear
          .frame(width: 48, height: 26)
      }
      .padding(.bottom, 2)

      VStack(alignment: .leading, spacing: 5) {
        Text("GENERAL")
          .font(.system(size: 10, weight: .semibold))
          .foregroundColor(AppColors.textMuted)
          .padding(.horizontal, AppSpacing.spacing4)

        VStack(spacing: 0) {
          HStack {
            Text("Clip History")
              .font(AppTypography.bodySecondary.weight(.medium))
              .foregroundColor(AppColors.textPrimary)

            Spacer()

            Toggle("", isOn: $appState.showSyncHistory)
              .labelsHidden()
              .toggleStyle(.switch)
              .controlSize(.small)
              .tint(AppColors.accent)
          }
          .padding(.horizontal, AppSpacing.spacing12)
          .frame(height: 38)

          Rectangle()
            .fill(AppColors.surfaceBorder(colorScheme))
            .frame(height: 0.5)
            .padding(.leading, AppSpacing.spacing12)

          HStack {
            Text("Sound Effects")
              .font(AppTypography.bodySecondary.weight(.medium))
              .foregroundColor(AppColors.textPrimary)
            Spacer()

            Toggle(
              "",
              isOn: Binding(
                get: { appState.isSoundEnabled },
                set: { appState.setSoundEnabled($0) }
              )
            )
            .labelsHidden()
            .toggleStyle(.switch)
            .controlSize(.small)
            .tint(AppColors.accent)
          }
          .padding(.horizontal, AppSpacing.spacing12)
          .frame(height: 38)

          if #available(macOS 13.0, *) {
            Rectangle()
              .fill(AppColors.surfaceBorder(colorScheme))
              .frame(height: 0.5)
              .padding(.leading, AppSpacing.spacing12)

            HStack {
              Text("Open at Login")
                .font(AppTypography.bodySecondary.weight(.medium))
                .foregroundColor(AppColors.textPrimary)
              Spacer()

              Toggle(
                "",
                isOn: Binding(
                  get: { appState.isLaunchAtLogin },
                  set: { _ in appState.toggleLaunchAtLogin() }
                )
              )
              .labelsHidden()
              .toggleStyle(.switch)
              .controlSize(.small)
              .tint(AppColors.accent)
            }
            .padding(.horizontal, AppSpacing.spacing12)
            .frame(height: 38)
          }
        }
        .background(
          RoundedRectangle(cornerRadius: AppRadius.card)
            .fill(AppColors.surfaceCard(colorScheme))
        )
        .overlay(
          RoundedRectangle(cornerRadius: AppRadius.card)
            .stroke(AppColors.surfaceBorder(colorScheme), lineWidth: 0.5)
        )
      }

      VStack(alignment: .leading, spacing: 5) {
        Text("TRANSFERS")
          .font(.system(size: 10, weight: .semibold))
          .foregroundColor(AppColors.textMuted)
          .padding(.horizontal, AppSpacing.spacing4)

        VStack(spacing: 0) {
          HStack {
            Text("Save Media To")
              .font(AppTypography.bodySecondary.weight(.medium))
              .foregroundColor(AppColors.textPrimary)
            Spacer()

            storageSegmentedPicker
          }
          .padding(.horizontal, AppSpacing.spacing12)
          .frame(height: 38)

          Rectangle()
            .fill(AppColors.surfaceBorder(colorScheme))
            .frame(height: 0.5)
            .padding(.leading, AppSpacing.spacing12)

          HStack(spacing: 8) {
            Image(systemName: "folder.fill")
              .font(.system(size: 13))
              .foregroundColor(AppColors.textSecondary)
            Text((appState.mediaFolderURL.path as NSString).abbreviatingWithTildeInPath)
              .font(AppTypography.bodySecondary.weight(.medium))
              .foregroundColor(appState.mediaDestinationMode == .clipboardOnly ? AppColors.textSecondary : AppColors.textPrimary)
              .lineLimit(1)
              .truncationMode(.middle)
              .help(appState.mediaFolderURL.path)

            Spacer()

            HStack(spacing: 4) {
              Button(action: {
                let folder = appState.mediaFolderURL
                try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                NSWorkspace.shared.open(folder)
              }) {
                Text("Open")
                  .font(.system(size: 9.5, weight: .medium))
                  .foregroundColor(AppColors.textSecondary)
                  .padding(.horizontal, 6)
                  .frame(height: 20)
                  .background(
                    RoundedRectangle(cornerRadius: AppRadius.small)
                      .fill(AppColors.surfaceHover(colorScheme))
                  )
              }
              .buttonStyle(InteractivePillButtonStyle())
              .help("Open in Finder")

              Button(action: { selectCustomFolder() }) {
                Text("Change")
                  .font(.system(size: 9.5, weight: .medium))
                  .foregroundColor(AppColors.textSecondary)
                  .padding(.horizontal, 6)
                  .frame(height: 20)
                  .background(
                    RoundedRectangle(cornerRadius: AppRadius.small)
                      .fill(AppColors.surfaceHover(colorScheme))
                  )
              }
              .buttonStyle(InteractivePillButtonStyle())
              .help("Change download folder")
            }
          }
          .padding(.horizontal, AppSpacing.spacing12)
          .frame(height: 38)
          .opacity(appState.mediaDestinationMode == .clipboardOnly ? 0.45 : 1.0)
          .disabled(appState.mediaDestinationMode == .clipboardOnly)
        }
        .background(
          RoundedRectangle(cornerRadius: AppRadius.card)
            .fill(AppColors.surfaceCard(colorScheme))
        )
        .overlay(
          RoundedRectangle(cornerRadius: AppRadius.card)
            .stroke(AppColors.surfaceBorder(colorScheme), lineWidth: 0.5)
        )
      }

      VStack(alignment: .leading, spacing: 5) {
        Text("ABOUT")
          .font(.system(size: 10, weight: .semibold))
          .foregroundColor(AppColors.textMuted)
          .padding(.horizontal, AppSpacing.spacing4)

        VStack(spacing: 0) {
          HStack {
            Text("Version \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0")")
              .font(AppTypography.bodySecondary.weight(.medium))
              .foregroundColor(AppColors.textPrimary)

            Spacer()

            Button(action: { appState.checkForUpdates() }) {
              HStack(spacing: 4) {
                Image(systemName: "arrow.triangle.2.circlepath")
                  .font(.system(size: 9, weight: .semibold))
                Text("Check for Updates")
                  .font(.system(size: 10, weight: .medium))
              }
              .foregroundColor(AppColors.textSecondary)
              .padding(.horizontal, 8)
              .frame(height: 24)
              .background(
                Capsule()
                  .fill(AppColors.surfaceHover(colorScheme))
              )
            }
            .buttonStyle(InteractivePillButtonStyle())
          }
          .padding(.horizontal, AppSpacing.spacing12)
          .frame(height: 38)

          Rectangle()
            .fill(AppColors.surfaceBorder(colorScheme))
            .frame(height: 0.5)
            .padding(.leading, AppSpacing.spacing12)
          Button(action: {
            if let url = URL(string: "https://github.com/Prabotics/RapiDrop/issues") {
              NSWorkspace.shared.open(url)
            }
          }) {
            HStack {
              Text("Report Issue")
                .font(AppTypography.bodySecondary.weight(.medium))
                .foregroundColor(AppColors.textPrimary)

              Spacer()

              Image(systemName: "arrow.up.forward")
                .font(.system(size: 9, weight: .semibold))
                .foregroundColor(AppColors.textMuted)
            }
            .contentShape(Rectangle())
            .padding(.horizontal, AppSpacing.spacing12)
            .frame(height: 38)
          }
          .buttonStyle(InteractiveCardButtonStyle())
        }
        .background(
          RoundedRectangle(cornerRadius: AppRadius.card)
            .fill(AppColors.surfaceCard(colorScheme))
        )
        .overlay(
          RoundedRectangle(cornerRadius: AppRadius.card)
            .stroke(AppColors.surfaceBorder(colorScheme), lineWidth: 0.5)
        )
      }

      Button(action: {
        NSApplication.shared.terminate(nil)
      }) {
        HStack {
          Spacer()
          Text("Quit RapiDrop")
            .font(AppTypography.bodySecondary.weight(.medium))
            .foregroundColor(AppColors.textSecondary)
          Spacer()
        }
        .frame(height: 32)
        .background(
          RoundedRectangle(cornerRadius: AppRadius.card)
            .fill(AppColors.surfaceHover(colorScheme))
        )
      }
      .buttonStyle(InteractivePillButtonStyle())
      .keyboardShortcut("q", modifiers: .command)
    }
  }

  private var storageSegmentedPicker: some View {
    let modes: [(MediaDestinationMode, String)] = [
      (.both, "Both"),
      (.folderOnly, "Folder"),
      (.clipboardOnly, "Clip")
    ]

    return HStack(spacing: 0) {
      ForEach(modes, id: \.0) { mode, label in
        Button(action: {
          withAnimation(.spring(response: 0.24, dampingFraction: 0.78)) {
            appState.mediaDestinationMode = mode
          }
        }) {
          Text(label)
            .font(.system(size: 10, weight: appState.mediaDestinationMode == mode ? .semibold : .medium))
            .foregroundColor(appState.mediaDestinationMode == mode ? AppColors.textPrimary : AppColors.textMuted)
            .frame(maxWidth: .infinity)
            .frame(height: 18)
            .contentShape(Rectangle())
            .background {
              if appState.mediaDestinationMode == mode {
                RoundedRectangle(cornerRadius: AppRadius.small)
                  .fill(colorScheme == .dark ? Color.white.opacity(0.18) : Color.white)
                  .shadow(color: Color.black.opacity(colorScheme == .dark ? 0.3 : 0.12), radius: 1, x: 0, y: 0.5)
                  .matchedGeometryEffect(id: "STORAGE_SEGMENT_INDICATOR", in: storageSegmentNamespace)
              }
            }
        }
        .buttonStyle(.plain)
      }
    }
    .padding(2)
    .frame(width: 160, height: 22)
    .background(
      RoundedRectangle(cornerRadius: AppRadius.medium)
        .fill(colorScheme == .dark ? Color.white.opacity(0.08) : Color.black.opacity(0.06))
    )
  }
  private func brandmarkLogo(size: CGFloat = 20) -> some View {
    Group {
      let nsImg: NSImage? = NSImage(named: "popover_logo") ?? {
        if let url = Bundle.main.url(forResource: "popover_logo", withExtension: "png") {
          return NSImage(contentsOf: url)
        }
        return nil
      }()
      if let image = nsImg {
        Image(nsImage: image)
          .resizable()
          .interpolation(.high)
          .aspectRatio(contentMode: .fit)
          .frame(width: size, height: size)
      } else {
        Image(systemName: "paperplane.fill")
          .resizable()
          .aspectRatio(contentMode: .fit)
          .foregroundColor(AppColors.accent)
          .frame(width: size, height: size)
      }
    }
  }

  private func incomingPairInviteCard(deviceName: String) -> some View {
    VStack(spacing: AppSpacing.spacing10) {
      VStack(spacing: AppSpacing.spacing6) {
        ZStack {
          Circle()
            .stroke(AppColors.accent.opacity(isPairingPulsing ? 0.0 : 0.4), lineWidth: 1.5)
            .scaleEffect(isPairingPulsing ? 1.4 : 0.95)
          Circle()
            .fill(AppColors.accent.opacity(colorScheme == .dark ? 0.14 : 0.08))
            .frame(width: 40, height: 40)
            .overlay(
              Circle()
                .stroke(AppColors.accent.opacity(0.25), lineWidth: 1)
            )
          Image(systemName: deviceIcon(for: friendlyDeviceName(for: deviceName)))
            .font(.system(size: 18, weight: .medium))
            .foregroundColor(AppColors.accent)
        }
        .frame(width: 44, height: 44)

        VStack(spacing: 2) {
          Text(friendlyDeviceName(for: deviceName))
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(AppColors.textPrimary)

          Text("Wants to connect with this Mac")
            .font(AppTypography.bodySecondary)
            .foregroundColor(AppColors.textSecondary)
        }
      }

      if let pin = appState.incomingPairInvite?.pin, !pin.isEmpty {
        pinCodeView(pin: pin)
          .transition(
            .asymmetric(
              insertion: .opacity.combined(with: .scale(scale: 0.92)),
              removal: .opacity
            )
          )
      }

      HStack(spacing: AppSpacing.spacing10) {
        Button(action: {
          withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
            appState.declinePairInvite()
          }
        }) {
          Text("Decline")
            .font(.system(size: 12, weight: .medium))
            .foregroundColor(AppColors.textPrimary)
            .frame(width: 100, height: 28)
            .background(
              Capsule()
                .fill(colorScheme == .dark ? Color.white.opacity(0.08) : Color.black.opacity(0.05))
                .overlay(
                  Capsule()
                    .stroke(AppColors.surfaceBorder(colorScheme), lineWidth: 0.5)
                )
            )
        }
        .buttonStyle(InteractivePillButtonStyle())

        Button(action: {
          withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
            appState.acceptPairInvite()
          }
        }) {
          Text("Accept")
            .font(.system(size: 12, weight: .semibold))
            .foregroundColor(.white)
            .frame(width: 100, height: 28)
            .background(
              Capsule()
                .fill(AppColors.accent)
            )
        }
        .buttonStyle(InteractivePillButtonStyle())
      }
      .padding(.top, 2)

    }
    .frame(maxWidth: .infinity)
    .padding(.vertical, 4)
    .onAppear {
      withAnimation(.easeInOut(duration: 1.3).repeatForever(autoreverses: true)) {
        isPairingPulsing = true
      }
    }
    .onDisappear {
      isPairingPulsing = false
    }
  }

  private func pairingPinCard(device: DiscoveredClientDevice) -> some View {
    VStack(spacing: AppSpacing.spacing10) {
      VStack(spacing: AppSpacing.spacing6) {
        ZStack {
          Circle()
            .stroke(AppColors.accent.opacity(isPairingPulsing ? 0.0 : 0.4), lineWidth: 1.5)
            .scaleEffect(isPairingPulsing ? 1.4 : 0.95)
          Circle()
            .fill(AppColors.accent.opacity(colorScheme == .dark ? 0.14 : 0.08))
            .frame(width: 40, height: 40)
            .overlay(
              Circle()
                .stroke(AppColors.accent.opacity(0.25), lineWidth: 1)
            )
          Image(systemName: deviceIcon(for: friendlyDeviceName(for: device.name)))
            .font(.system(size: 18, weight: .medium))
            .foregroundColor(AppColors.accent)
        }
        .frame(width: 44, height: 44)

        VStack(spacing: 2) {
          Text(friendlyDeviceName(for: device.name))
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(AppColors.textPrimary)

          if let pin = appState.pendingPairingSasCode, !pin.isEmpty {
            Text("Compare this code with your phone")
              .font(AppTypography.bodySecondary)
              .foregroundColor(AppColors.textSecondary)
              .transition(
                .asymmetric(
                  insertion: .opacity.combined(with: .offset(y: 4)),
                  removal: .opacity
                )
              )
          }
        }
      }

      if let pin = appState.pendingPairingSasCode, !pin.isEmpty {
        pinCodeView(pin: pin)
          .transition(
            .asymmetric(
              insertion: .opacity.combined(with: .scale(scale: 0.92)),
              removal: .opacity
            )
          )
      }

      HStack(alignment: .center, spacing: 6) {
        ProgressView()
          .controlSize(.small)
        Text(appState.pendingPairingSasCode != nil ? "Waiting for confirmation on device..." : "Waiting for approval on device...")
          .font(AppTypography.metadata)
          .foregroundColor(AppColors.textSecondary)
          .lineLimit(1)
          .id(appState.pendingPairingSasCode != nil)
          .transition(
            .asymmetric(
              insertion: .opacity.combined(with: .offset(y: 3)),
              removal: .opacity.combined(with: .offset(y: -3))
            )
          )
      }
      .padding(.horizontal, AppSpacing.spacing10)
      .padding(.vertical, 5)
      .background(
        Capsule()
          .fill(AppColors.containerSubtle(colorScheme))
      )
      .animation(.spring(response: 0.35, dampingFraction: 0.85), value: appState.pendingPairingSasCode != nil)

      Button(action: {
        withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
          appState.cancelPairingSelection()
        }
      }) {
        Text("Cancel")
          .font(.system(size: 12, weight: .medium))
          .foregroundColor(AppColors.textPrimary)
          .frame(width: 100, height: 26)
          .background(
            Capsule()
              .fill(colorScheme == .dark ? Color.white.opacity(0.08) : Color.black.opacity(0.05))
              .overlay(
                Capsule()
                  .stroke(AppColors.surfaceBorder(colorScheme), lineWidth: 0.5)
              )
          )
      }
      .buttonStyle(InteractivePillButtonStyle())
    }
    .frame(maxWidth: .infinity)
    .padding(.vertical, 4)
    .animation(.spring(response: 0.42, dampingFraction: 0.85), value: appState.pendingPairingSasCode)
    .onAppear {
      withAnimation(.easeInOut(duration: 1.3).repeatForever(autoreverses: true)) {
        isPairingPulsing = true
      }
    }
    .onDisappear {
      isPairingPulsing = false
    }
  }

  @ViewBuilder
  private func pinCodeView(pin: String) -> some View {
    let digits = Array(pin)
    if digits.count == 6 {
      HStack(spacing: 8) {
        HStack(spacing: 4) {
          ForEach(0..<3, id: \.self) { i in
            pinDigitCell(char: digits[i])
          }
        }
        HStack(spacing: 4) {
          ForEach(3..<6, id: \.self) { i in
            pinDigitCell(char: digits[i])
          }
        }
      }
    } else {
      Text(pin)
        .font(.system(size: 18, weight: .bold, design: .monospaced))
        .foregroundColor(AppColors.textPrimary)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .background(
          RoundedRectangle(cornerRadius: AppRadius.medium)
            .fill(colorScheme == .dark ? Color.white.opacity(0.06) : Color.black.opacity(0.04))
            .overlay(
              RoundedRectangle(cornerRadius: AppRadius.medium)
                .stroke(AppColors.surfaceBorder(colorScheme), lineWidth: 0.5)
            )
        )
    }
  }
  private func pinDigitCell(char: Character) -> some View {
    Text(String(char))
      .font(.system(size: 18, weight: .semibold, design: .monospaced))
      .foregroundColor(AppColors.textPrimary)
      .frame(width: 28, height: 35)
      .background(
        RoundedRectangle(cornerRadius: AppRadius.medium)
          .fill(colorScheme == .dark ? Color.white.opacity(0.08) : Color.black.opacity(0.04))
          .overlay(
            RoundedRectangle(cornerRadius: AppRadius.medium)
              .stroke(AppColors.accent.opacity(colorScheme == .dark ? 0.35 : 0.25), lineWidth: 1)
          )
      )
  }
  private var discoveredDevicesSection: some View {
    VStack(alignment: .leading, spacing: 6) {
      Text("NEARBY DEVICES")
        .font(.system(size: 10, weight: .semibold))
        .foregroundColor(AppColors.textMuted)
        .padding(.horizontal, AppSpacing.spacing4)
      if appState.unpairedDiscoveredDevices.isEmpty {
        VStack(spacing: AppSpacing.spacing8) {
          ZStack {
            Circle()
              .fill(AppColors.surfaceHover(colorScheme))
              .frame(width: 36, height: 36)

            Image(systemName: "wifi")
              .font(.system(size: 16, weight: .medium))
              .foregroundColor(AppColors.textSecondary)
          }
          .padding(.bottom, 2)

          Text("No devices found")
            .font(AppTypography.bodySecondary.weight(.medium))
            .foregroundColor(AppColors.textSecondary)
          Text("Open RapiDrop on other device on same Wi-Fi.")
            .font(AppTypography.metadata)
            .foregroundColor(AppColors.textMuted)
            .multilineTextAlignment(.center)
        }
        .padding(.vertical, AppSpacing.spacing14)
        .padding(.horizontal, AppSpacing.spacing12)
        .frame(maxWidth: .infinity)
        .background(
          RoundedRectangle(cornerRadius: AppRadius.medium)
            .fill(AppColors.containerSubtle(colorScheme))
        )
        .transition(.opacity.combined(with: .scale(scale: 0.98)))
      } else {
        VStack(spacing: AppSpacing.spacing6) {
          ForEach(appState.unpairedDiscoveredDevices) { device in
            HStack(spacing: AppSpacing.spacing10) {
              ZStack {
                RoundedRectangle(cornerRadius: AppRadius.medium)
                  .fill(colorScheme == .dark ? Color.white.opacity(0.06) : Color.black.opacity(0.04))
                  .frame(width: 32, height: 32)
                Image(systemName: deviceIcon(for: device.name))
                  .font(.system(size: 15, weight: .medium))
                  .foregroundColor(AppColors.textSecondary)
              }
              VStack(alignment: .leading, spacing: 1.5) {
                Text(friendlyDeviceName(for: device.name))
                  .font(.system(size: 13, weight: .semibold))
                  .foregroundColor(AppColors.textPrimary)
                  .lineLimit(1)
                  .truncationMode(.tail)
                Text("Ready to connect")
                  .font(.system(size: 10.5, weight: .regular))
                  .foregroundColor(AppColors.textMuted)
                  .lineLimit(1)
                  .truncationMode(.tail)
              }

              Spacer()

              Button(action: {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                  appState.selectDeviceForPairing(device)
                }
              }) {
                Text("Connect")
                  .font(.system(size: 11.5, weight: .semibold))
                  .foregroundColor(AppColors.textPrimary)
                  .padding(.horizontal, 12)
                  .frame(height: 24)
                  .background(
                    Capsule()
                      .fill(colorScheme == .dark ? Color.white.opacity(0.12) : Color.black.opacity(0.06))
                  )
              }
              .buttonStyle(InteractivePillButtonStyle())
              .fixedSize()
              .layoutPriority(1)
            }
            .padding(.horizontal, AppSpacing.spacing12)
            .frame(height: 46)
            .background(
              RoundedRectangle(cornerRadius: AppRadius.card)
                .fill(AppColors.surfaceCard(colorScheme))
            )
            .overlay(
              RoundedRectangle(cornerRadius: AppRadius.card)
                .stroke(AppColors.surfaceBorder(colorScheme), lineWidth: 0.5)
            )
            .transition(
              .asymmetric(
                insertion: .opacity.combined(with: .offset(y: 6)),
                removal: .opacity.combined(with: .scale(scale: 0.96))
              )
            )
          }
        }
      }
    }
    .transition(
      .asymmetric(
        insertion: .opacity.combined(with: .scale(scale: 0.98, anchor: .top)),
        removal: .opacity.combined(with: .scale(scale: 0.98, anchor: .top))
      ))
  }

  private var pairedDeviceCockpitSection: some View {
    VStack(alignment: .leading, spacing: AppSpacing.spacing10) {
      connectedDeviceCard

      VStack(spacing: AppSpacing.spacing6) {
        Button(action: {
          let panel = NSOpenPanel()
          panel.canChooseFiles = true
          panel.canChooseDirectories = true
          panel.allowsMultipleSelection = true
          panel.prompt = "Send"
          panel.message = "Choose files, photos, or documents to send"
          if panel.runModal() == .OK, !panel.urls.isEmpty {
            appState.sendFiles(at: panel.urls)
            withAnimation(.spring(response: 0.35, dampingFraction: 0.75)) {
              isFileSent = true
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
              withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                isFileSent = false
              }
            }
          }
        }) {
          HStack(spacing: AppSpacing.spacing10) {
            ZStack {
              RoundedRectangle(cornerRadius: AppRadius.medium)
                .fill(isFileSent ? AppColors.statusConnected.opacity(0.15) : AppColors.surfaceHover(colorScheme))
                .frame(width: 32, height: 32)
              Image(systemName: isFileSent ? "checkmark" : "folder")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(isFileSent ? AppColors.statusConnected : AppColors.textPrimary)
                .contentTransition(.symbolEffect(.replace.downUp))
            }

            VStack(alignment: .leading, spacing: 2) {
              Text("Send Media")
                .font(AppTypography.bodyPrimary.weight(.semibold))
                .foregroundColor(appState.isConnected ? AppColors.textPrimary : AppColors.textPrimary.opacity(0.4))
              Text(isFileSent ? "Sent to \(friendlyDeviceName(for: appState.peerName ?? appState.pairedPeerName ?? "Device"))!" : "Share files, photos, or documents")
                .font(AppTypography.metadata)
                .foregroundColor(isFileSent ? AppColors.statusConnected : (appState.isConnected ? AppColors.textSecondary : AppColors.textSecondary.opacity(0.4)))
                .contentTransition(.opacity)
            }

            Spacer()

            Image(systemName: "chevron.forward")
              .font(.system(size: 10, weight: .semibold))
              .foregroundColor(AppColors.textSecondary.opacity(0.6))
          }
          .padding(AppSpacing.spacing8)
          .background(
            RoundedRectangle(cornerRadius: AppRadius.card)
              .fill(isFileSent ? AppColors.statusConnected.opacity(colorScheme == .dark ? 0.12 : 0.08) : AppColors.surfaceCard(colorScheme))
              .overlay(
                RoundedRectangle(cornerRadius: AppRadius.card)
                  .stroke(isFileSent ? AppColors.statusConnected.opacity(0.3) : AppColors.surfaceBorder(colorScheme), lineWidth: 1)
              )
          )
        }
        .buttonStyle(InteractiveCardButtonStyle())
        .disabled(!appState.isConnected)

        Button(action: {
          if appState.sendClipboardNow() {
            withAnimation(.spring(response: 0.35, dampingFraction: 0.75)) {
              isClipboardSynced = true
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
              withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                isClipboardSynced = false
              }
            }
          }
        }) {
          HStack(spacing: AppSpacing.spacing10) {
            ZStack {
              RoundedRectangle(cornerRadius: AppRadius.medium)
                .fill(isClipboardSynced ? AppColors.statusConnected.opacity(0.15) : AppColors.surfaceHover(colorScheme))
                .frame(width: 32, height: 32)
              Image(systemName: isClipboardSynced ? "checkmark" : "doc.on.clipboard")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(isClipboardSynced ? AppColors.statusConnected : AppColors.textPrimary)
                .contentTransition(.symbolEffect(.replace.downUp))
            }

            VStack(alignment: .leading, spacing: 2) {
              Text("Send Clipboard")
                .font(AppTypography.bodyPrimary.weight(.semibold))
                .foregroundColor(appState.isConnected ? AppColors.textPrimary : AppColors.textPrimary.opacity(0.4))
              Text(isClipboardSynced ? "Sent to \(friendlyDeviceName(for: appState.peerName ?? appState.pairedPeerName ?? "Device"))!" : "Send current clipboard to \(friendlyDeviceName(for: appState.peerName ?? appState.pairedPeerName ?? "Device"))")
                .font(AppTypography.metadata)
                .foregroundColor(isClipboardSynced ? AppColors.statusConnected : (appState.isConnected ? AppColors.textSecondary : AppColors.textSecondary.opacity(0.4)))
                .contentTransition(.opacity)
            }

            Spacer()

            Image(systemName: "chevron.forward")
              .font(.system(size: 10, weight: .semibold))
              .foregroundColor(AppColors.textSecondary.opacity(0.6))
          }
          .padding(AppSpacing.spacing8)
          .background(
            RoundedRectangle(cornerRadius: AppRadius.card)
              .fill(isClipboardSynced ? AppColors.statusConnected.opacity(colorScheme == .dark ? 0.12 : 0.08) : AppColors.surfaceCard(colorScheme))
              .overlay(
                RoundedRectangle(cornerRadius: AppRadius.card)
                  .stroke(isClipboardSynced ? AppColors.statusConnected.opacity(0.3) : AppColors.surfaceBorder(colorScheme), lineWidth: 1)
              )
          )
        }
        .buttonStyle(InteractiveCardButtonStyle())
        .disabled(!appState.isConnected)

        Button(action: {
          withAnimation(.spring(response: 0.35, dampingFraction: 0.84)) {
            isViewingHistory = true
          }
        }) {
          HStack(spacing: AppSpacing.spacing10) {
            ZStack {
              RoundedRectangle(cornerRadius: AppRadius.medium)
                .fill(AppColors.surfaceHover(colorScheme))
                .frame(width: 32, height: 32)
              Image(systemName: "clock.arrow.circlepath")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(AppColors.textPrimary)
            }

            VStack(alignment: .leading, spacing: 2) {
              Text("Sync History")
                .font(AppTypography.bodyPrimary.weight(.semibold))
                .foregroundColor(AppColors.textPrimary)
              Text("View recent synchronized items")
                .font(AppTypography.metadata)
                .foregroundColor(AppColors.textSecondary)
            }

            Spacer()

            Image(systemName: "chevron.forward")
              .font(.system(size: 10, weight: .semibold))
              .foregroundColor(AppColors.textSecondary.opacity(0.6))
          }
          .padding(AppSpacing.spacing8)
          .background(
            RoundedRectangle(cornerRadius: AppRadius.card)
              .fill(AppColors.surfaceCard(colorScheme))
              .overlay(
                RoundedRectangle(cornerRadius: AppRadius.card)
                  .stroke(AppColors.surfaceBorder(colorScheme), lineWidth: 1)
              )
          )
        }
        .buttonStyle(InteractiveCardButtonStyle())
      }
    }
  }

  private var connectedDeviceCard: some View {
    HStack(spacing: AppSpacing.spacing10) {
      ZStack {
        RoundedRectangle(cornerRadius: AppRadius.medium)
          .fill(AppColors.surfaceHover(colorScheme))
          .frame(width: 34, height: 34)

        Image(systemName: deviceIcon(for: friendlyDeviceName(for: appState.peerName ?? appState.pairedPeerName ?? "Device")))
          .font(.system(size: 15))
          .foregroundColor(AppColors.textPrimary)
      }

      VStack(alignment: .leading, spacing: 2) {
        Text(friendlyDeviceName(for: appState.peerName ?? appState.pairedPeerName ?? "Paired Device"))
          .font(AppTypography.deviceName)
          .foregroundColor(AppColors.textPrimary)
          .lineLimit(1)
          .truncationMode(.tail)

        HStack(spacing: 5) {
          Circle()
            .fill(appState.isConnected ? AppColors.statusConnected : (appState.isConnecting ? Color.orange : AppColors.statusDisconnected))
            .frame(width: 5, height: 5)
          Text(appState.isConnected ? "Connected" : (appState.isConnecting ? "Connecting..." : "Offline"))
            .font(AppTypography.statusBadge)
            .foregroundColor(appState.isConnected ? AppColors.statusConnected : (appState.isConnecting ? Color.orange : AppColors.statusDisconnected))
            .lineLimit(1)
        }
      }
      Spacer()

      Button(action: {
        withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
          appState.unpair()
        }
      }) {
        Text("Unpair")
          .font(AppTypography.metadata.weight(.medium))
          .foregroundColor(AppColors.textSecondary)
          .padding(.horizontal, AppSpacing.spacing8)
          .frame(minHeight: 24)
          .background(AppColors.surfaceHover(colorScheme))
          .cornerRadius(AppRadius.small)
      }
      .buttonStyle(InteractivePillButtonStyle())
      .accessibilityLabel("Unpair device")
    }
    .padding(AppSpacing.spacing10)
    .background(
      RoundedRectangle(cornerRadius: AppRadius.card)
        .fill(AppColors.surfaceCard(colorScheme))
        .overlay(
          RoundedRectangle(cornerRadius: AppRadius.card)
            .stroke(AppColors.surfaceBorder(colorScheme), lineWidth: 1)
        )
    )
  }
  @ViewBuilder
  private func transferProgressCard(_ info: AppState.TransferProgressInfo) -> some View {
    VStack(alignment: .leading, spacing: 6) {
      HStack(spacing: 6) {
        Image(systemName: info.isSending ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
          .font(.system(size: 13, weight: .semibold))
          .foregroundColor(Color(red: 0.06, green: 0.72, blue: 0.51))
        Text(info.isSending ? "Sending \(info.fileName)" : "Receiving \(info.fileName)")
          .font(.system(size: 12, weight: .medium))
          .lineLimit(1)
        Spacer()
        if info.totalFiles > 1 {
          Text("\(info.fileIndex)/\(info.totalFiles)")
            .font(.system(size: 10, weight: .semibold))
            .foregroundColor(.secondary)
        }
        Button(action: {
          withAnimation(.spring(response: 0.32, dampingFraction: 0.8)) {
            appState.cancelActiveTransfer()
          }
        }) {
          Image(systemName: "xmark.circle.fill")
            .font(.system(size: 14, weight: .medium))
            .foregroundColor(AppColors.textSecondary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Cancel transfer")
      }
      ProgressView(value: info.progressFraction)
        .progressViewStyle(.linear)
        .tint(Color(red: 0.06, green: 0.72, blue: 0.51))

      HStack {
        let sentMb = Double(info.bytesTransferred) / 1_048_576.0
        let totalMb = Double(info.totalBytes) / 1_048_576.0
        Text(String(format: "%.1f / %.1f MB", sentMb, totalMb))
          .font(.system(size: 10))
          .foregroundColor(.secondary)
        Spacer()
        if info.speedBytesPerSec > 0 {
          let speedMb = info.speedBytesPerSec / 1_048_576.0
          let speedText = String(format: "%.1f MB/s", speedMb)
          if let eta = info.formattedEta {
            Text("\(speedText), \(eta)")
              .font(.system(size: 10, weight: .medium))
              .foregroundColor(.secondary)
          } else {
            Text(speedText)
              .font(.system(size: 10, weight: .medium))
              .foregroundColor(.secondary)
          }
        }
      }
    }
    .padding(10)
    .background(
      RoundedRectangle(cornerRadius: 10)
        .fill(colorScheme == .dark ? Color.primary.opacity(0.04) : Color(nsColor: .controlBackgroundColor))
    )
    .overlay(
      RoundedRectangle(cornerRadius: 10)
        .stroke(Color(red: 0.06, green: 0.72, blue: 0.51).opacity(0.3), lineWidth: 1)
    )
    .transition(.opacity.combined(with: .move(edge: .top)))
  }

  private func selectCustomFolder() {
    let panel = NSOpenPanel()
    panel.canChooseFiles = false
    panel.canChooseDirectories = true
    panel.allowsMultipleSelection = false
    panel.canCreateDirectories = true
    panel.prompt = "Select Folder"
    panel.message = "Choose destination folder for received media"

    if panel.runModal() == .OK, let selectedURL = panel.url {
      appState.customMediaFolderURL = selectedURL
    }
  }

  private var recentHistorySection: some View {
    VStack(alignment: .leading, spacing: 8) {
      if let inspecting = inspectingClip {
        HStack {
          Button(action: {
            withAnimation(.spring(response: 0.28, dampingFraction: 0.8)) {
              inspectingClip = nil
            }
          }) {
            HStack(spacing: 4) {
              Image(systemName: "chevron.left")
                .font(.system(size: 9, weight: .bold))
              Text("Back to Clips")
                .font(.system(size: 10, weight: .medium))
            }
            .foregroundColor(.secondary)
          }
          .buttonStyle(InteractivePillButtonStyle())
          .accessibilityLabel("Back to clips list")

          Spacer()

          Text("Inspect Clip")
            .font(.system(size: 10, weight: .bold))
            .foregroundColor(.secondary)
            .tracking(0.5)
        }

        clipInspectorCard(inspecting)
          .transition(
            .asymmetric(
              insertion: .move(edge: .trailing).combined(with: .opacity),
              removal: .move(edge: .trailing).combined(with: .opacity)
            )
          )
      } else {
        HStack {
          Text("Recent clips")
            .font(.system(size: 10, weight: .bold))
            .foregroundColor(.secondary)
            .tracking(0.5)

          Spacer()

          if !appState.recentClips.isEmpty {
            Button("Clear") {
              showClearConfirmation = true
            }
            .font(.system(size: 10))
            .foregroundColor(.secondary)
            .buttonStyle(.plain)
            .accessibilityLabel("Clear all clips")
          }
        }
        if appState.showSyncHistory && !appState.recentClips.isEmpty {
          HStack(spacing: AppSpacing.spacing6) {
            Image(systemName: "magnifyingglass")
              .font(.system(size: 10))
              .foregroundColor(AppColors.textSecondary)

            TextField("Search clips...", text: $searchText)
              .textFieldStyle(.plain)
              .font(AppTypography.bodySecondary)
              .onExitCommand {
                if !searchText.isEmpty {
                  searchText = ""
                }
              }
            if !searchText.isEmpty {
              Button(action: { searchText = "" }) {
                Image(systemName: "xmark.circle.fill")
                  .font(.system(size: 10))
                  .foregroundColor(AppColors.textSecondary)
              }
              .buttonStyle(.plain)
              .accessibilityLabel("Clear search")
            }
          }
          .padding(.horizontal, AppSpacing.spacing8)
          .padding(.vertical, AppSpacing.spacing4)
          .background(
            RoundedRectangle(cornerRadius: AppRadius.medium)
              .fill(AppColors.surfaceCard(colorScheme))
              .overlay(
                RoundedRectangle(cornerRadius: AppRadius.medium)
                  .stroke(AppColors.surfaceBorder(colorScheme), lineWidth: 1)
              )
          )

          HStack(spacing: 4) {
            filterChip(title: "All", filter: nil, key: "1")
            filterChip(title: "Text", filter: .text, key: "2")
            filterChip(title: "Links", filter: .url, key: "3")
            filterChip(title: "Media", filter: .image, key: "4")
          }
        }

        if !appState.showSyncHistory {
          HStack {
            Spacer()
            VStack(spacing: 4) {
              Image(systemName: "lock")
                .font(.system(size: 15))
                .foregroundColor(.secondary)
              Text("Direct Clipboard Sync")
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(.primary)
              Text("Clips sync in memory without history storage")
                .font(.system(size: 9.5))
                .foregroundColor(.secondary)

              Button(action: { appState.showSyncHistory = true }) {
                Text("Turn on History")
                  .font(.system(size: 10.5, weight: .medium))
                  .foregroundColor(.primary)
                  .padding(.horizontal, 10)
                  .padding(.vertical, 3.5)
                  .background(
                    colorScheme == .dark
                      ? Color.primary.opacity(0.08) : Color(nsColor: .controlBackgroundColor)
                  )
                  .cornerRadius(6)
                  .overlay(
                    RoundedRectangle(cornerRadius: 6)
                      .stroke(
                        colorScheme == .dark
                          ? Color.clear : Color(nsColor: .separatorColor).opacity(0.5), lineWidth: 1)
                  )
              }
              .buttonStyle(.plain)
              .padding(.top, 4)
            }
            .padding(.vertical, 10)
            Spacer()
          }
        } else if appState.recentClips.isEmpty {
          HStack {
            Spacer()
            VStack(spacing: 6) {
              Image(systemName: "clipboard")
                .font(.system(size: 20))
                .foregroundColor(.secondary.opacity(0.6))
              Text("No clipboard history")
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(.secondary)
              Text("Copied items will appear here.")
                .foregroundColor(.secondary.opacity(0.7))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 8)
            }
            .padding(.vertical, 16)
            Spacer()
          }
        } else if filteredClips.isEmpty {
          VStack(spacing: 6) {
            Text("No matching clips")
              .font(.system(size: 11, weight: .medium))
              .foregroundColor(.secondary)
            Button("Clear Filter") {
              searchText = ""
              selectedFilter = nil
            }
            .font(.system(size: 10))
            .buttonStyle(.plain)
            .foregroundColor(AppColors.accent)
          }
          .frame(maxWidth: .infinity)
          .padding(.vertical, 12)
        } else {
          ScrollView(.vertical, showsIndicators: false) {
            VStack(spacing: 4) {
              ForEach(filteredClips) { clip in
                clipRow(clip)
              }
            }
            .animation(.spring(response: 0.3, dampingFraction: 0.8), value: filteredClips)
          }
          .frame(maxHeight: 220)
        }
      }
    }
  }

  private var filteredClips: [ClipItem] {
    let filtered = appState.recentClips.filter { clip in
      let matchesFilter: Bool = {
        guard let filter = selectedFilter else { return true }
        if filter == .image {
          return clip.type == .image || clip.type == .file
        }
        return clip.type == filter
      }()
      let trimmed = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
      guard !trimmed.isEmpty else { return matchesFilter }
      let query = trimmed.lowercased()
      let textMatch = clip.textContent?.lowercased().contains(query) ?? false
      let fileMatch = clip.fileName?.lowercased().contains(query) ?? false
      let previewMatch = clip.previewText.lowercased().contains(query)
      return matchesFilter && (textMatch || fileMatch || previewMatch)
    }
    var pinned: [ClipItem] = []
    var unpinned: [ClipItem] = []
    pinned.reserveCapacity(filtered.count / 2)
    unpinned.reserveCapacity(filtered.count)
    for clip in filtered {
      if appState.pinnedClipIds.contains(clip.id) {
        pinned.append(clip)
      } else {
        unpinned.append(clip)
      }
    }
    return pinned + unpinned
  }

  private func filterChip(title: String, filter: ClipContentType?, key: KeyEquivalent? = nil)
    -> some View
  {
    let isSelected = selectedFilter == filter
    let btn = Button(action: {
      withAnimation(.spring(response: 0.25, dampingFraction: 0.8)) {
        selectedFilter = filter
      }
    }) {
      Text(title)
        .font(.system(size: 10, weight: isSelected ? .semibold : .medium))
        .foregroundColor(isSelected ? .white : AppColors.textSecondary)
        .padding(.horizontal, AppSpacing.spacing8)
        .padding(.vertical, 3)
        .background(
          RoundedRectangle(cornerRadius: AppRadius.small)
            .fill(
              isSelected
                ? Color.accentColor
                : AppColors.surfaceHover(colorScheme)
            )
            .overlay(
              RoundedRectangle(cornerRadius: AppRadius.small)
                .stroke(isSelected ? Color.clear : AppColors.surfaceBorder(colorScheme), lineWidth: 1)
            )
        )
    }
    .buttonStyle(InteractivePillButtonStyle())
    .animation(.spring(response: 0.25, dampingFraction: 0.8), value: selectedFilter)

    return Group {
      if let key = key {
        btn.keyboardShortcut(key, modifiers: .command)
      } else {
        btn
      }
    }
  }
  private static let relativeFormatter: RelativeDateTimeFormatter = {
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .abbreviated
    return formatter
  }()

  private func relativeTimeString(for date: Date) -> String {
    let interval = -date.timeIntervalSinceNow
    if interval < 60 {
      return "Just now"
    }
    return Self.relativeFormatter.localizedString(for: date, relativeTo: Date())
  }

  @ViewBuilder
  private func clipLeadingView(_ clip: ClipItem, isCopied: Bool) -> some View {
    if isCopied {
      Image(systemName: "checkmark")
        .font(.system(size: 11))
        .foregroundColor(Color(red: 0.06, green: 0.72, blue: 0.51))
        .frame(width: 20, height: 20)
    } else if clip.type == .image, let thumb = ThumbnailCache.shared.thumbnail(for: clip.id, rawData: clip.rawData) {
      Image(nsImage: thumb)
        .resizable()
        .scaledToFill()
        .frame(width: 20, height: 20)
        .clipShape(RoundedRectangle(cornerRadius: 4))
        .overlay(
          RoundedRectangle(cornerRadius: 4)
            .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
    } else {
      Image(systemName: iconForType(clip.type))
        .font(.system(size: 11))
        .foregroundColor(.secondary)
        .frame(width: 14)
    }
  }

  private func clipRow(_ clip: ClipItem) -> some View {
    let isCopied = copiedClipId == clip.id
    let isHovered = hoveredClipId == clip.id
    let isPinned = appState.pinnedClipIds.contains(clip.id)
    let isCode = isCodeSnippet(clip.previewText)
    return HStack(spacing: 8) {
      if isPinned {
        Image(systemName: "pin.fill")
          .font(.system(size: 8))
          .foregroundColor(.accentColor)
      }

      clipLeadingView(clip, isCopied: isCopied)

      VStack(alignment: .leading, spacing: 1) {
        Text(clip.previewText)
          .font(
            isCode ? .system(size: 11, design: .monospaced) : .system(size: 11)
          )
          .lineLimit(1)
          .truncationMode(.tail)

        HStack(spacing: 4) {
          if isCode {
            Text("CODE")
              .font(.system(size: 7.5, weight: .bold))
              .foregroundColor(.secondary)
              .padding(.horizontal, 3)
              .padding(.vertical, 0.5)
              .background(Color.primary.opacity(0.06))
              .cornerRadius(3)
          }
          Text(relativeTimeString(for: clip.timestamp))
            .font(.system(size: 9))
            .foregroundColor(.secondary.opacity(0.75))
        }
      }
      Spacer()
      if isHovered || isCopied {
        HStack(spacing: 4) {
          if isHovered && !isCopied {
            if clip.type == .url, let urlStr = clip.textContent, let url = URL(string: urlStr),
              url.scheme != nil
            {
              Button(action: {
                NSWorkspace.shared.open(url)
              }) {
                Image(systemName: "arrow.up.right")
                  .font(.system(size: 8, weight: .bold))
                  .foregroundColor(.secondary)
                  .frame(width: 22, height: 20)
                  .background(Color.primary.opacity(0.06))
                  .cornerRadius(4)
                  .contentShape(Rectangle())
              }
              .buttonStyle(.plain)
              .accessibilityLabel("Open URL in browser")
            } else if clip.type == .file || clip.type == .image, let name = clip.fileName {
              let fileURL = appState.mediaFolderURL.appendingPathComponent(name)
              if FileManager.default.fileExists(atPath: fileURL.path) {
                Button(action: {
                  NSWorkspace.shared.activateFileViewerSelecting([fileURL])
                }) {
                  Image(systemName: "folder")
                    .font(.system(size: 8, weight: .bold))
                    .foregroundColor(.secondary)
                    .frame(width: 22, height: 20)
                    .background(Color.primary.opacity(0.06))
                    .cornerRadius(4)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Show file in Finder")
              }
            }
            Button(action: {
              withAnimation(.spring(response: 0.32, dampingFraction: 0.82)) {
                inspectingClip = clip
              }
            }) {
              Image(systemName: "magnifyingglass")
                .font(.system(size: 8, weight: .bold))
                .foregroundColor(.secondary)
                .frame(width: 22, height: 20)
                .background(Color.primary.opacity(0.06))
                .cornerRadius(4)
                .contentShape(Rectangle())
            }
            .buttonStyle(InteractivePillButtonStyle())
            .accessibilityLabel("Inspect clip details")
            Button(action: {
              withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                appState.deleteClip(clip)
              }
            }) {
              Image(systemName: "trash")
                .font(.system(size: 8, weight: .bold))
                .foregroundColor(.secondary)
                .frame(width: 22, height: 20)
                .background(Color.primary.opacity(0.06))
                .cornerRadius(4)
                .contentShape(Rectangle())
            }
            .buttonStyle(InteractivePillButtonStyle())
            .accessibilityLabel("Delete clip")
          }
          Button(action: {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
              appState.togglePinClip(clip)
            }
          }) {
            Image(systemName: isPinned ? "pin.fill" : "pin")
              .font(.system(size: 8, weight: .bold))
              .foregroundColor(isPinned ? .accentColor : .secondary)
              .frame(width: 22, height: 20)
              .background(Color.primary.opacity(0.06))
              .cornerRadius(4)
              .contentShape(Rectangle())
          }
          .buttonStyle(InteractivePillButtonStyle())
          .accessibilityLabel(isPinned ? "Unpin clip" : "Pin clip")

          Text(isCopied ? "Copied" : "Copy")
            .font(.system(size: 9, weight: .medium))
            .foregroundColor(isCopied ? Color(red: 0.06, green: 0.72, blue: 0.51) : .secondary)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(Color.primary.opacity(0.06))
            .cornerRadius(4)
        }
      }
    }
    .padding(.horizontal, 8)
    .padding(.vertical, 6)
    .background(
      RoundedRectangle(cornerRadius: 6)
        .fill(
          isHovered
            ? (colorScheme == .dark
              ? Color.primary.opacity(0.04) : Color(nsColor: .controlBackgroundColor)) : Color.clear
        )
    )
    .contentShape(Rectangle())
    .animation(.easeInOut(duration: 0.15), value: hoveredClipId)
    .animation(.spring(response: 0.25, dampingFraction: 0.7), value: copiedClipId)
    .onHover { hovering in
      hoveredClipId = hovering ? clip.id : nil
    }
    .onTapGesture {
      appState.copyRecentClip(clip)
      withAnimation(.spring(response: 0.25, dampingFraction: 0.7)) {
        copiedClipId = clip.id
      }
      DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
        if copiedClipId == clip.id {
          withAnimation(.spring(response: 0.25, dampingFraction: 0.7)) {
            copiedClipId = nil
          }
        }
      }
    }
  }
  @ViewBuilder
  private func clipInspectorCard(_ clip: ClipItem) -> some View {
    VStack(alignment: .leading, spacing: 8) {
      HStack {
        Image(systemName: iconForType(clip.type))
          .font(.system(size: 11, weight: .semibold))
          .foregroundColor(.accentColor)
        Text(clip.type.rawValue.capitalized)
          .font(.system(size: 11, weight: .bold))
          .foregroundColor(.primary)
        Text(relativeTimeString(for: clip.timestamp))
          .font(.system(size: 9))
          .foregroundColor(.secondary)
        Spacer()
        Button(action: {
          withAnimation(.spring(response: 0.28, dampingFraction: 0.8)) {
            inspectingClip = nil
          }
        }) {
          Image(systemName: "xmark")
            .font(.system(size: 8, weight: .bold))
            .foregroundColor(.secondary)
            .padding(4)
            .background(Color.primary.opacity(0.06))
            .clipShape(Circle())
        }
        .buttonStyle(InteractivePillButtonStyle())
        .accessibilityLabel("Close inspector")
      }
      if clip.type == .image {
        let nsImage: NSImage? = {
          if let raw = clip.rawData { return NSImage(data: raw) }
          if let name = clip.fileName {
            let fileURL = appState.mediaFolderURL.appendingPathComponent(name)
            if let img = NSImage(contentsOf: fileURL) { return img }
          }
          return nil
        }()
        if let image = nsImage {
          VStack(spacing: 6) {
            Image(nsImage: image)
              .resizable()
              .scaledToFit()
              .frame(maxHeight: 180)
              .cornerRadius(6)
              .overlay(
                RoundedRectangle(cornerRadius: 6)
                  .stroke(Color.primary.opacity(0.08), lineWidth: 1)
              )
            HStack {
              Text("\(Int(image.size.width)) × \(Int(image.size.height)) px")
                .font(.system(size: 9))
                .foregroundColor(.secondary)
              Spacer()
              if let raw = clip.rawData {
                Text(ByteCountFormatter.string(fromByteCount: Int64(raw.count), countStyle: .file))
                  .font(.system(size: 9))
                  .foregroundColor(.secondary)
              }
            }
          }
        }
      } else if let text = clip.textContent, !text.isEmpty {
        ScrollView(.vertical, showsIndicators: true) {
          Text(text)
            .font(isCodeSnippet(text) ? .system(size: 10, design: .monospaced) : .system(size: 11))
            .foregroundColor(.primary)
            .textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(8)
        }
        .frame(maxHeight: 160)
        .background(colorScheme == .dark ? Color.primary.opacity(0.04) : Color(nsColor: .controlBackgroundColor))
        .cornerRadius(6)
        .overlay(
          RoundedRectangle(cornerRadius: 6)
            .stroke(Color.primary.opacity(0.08), lineWidth: 1)
        )
      } else if clip.type == .file, let name = clip.fileName {
        HStack(spacing: 8) {
          Image(systemName: "doc")
            .font(.system(size: 18))
            .foregroundColor(.secondary)
          VStack(alignment: .leading, spacing: 2) {
            Text(name)
              .font(.system(size: 11, weight: .medium))
              .lineLimit(1)
            let fileURL = appState.mediaFolderURL.appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: fileURL.path),
               let attr = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
               let size = attr[.size] as? Int64 {
              Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
                .font(.system(size: 9))
                .foregroundColor(.secondary)
            }
          }
          Spacer()
          let fileURL = appState.mediaFolderURL.appendingPathComponent(name)
          if FileManager.default.fileExists(atPath: fileURL.path) {
            Button(action: {
              NSWorkspace.shared.activateFileViewerSelecting([fileURL])
            }) {
              Text("Show in Finder")
                .font(.system(size: 9, weight: .medium))
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(Color.primary.opacity(0.06))
                .cornerRadius(4)
            }
            .buttonStyle(.plain)
          }
        }
        .padding(8)
        .background(colorScheme == .dark ? Color.primary.opacity(0.04) : Color(nsColor: .controlBackgroundColor))
        .cornerRadius(6)
      }
      HStack(spacing: 6) {
        Button(action: {
          appState.copyRecentClip(clip)
          withAnimation(.spring(response: 0.25, dampingFraction: 0.7)) {
            copiedClipId = clip.id
          }
          DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
            if copiedClipId == clip.id {
              withAnimation(.spring(response: 0.25, dampingFraction: 0.7)) {
                copiedClipId = nil
              }
            }
          }
        }) {
          HStack(spacing: 4) {
            Image(systemName: copiedClipId == clip.id ? "checkmark" : "doc.on.doc")
              .font(.system(size: 9))
              .contentTransition(.symbolEffect(.replace.downUp))
            Text(copiedClipId == clip.id ? "Copied" : "Copy")
              .font(.system(size: 10, weight: .medium))
          }
          .frame(maxWidth: .infinity)
          .padding(.vertical, 4)
          .background(copiedClipId == clip.id ? Color(red: 0.06, green: 0.72, blue: 0.51).opacity(0.12) : Color.primary.opacity(0.06))
          .foregroundColor(copiedClipId == clip.id ? Color(red: 0.06, green: 0.72, blue: 0.51) : .primary)
          .cornerRadius(4)
        }
        .buttonStyle(InteractivePillButtonStyle())
        .accessibilityLabel("Copy clip content")
        Button(action: {
          withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
            appState.togglePinClip(clip)
          }
        }) {
          Image(systemName: appState.pinnedClipIds.contains(clip.id) ? "pin.fill" : "pin")
            .font(.system(size: 9))
            .frame(width: 24, height: 22)
            .background(Color.primary.opacity(0.06))
            .foregroundColor(appState.pinnedClipIds.contains(clip.id) ? .accentColor : .primary)
            .cornerRadius(4)
        }
        .buttonStyle(InteractivePillButtonStyle())
        .accessibilityLabel(appState.pinnedClipIds.contains(clip.id) ? "Unpin clip" : "Pin clip")
        Button(action: {
          withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
            appState.deleteClip(clip)
            inspectingClip = nil
          }
        }) {
          Image(systemName: "trash")
            .font(.system(size: 9))
            .frame(width: 24, height: 22)
            .background(Color.primary.opacity(0.06))
            .foregroundColor(.secondary)
            .cornerRadius(4)
        }
        .buttonStyle(InteractivePillButtonStyle())
        .accessibilityLabel("Delete clip")
      }
    }
    .padding(10)
    .background(
      RoundedRectangle(cornerRadius: 8)
        .fill(colorScheme == .dark ? Color.primary.opacity(0.04) : Color(nsColor: .controlBackgroundColor))
        .overlay(
          RoundedRectangle(cornerRadius: 8)
            .stroke(colorScheme == .dark ? Color.primary.opacity(0.08) : Color(nsColor: .separatorColor).opacity(0.5), lineWidth: 1)
        )
    )
  }

  private func iconForType(_ type: ClipContentType) -> String {
    switch type {
    case .text: return "text.alignleft"
    case .url: return "link"
    case .image: return "photo"
    case .file: return "doc"
    }
  }
  private func deviceIcon(for name: String) -> String {
    let lower = name.lowercased()
    if lower.contains("tablet") || lower.contains("pad") || lower.contains("tab") {
      return "ipad"
    }
    return "smartphone"
  }
  private func friendlyDeviceName(for name: String) -> String {
    return name.trimmingCharacters(in: .whitespacesAndNewlines)
      .replacingOccurrences(of: "\"", with: "")
      .replacingOccurrences(of: "\\", with: "")
      .trimmingCharacters(in: .whitespacesAndNewlines)
  }
  private func isCodeSnippet(_ text: String) -> Bool {
    if text.contains("\n") && (text.contains("    ") || text.contains("\t")) {
      return true
    }
    let codeTokens = [
      "func ", "def ", "val ", "let ", "const ", "class ", "import ",
      "SELECT ", "FROM ", "WHERE ", "public ", "private ", "return ",
      "package ", "namespace ", "struct ", "interface ", "enum ", "=>",
    ]
    return codeTokens.contains { text.contains($0) }
  }
}

final class ThumbnailCache: @unchecked Sendable {
  static let shared = ThumbnailCache()
  private let cache = NSCache<NSUUID, NSImage>()

  private init() {
    cache.countLimit = 120
  }

  func thumbnail(for id: UUID, rawData: Data?) -> NSImage? {
    let key = id as NSUUID
    if let cached = cache.object(forKey: key) {
      return cached
    }
    guard let rawData else { return nil }
    guard let source = CGImageSourceCreateWithData(rawData as CFData, nil) else { return nil }
    let options: [CFString: Any] = [
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      kCGImageSourceShouldCacheImmediately: true,
      kCGImageSourceCreateThumbnailWithTransform: true,
      kCGImageSourceThumbnailMaxPixelSize: 80
    ]
    guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
    let thumb = NSImage(cgImage: cgImage, size: NSSize(width: 40, height: 40))
    cache.setObject(thumb, forKey: key)
    return thumb
  }
}
private final class URLAccumulator: @unchecked Sendable {
  private let lock = NSLock()
  private var urls: [URL] = []
  func append(_ url: URL) {
    lock.lock()
    urls.append(url)
    lock.unlock()
  }
  func get() -> [URL] {
    lock.lock()
    defer { lock.unlock() }
    return urls
  }
}
