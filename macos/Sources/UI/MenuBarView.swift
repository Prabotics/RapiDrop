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
  public init(appState: AppState) {
    self.appState = appState
  }
  public var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      if isViewingHistory {
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
            .buttonStyle(InteractivePillButtonStyle())
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
            insertion: .opacity.combined(with: .offset(x: 14)),
            removal: .opacity.combined(with: .offset(x: 14))
          )
        )
      } else {
        VStack(alignment: .leading, spacing: 12) {
          headerSection

          if let toast = appState.toastMessage ?? syncToastMessage {
            syncToastBanner(toast)
          }

          if let errorMsg = appState.transferErrorMessage {
            transferErrorBanner(errorMsg)
          }
          if let progress = appState.activeTransferProgress {
            transferProgressCard(progress)
          }

          if let invite = appState.incomingPairInvite {
            incomingPairInviteCard(deviceName: invite.deviceName)
          } else if appState.isPaired {
            pairedDeviceCockpitSection
          } else if let selectedDevice = appState.selectedDeviceForPairing {
            pairingPinCard(device: selectedDevice)
          } else {
            discoveredDevicesSection
          }
        }
        .transition(
          .asymmetric(
            insertion: .opacity.combined(with: .offset(x: -14)),
            removal: .opacity.combined(with: .offset(x: -14))
          )
        )
      }
    }
    .padding(AppSpacing.spacing14)
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
      .spring(response: 0.35, dampingFraction: 0.8),
      value: appState.unpairedDiscoveredDevices.isEmpty
    )
    .animation(.spring(response: 0.3, dampingFraction: 0.8), value: appState.transferErrorMessage)
    .animation(.spring(response: 0.3, dampingFraction: 0.8), value: appState.toastMessage)
    .animation(.spring(response: 0.3, dampingFraction: 0.8), value: syncToastMessage)
    .animation(.spring(response: 0.35, dampingFraction: 0.84), value: isViewingHistory)
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
    .onAppear {
      appState.refreshDiscovery()
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

      Menu {
        Button(appState.showSyncHistory ? "Pause Clip History" : "Resume Clip History") {
          appState.showSyncHistory.toggle()
        }

        Divider()

        Menu("Media Storage") {
          Picker("Destination", selection: $appState.mediaDestinationMode) {
            Text("Both").tag(MediaDestinationMode.both)
            Text("Folder Only").tag(MediaDestinationMode.folderOnly)
            Text("Clipboard Only").tag(MediaDestinationMode.clipboardOnly)
          }

          Divider()

          Button("Open Received Folder in Finder") {
            let folder = appState.mediaFolderURL
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            NSWorkspace.shared.open(folder)
          }

          Button("Change Folder...") {
            selectCustomFolder()
          }
        }

        Toggle(
          "Sound Effects",
          isOn: Binding(
            get: { appState.isSoundEnabled },
            set: { appState.setSoundEnabled($0) }
          ))

        if #available(macOS 13.0, *) {
          Toggle(
            "Launch at Login",
            isOn: Binding(
              get: { appState.isLaunchAtLogin },
              set: { _ in appState.toggleLaunchAtLogin() }
            ))
        }

        Divider()

        Button("Check for Updates...") {
          appState.checkForUpdates()
        }

        Button("GitHub Repository") {
          if let url = URL(string: "https://github.com/Prabotics/RapiDrop") {
            NSWorkspace.shared.open(url)
          }
        }

        Button("Report an Issue...") {
          if let url = URL(string: "https://github.com/Prabotics/RapiDrop/issues")
          {
            NSWorkspace.shared.open(url)
          }
        }

        Divider()

        Button("Quit RapiDrop") {
          NSApplication.shared.terminate(nil)
        }
        .keyboardShortcut("q", modifiers: .command)
      } label: {
        Image(systemName: "gearshape")
          .font(.system(size: 13, weight: .medium))
          .foregroundColor(.secondary)
          .accessibilityLabel("Settings")
      }
      .menuIndicator(.hidden)
      .menuStyle(.borderlessButton)
      .fixedSize()
    }
  }
  private func brandmarkLogo(size: CGFloat = 20) -> some View {
    Group {
      if let nsImg = Bundle.main.image(forResource: "popover_logo") ?? NSImage(named: "popover_logo") {
        Image(nsImage: nsImg)
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
    VStack(alignment: .leading, spacing: AppSpacing.spacing10) {
      HStack(spacing: AppSpacing.spacing10) {
        ZStack {
          RoundedRectangle(cornerRadius: AppRadius.medium)
            .fill(AppColors.accent.opacity(colorScheme == .dark ? 0.15 : 0.12))
            .frame(width: 36, height: 36)
          Image(systemName: deviceIcon(for: friendlyDeviceName(for: deviceName)))
            .font(.system(size: 16, weight: .medium))
            .foregroundColor(AppColors.accent)
        }

        VStack(alignment: .leading, spacing: 2) {
          Text(friendlyDeviceName(for: deviceName))
            .font(AppTypography.deviceName)
            .foregroundColor(AppColors.textPrimary)
          Text("Wants to connect with this Mac")
            .font(AppTypography.bodySecondary)
            .foregroundColor(AppColors.textSecondary)
        }

        Spacer()
      }

      if let pin = appState.incomingPairInvite?.pin, !pin.isEmpty {
        let formattedPin = pin.count == 6 ? "\(pin.prefix(3)) \(pin.suffix(3))" : pin
        HStack {
          Text("Verification Code")
            .font(AppTypography.metadata)
            .foregroundColor(AppColors.textSecondary)
          Spacer()
          Text(formattedPin)
            .font(AppTypography.codeSnippet.weight(.bold))
            .foregroundColor(AppColors.statusConnected)
        }
        .padding(.horizontal, AppSpacing.spacing8)
        .padding(.vertical, AppSpacing.spacing4)
        .background(AppColors.containerSubtle(colorScheme))
        .cornerRadius(AppRadius.small)
      }

      HStack(spacing: AppSpacing.spacing8) {
        Button(action: {
          withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
            appState.declinePairInvite()
          }
        }) {
          Text("Decline")
            .font(AppTypography.bodySecondary)
            .foregroundColor(AppColors.textSecondary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 5)
            .background(AppColors.surfaceHover(colorScheme))
            .cornerRadius(AppRadius.medium)
            .overlay(
              RoundedRectangle(cornerRadius: AppRadius.medium)
                .stroke(AppColors.surfaceBorder(colorScheme), lineWidth: 1)
            )
        }
        .buttonStyle(InteractivePillButtonStyle())

        Button(action: {
          withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
            appState.acceptPairInvite()
          }
        }) {
          Text("Accept")
            .font(AppTypography.bodySecondary.weight(.semibold))
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 5)
            .background(AppColors.accent)
            .cornerRadius(AppRadius.medium)
        }
        .buttonStyle(InteractivePillButtonStyle())
      }
    }
    .padding(AppSpacing.spacing12)
    .background(
      RoundedRectangle(cornerRadius: AppRadius.card)
        .fill(AppColors.surfaceCard(colorScheme))
        .overlay(
          RoundedRectangle(cornerRadius: AppRadius.card)
            .stroke(AppColors.accent.opacity(colorScheme == .dark ? 0.35 : 0.5), lineWidth: 1)
        )
    )
    .transition(
      .asymmetric(
        insertion: .opacity.combined(with: .scale(scale: 0.98, anchor: .top)),
        removal: .opacity.combined(with: .scale(scale: 0.98, anchor: .top))
      ))
  }

  private func pairingPinCard(device: DiscoveredClientDevice) -> some View {
    VStack(alignment: .leading, spacing: AppSpacing.spacing10) {
      HStack(spacing: AppSpacing.spacing10) {
        ZStack {
          RoundedRectangle(cornerRadius: AppRadius.medium)
            .fill(AppColors.statusConnected.opacity(colorScheme == .dark ? 0.15 : 0.12))
            .frame(width: 36, height: 36)
          Image(systemName: deviceIcon(for: friendlyDeviceName(for: device.name)))
            .font(.system(size: 16, weight: .medium))
            .foregroundColor(AppColors.statusConnected)
        }

        VStack(alignment: .leading, spacing: 2) {
          Text("Connecting to \(friendlyDeviceName(for: device.name))")
            .font(AppTypography.deviceName)
            .foregroundColor(AppColors.textPrimary)
            .lineLimit(1)

          if let pin = appState.pendingPairingSasCode, !pin.isEmpty {
            let formatted = pin.count == 6 ? "\(pin.prefix(3)) \(pin.suffix(3))" : pin
            HStack(spacing: 6) {
              Text("Verification code:")
                .font(AppTypography.bodySecondary)
                .foregroundColor(AppColors.textSecondary)
              Text(formatted)
                .font(.system(size: 13, weight: .bold, design: .monospaced))
                .foregroundColor(AppColors.textPrimary)
            }
            .transition(.opacity.combined(with: .scale(scale: 0.96, anchor: .leading)))
          } else {
            HStack(spacing: 5) {
              Circle()
                .fill(AppColors.statusConnected)
                .frame(width: 5, height: 5)
              Text("Waiting for approval on \(friendlyDeviceName(for: device.name))...")
                .font(AppTypography.bodySecondary)
                .foregroundColor(AppColors.textSecondary)
                .lineLimit(1)
            }
            .transition(.opacity)
          }
        }
        Spacer()
      }
      Button("Cancel") {
        withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
          appState.cancelPairingSelection()
        }
      }
      .font(AppTypography.bodySecondary)
      .foregroundColor(AppColors.textSecondary)
      .frame(maxWidth: .infinity)
      .padding(.vertical, 5)
      .background(AppColors.surfaceHover(colorScheme))
      .cornerRadius(AppRadius.medium)
      .overlay(
        RoundedRectangle(cornerRadius: AppRadius.medium)
          .stroke(AppColors.surfaceBorder(colorScheme), lineWidth: 1)
      )
      .buttonStyle(InteractivePillButtonStyle())
    }
    .padding(AppSpacing.spacing12)
    .background(
      RoundedRectangle(cornerRadius: AppRadius.card)
        .fill(AppColors.surfaceCard(colorScheme))
        .overlay(
          RoundedRectangle(cornerRadius: AppRadius.card)
            .stroke(AppColors.surfaceBorder(colorScheme), lineWidth: 1)
        )
    )
    .transition(
      .asymmetric(
        insertion: .opacity.combined(with: .scale(scale: 0.98, anchor: .top)),
        removal: .opacity.combined(with: .scale(scale: 0.98, anchor: .top))
      ))
  }
  private var discoveredDevicesSection: some View {
    VStack(alignment: .leading, spacing: AppSpacing.spacing8) {
      Text("Nearby devices")
        .font(AppTypography.sectionTitle)
        .foregroundColor(AppColors.textSecondary)

      if appState.unpairedDiscoveredDevices.isEmpty {
        VStack(spacing: AppSpacing.spacing6) {
          Image(systemName: "wifi")
            .font(.system(size: 16))
            .foregroundColor(AppColors.textMuted)
          Text("No nearby devices found")
            .font(AppTypography.bodySecondary.weight(.medium))
            .foregroundColor(AppColors.textSecondary)
          Text("Make sure RapiDrop is open on your other device and connected to the same Wi-Fi network.")
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
      } else {
        VStack(spacing: AppSpacing.spacing6) {
          ForEach(appState.unpairedDiscoveredDevices) { device in
            HStack(spacing: AppSpacing.spacing10) {
              ZStack {
                RoundedRectangle(cornerRadius: AppRadius.medium)
                  .fill(AppColors.surfaceHover(colorScheme))
                  .frame(width: 32, height: 32)
                Image(systemName: deviceIcon(for: device.name))
                  .font(.system(size: 14))
                  .foregroundColor(AppColors.textPrimary)
              }

              VStack(alignment: .leading, spacing: 2) {
                Text(friendlyDeviceName(for: device.name))
                  .font(AppTypography.deviceName)
                  .foregroundColor(AppColors.textPrimary)
                  .lineLimit(1)
                  .truncationMode(.tail)
                Text("Local Wi-Fi")
                  .font(AppTypography.metadata)
                  .foregroundColor(AppColors.textSecondary)
                  .lineLimit(1)
                  .truncationMode(.tail)
              }

              Spacer()

              Button("Connect") {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                  appState.selectDeviceForPairing(device)
                }
              }
              .font(AppTypography.bodySecondary.weight(.semibold))
              .foregroundColor(AppColors.textPrimary)
              .padding(.horizontal, AppSpacing.spacing10)
              .padding(.vertical, 4)
              .background(AppColors.surfaceHover(colorScheme))
              .cornerRadius(AppRadius.medium)
              .overlay(
                RoundedRectangle(cornerRadius: AppRadius.medium)
                  .stroke(AppColors.surfaceBorder(colorScheme), lineWidth: 1)
              )
              .buttonStyle(InteractivePillButtonStyle())
              .fixedSize()
              .layoutPriority(1)
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
              Text("Items you copy or receive will appear here.")
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
    let pinned = filtered.filter { appState.pinnedClipIds.contains($0.id) }
    let unpinned = filtered.filter { !appState.pinnedClipIds.contains($0.id) }
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
            isCodeSnippet(clip.previewText)
              ? .system(size: 11, design: .monospaced) : .system(size: 11)
          )
          .lineLimit(1)
          .truncationMode(.tail)

        HStack(spacing: 4) {
          if isCodeSnippet(clip.previewText) {
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
    if lower.contains("mac") || lower.contains("book") {
      return "laptopcomputer"
    } else if lower.contains("pc") || lower.contains("windows") || lower.contains("desktop") {
      return "display"
    } else if lower.contains("ipad") || lower.contains("tablet") {
      return "ipad"
    } else {
      return "smartphone"
    }
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
