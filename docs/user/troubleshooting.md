# User Troubleshooting Guide

If you experience difficulty discovering or connecting to your devices, check the common causes and solutions below.

## 1. Device Discovery Issues

### Devices do not appear on the Radar screen
* **Check Wi-Fi Network**: Ensure both devices are connected to the exact same Wi-Fi network and band (e.g. both on the same home router).
* **Wi-Fi Isolation / Guest Network**: If you are connected to a Guest Wi-Fi network, the router may have "Client Isolation" enabled, which blocks devices from talking to each other. Connect to the primary home/office network.
* **macOS Local Network Permission**: Open **System Settings → Privacy & Security → Local Network** and confirm that **RapiDrop** is toggled **ON**.
* **Windows Firewall Profile**: On Windows, check that your network is set to **Private Network** (not Public). Windows Firewall restricts local device discovery on public profiles.

## 2. Android Background Sync

### Copying text on Mac does not arrive on Android
* **Battery Optimization**: On Android, open **Settings → Apps → RapiDrop → Battery** and select **Unrestricted**. Some phone manufacturers aggressively freeze background apps unless unrestricted battery usage is granted.
* **Foreground Notification**: Ensure the RapiDrop status bar notification is active. Android requires this notification to keep background sync alive.

## 3. Pairing Issues

### Pairing times out or fails
* If pairing fails, click **Unpair** in Settings on both devices, restart RapiDrop, and tap the device on the radar again.
* Ensure both devices are awake and the screen is unlocked during the initial pairing handshake.
