# Clipboard Synchronization

RapiDrop provides instant, end-to-end encrypted clipboard synchronization across your paired devices.

## Supported Content Types

* **Plain Text**: Code snippets, notes, messages, commands.
* **URLs**: Web links, deep links.
* **Images**: Screenshots, PNG/JPEG images copied from photo apps or browsers.

## How It Works on Each Platform

### macOS & Windows (Continuous Sync)
On desktop platforms, RapiDrop automatically monitors your system clipboard. When you copy something on your Mac or PC, RapiDrop immediately encrypts it and sends it to your connected device.

When clipboard data arrives from another device, RapiDrop writes it directly to your system pasteboard, so you can press `Cmd+V` (Mac) or `Ctrl+V` (Windows) right away.

### Android (Push Model)
Due to Android operating system privacy restrictions, background apps cannot continuously read the clipboard. To share your Android clipboard to your computer:

1. **Quick Settings Tile**: Pull down your Android notification shade and tap the **Send Clipboard** tile.
2. **System Share Sheet**: In any Android app (browser, gallery, notes), tap **Share** and select **RapiDrop**.
3. **In-App Action**: Tap the **Send Clipboard** button on the RapiDrop main screen.

When your computer sends clipboard content to Android, RapiDrop receives it automatically in the background and shows a confirmation toast.

## Privacy & Sensitive Data Protection

RapiDrop automatically ignores clips marked as sensitive by password managers (such as 1Password, Bitwarden, KeePass, and macOS Keychain). Transient passwords and auto-generated credentials will not be synced across your network.

## Direct Mode (No History)

If you prefer that clipboard clips are not saved in RapiDrop's recent history feed on your device, toggle **Direct Mode** in Settings. Data will be pasted directly into the system clipboard and discarded from memory immediately.
