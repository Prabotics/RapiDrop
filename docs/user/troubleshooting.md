# User Troubleshooting Guide

If you experience difficulty discovering or connecting to your devices, check the common causes and solutions below.

## 1. Device Discovery Issues

### Devices do not appear on the Radar screen
* **Check Wi-Fi Network**: Make sure both devices are connected to the same Wi-Fi network and band.
* **Wi-Fi Isolation / Guest Network**: If you are connected to a Guest Wi-Fi network, the router may have "Client Isolation" enabled, which blocks devices from talking to each other. Connect to the primary home or office network.
* **macOS Local Network Permission**: In macOS, open **System Settings → Privacy & Security → Local Network** and check that **RapiDrop** is toggled **ON**.

## 2. Android Background Sync

### Copying text on Mac does not arrive on Android
* **Battery Optimization**: On Android, open **Settings → Apps → RapiDrop → Battery** and select **Unrestricted**. Some phone manufacturers aggressively freeze background apps unless unrestricted battery usage is granted.
* **Foreground Notification**: Keep the RapiDrop status bar notification active. Android requires this notification to keep background sync alive.

## 3. Pairing Issues

### Pairing times out or fails
* If pairing fails, click **Unpair** in Settings on both devices, restart RapiDrop, and tap the device on the radar again.
* Make sure both devices are awake and unlocked during the initial pairing handshake.
