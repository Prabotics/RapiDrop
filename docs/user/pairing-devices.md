# Pairing Your Devices

RapiDrop uses an interactive **Request → Accept / Decline** pairing model to establish a trusted connection between your devices.

## Step-by-Step Pairing Flow

```
Device A (Initiator)                    Device B (Receiver)
       │                                       │
  [ Radar Scan ]                         [ Radar Scan ]
       │                                       │
Tap Device B on Radar ───────────────► Incoming Pair Request
       │                               (Shows Name & 6-Digit Code)
       │                                       │
       │                                User Taps "Accept"
       │                                       │
Trusted Connection ◄─────────────────── Pair Confirmation
(Indicator turns Green)                 (Indicator turns Green)
```

1. **Open RapiDrop** on both devices and make sure both are connected to the same Wi-Fi router.
2. The **Radar** screen will automatically display nearby devices running RapiDrop.
3. Tap the device you want to pair with.
4. On the receiving device, an **Incoming Pairing Request** card will pop up showing the sender's device name and a 6-digit confirmation code.
5. Tap **Accept** to establish the pairing. Both devices will display a green **Connected** indicator.

## Short Authentication String (SAS) Verification

When an incoming pairing request arrives, both devices display a matching 6-digit numeric confirmation code.

This numeric code is cryptographically derived from the connection handshake. If you are pairing in an open environment, visually verify that the numbers match on both screens before tapping **Accept**.

## Declining or Cancelling

* If you tap **Decline** on the receiver, the connection is immediately terminated.
* If the sender taps **Cancel** before the receiver responds, the pairing prompt is dismissed on both sides.

## Automatic Reconnection

Once paired, your devices save a secure cryptographic key locally. Whenever both devices are on the same Wi-Fi network with RapiDrop open, they will automatically discover and reconnect to each other without needing to pair again.

To remove a paired device, open **Settings** and tap **Unpair**.
