# Privacy & Security Overview

RapiDrop keeps your personal data on your local network.

## Frequently Asked Questions

### Does RapiDrop use the cloud?
**No.** RapiDrop never connects to external servers, relay networks, or cloud storage. All communication happens strictly over your local Wi-Fi router or hotspot.

### Is my data encrypted?
**Yes.** All clipboard text, images, and files are encrypted end-to-end using **AES-256-GCM** encryption with unique random keys generated during each connection. Even on an open or public Wi-Fi network, other devices on the subnet cannot read your transferred data.

### Does RapiDrop collect telemetry or analytics?
**No.** RapiDrop has zero tracking, zero telemetry, and zero third-party analytical SDKs.

### What permissions does RapiDrop require?
* **Local Network (macOS/Android)**: Needed to discover and connect to your other devices on your Wi-Fi network.
* **Notifications (Android)**: Required by Android 13+ to run the background synchronization service and notify you when a clip or file is received.
* **Storage (Android)**: **Zero storage permissions required.** RapiDrop uses modern Android Scoped Storage (`MediaStore.Downloads`) to save received files directly to your Downloads folder without asking for broad photo or file system access.

### How does device pairing protect me?
A new device cannot connect to your computer or phone without explicit permission. When an unknown device attempts to pair, RapiDrop shows a prompt on your screen with the device name and a 6-digit confirmation code. You must tap **Accept** before any data can be exchanged.
