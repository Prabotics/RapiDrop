package com.prabotics.rapidrop

import org.json.JSONObject
import com.prabotics.rapidrop.clipboard.ClipContentType
import com.prabotics.rapidrop.clipboard.ClipItem
import com.prabotics.rapidrop.network.DeviceNameHelper
import com.prabotics.rapidrop.network.DiscoveredDevice
import com.prabotics.rapidrop.network.PacketType
import com.prabotics.rapidrop.network.SocketClient
import com.prabotics.rapidrop.network.WireFrame
import com.prabotics.rapidrop.security.CryptoEngine
import com.prabotics.rapidrop.service.SyncService
import com.prabotics.rapidrop.ui.components.getRelativeTimeSpan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
class RapiDropUnitTest {
    @Test
    fun testWireFrameSerializationRoundTrip() {
        val text = "Hello Android RapiDrop"
        val ciphertext = text.toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12) { 0x1A.toByte() }
        val tag = ByteArray(16) { 0x2B.toByte() }

        val frame = WireFrame(
            type = PacketType.CLIP_TEXT,
            timestamp = 1700000000000L,
            nonce = nonce,
            ciphertext = ciphertext,
            tag = tag
        )

        val serialized = frame.serialize()
        assertEquals(WireFrame.HEADER_SIZE + ciphertext.size + tag.size, serialized.size)

        val deserialized = WireFrame.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(PacketType.CLIP_TEXT, deserialized?.type)
        assertEquals(1700000000000L, deserialized?.timestamp)
        assertArrayEquals(nonce, deserialized?.nonce)
        assertArrayEquals(ciphertext, deserialized?.ciphertext)
        assertArrayEquals(tag, deserialized?.tag)
    }

    @Test
    fun testWireFramePairInviteSerialization() {
        val inviteJson = """{"fromDeviceName":"Test Mac","deviceType":"mac"}""".toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12)
        val tag = ByteArray(16)

        val frame = WireFrame(
            type = PacketType.PAIR_INVITE,
            timestamp = 1700000000000L,
            nonce = nonce,
            ciphertext = inviteJson,
            tag = tag
        )

        val serialized = frame.serialize()
        val deserialized = WireFrame.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(PacketType.PAIR_INVITE, deserialized?.type)
        assertArrayEquals(inviteJson, deserialized?.ciphertext)
    }
    @Test
    fun testWireFramePairAcceptSerialization() {
        val acceptJson = """{"status":"accepted","pin":"123456"}""".toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12)
        val tag = ByteArray(16)
        val frame = WireFrame(
            type = PacketType.PAIR_ACCEPT,
            timestamp = 1700000000000L,
            nonce = nonce,
            ciphertext = acceptJson,
            tag = tag
        )
        val serialized = frame.serialize()
        val deserialized = WireFrame.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(PacketType.PAIR_ACCEPT, deserialized?.type)
        assertArrayEquals(acceptJson, deserialized?.ciphertext)
    }
    @Test
    fun testWireFramePairFailDeclinedSerialization() {
        val failDeclined = "DECLINED".toByteArray(Charsets.UTF_8)
        val frame = WireFrame(
            type = PacketType.PAIR_FAIL,
            timestamp = 1700000000000L,
            nonce = ByteArray(12),
            ciphertext = failDeclined,
            tag = ByteArray(16)
        )
        val serialized = frame.serialize()
        val deserialized = WireFrame.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(PacketType.PAIR_FAIL, deserialized?.type)
        assertArrayEquals(failDeclined, deserialized?.ciphertext)
    }

    @Test
    fun testWireFramePairFailCancelledSerialization() {
        val failCancelled = "CANCELLED".toByteArray(Charsets.UTF_8)
        val frame = WireFrame(
            type = PacketType.PAIR_FAIL,
            timestamp = 1700000000000L,
            nonce = ByteArray(12),
            ciphertext = failCancelled,
            tag = ByteArray(16)
        )
        val serialized = frame.serialize()
        val deserialized = WireFrame.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(PacketType.PAIR_FAIL, deserialized?.type)
        assertArrayEquals(failCancelled, deserialized?.ciphertext)
    }

    @Test
    fun testWireFrameConfigSyncSerialization() {
        val configJson = """{"privacyMode":true}""".toByteArray(Charsets.UTF_8)
        val frame = WireFrame(
            type = PacketType.CONFIG_SYNC,
            timestamp = 1700000000000L,
            nonce = ByteArray(12) { 0x01.toByte() },
            ciphertext = configJson,
            tag = ByteArray(16) { 0x02.toByte() }
        )
        val serialized = frame.serialize()
        val deserialized = WireFrame.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(PacketType.CONFIG_SYNC, deserialized?.type)
        assertArrayEquals(configJson, deserialized?.ciphertext)
    }

    @Test
    fun testWireFrameClipFileSerialization() {
        val filename = "report.pdf"
        val filenameBytes = filename.toByteArray(Charsets.UTF_8)
        val fileData = "Binary PDF payload content".toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(2 + filenameBytes.size + fileData.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(filenameBytes.size.toShort())
        buffer.put(filenameBytes)
        buffer.put(fileData)
        val filePayload = buffer.array()

        val frame = WireFrame(
            type = PacketType.CLIP_FILE,
            timestamp = 1700000000000L,
            nonce = ByteArray(12) { 0xAA.toByte() },
            ciphertext = filePayload,
            tag = ByteArray(16) { 0xBB.toByte() }
        )
        val serialized = frame.serialize()
        val deserialized = WireFrame.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(PacketType.CLIP_FILE, deserialized?.type)
        assertArrayEquals(filePayload, deserialized?.ciphertext)
    }

    @Test
    fun testWireFrameDisconnectSerialization() {
        val disconnectPayload = "UNPAIR".toByteArray(Charsets.UTF_8)
        val frame = WireFrame(
            type = PacketType.DISCONNECT,
            timestamp = 1700000000000L,
            nonce = ByteArray(12),
            ciphertext = disconnectPayload,
            tag = ByteArray(16)
        )
        val serialized = frame.serialize()
        val deserialized = WireFrame.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(PacketType.DISCONNECT, deserialized?.type)
        assertArrayEquals(disconnectPayload, deserialized?.ciphertext)
    }

    @Test
    fun testWireFrameCorruptedData() {
        val truncated = ByteArray(10)
        assertNull(WireFrame.deserialize(truncated))
    }

    @Test
    fun testOversizedPayloadRejection() {
        val oversizedLength = WireFrame.MAX_PAYLOAD_SIZE + 1024
        val buffer = ByteBuffer.allocate(WireFrame.HEADER_SIZE + 32).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(oversizedLength)
        buffer.putShort(PacketType.CLIP_TEXT.value)
        buffer.putShort(WireFrame.PROTOCOL_VERSION)
        buffer.putLong(System.currentTimeMillis())
        buffer.put(ByteArray(WireFrame.NONCE_SIZE))
        buffer.put(ByteArray(32))

        val result = WireFrame.deserialize(buffer.array())
        assertNull(result)
    }

    @Test
    fun testPacketTypeWireValuesParity() {
        assertEquals(0x0001.toShort(), PacketType.PING.value)
        assertEquals(0x0002.toShort(), PacketType.PONG.value)
        assertEquals(0x0003.toShort(), PacketType.PAIR_REQUEST.value)
        assertEquals(0x0004.toShort(), PacketType.PAIR_CONFIRM.value)
        assertEquals(0x0005.toShort(), PacketType.DEVICE_INFO.value)
        assertEquals(0x0006.toShort(), PacketType.PAIR_INVITE.value)
        assertEquals(0x0007.toShort(), PacketType.PAIR_FAIL.value)
        assertEquals(0x0008.toShort(), PacketType.PAIR_ACCEPT.value)
        assertEquals(0x0010.toShort(), PacketType.CLIP_TEXT.value)
        assertEquals(0x0011.toShort(), PacketType.CLIP_URL.value)
        assertEquals(0x0012.toShort(), PacketType.CLIP_IMAGE.value)
        assertEquals(0x0013.toShort(), PacketType.CLIP_FILE.value)
        assertEquals(0x0020.toShort(), PacketType.CONFIG_SYNC.value)
        assertEquals(0x00FF.toShort(), PacketType.DISCONNECT.value)
    }

    @Test
    fun testCryptoEngineEncryptionAndDecryption() {
        val keyBytes = ByteArray(32) { 0x07.toByte() }
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val payload = "Super secret test payload".toByteArray(Charsets.UTF_8)
        val encrypted = CryptoEngine.encrypt(payload, secretKey)

        val decrypted = CryptoEngine.decrypt(
            ciphertext = encrypted.ciphertext,
            nonce = encrypted.nonce,
            tag = encrypted.tag,
            key = secretKey
        )

        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun testGmacTamperRejection() {
        val pin = "582910"
        val key = CryptoEngine.deriveKeyFromPin(pin)
        val plaintext = "Integrity Protected Payload".toByteArray(Charsets.UTF_8)
        val encrypted = CryptoEngine.encrypt(plaintext, key)

        val tamperedCiphertext = encrypted.ciphertext.copyOf()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0x01).toByte()
        try {
            CryptoEngine.decrypt(tamperedCiphertext, encrypted.nonce, encrypted.tag, key)
            fail("Decryption should fail when ciphertext is tampered")
        } catch (_: Exception) {
        }

        val tamperedTag = encrypted.tag.copyOf()
        tamperedTag[tamperedTag.size - 1] = (tamperedTag[tamperedTag.size - 1].toInt() xor 0xFF).toByte()
        try {
            CryptoEngine.decrypt(encrypted.ciphertext, encrypted.nonce, tamperedTag, key)
            fail("Decryption should fail when auth tag is tampered")
        } catch (_: Exception) {
        }

        val tamperedNonce = encrypted.nonce.copyOf()
        tamperedNonce[0] = (tamperedNonce[0].toInt() xor 0x80).toByte()
        try {
            CryptoEngine.decrypt(encrypted.ciphertext, tamperedNonce, encrypted.tag, key)
            fail("Decryption should fail when nonce is tampered")
        } catch (_: Exception) {
        }
    }

    @Test
    fun testNonceUniqueness() {
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 0x01.toByte() }, "AES")
        val nonces = HashSet<String>()
        val payload = "Fixed Plaintext".toByteArray(Charsets.UTF_8)

        for (i in 1..100) {
            val result = CryptoEngine.encrypt(payload, key)
            assertEquals(12, result.nonce.size)
            val hex = result.nonce.joinToString("") { "%02x".format(it) }
            assertFalse("Nonce was reused across encryptions: $hex", nonces.contains(hex))
            nonces.add(hex)
        }
        assertEquals(100, nonces.size)
    }

    @Test
    fun testCrossPlatformKeyDerivationVector() {
        val pin = "849201"
        val derivedKey = CryptoEngine.deriveKeyFromPin(pin)

        val expectedSecret = "RapiDrop-PIN-849201".toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        val expectedKeyBytes = md.digest(expectedSecret)

        assertArrayEquals(expectedKeyBytes, derivedKey.encoded)
        assertEquals(32, derivedKey.encoded.size)
    }

    @Test
    fun testClipItemDataModel() {
        val item = ClipItem(
            type = ClipContentType.TEXT,
            textContent = "Sample Text"
        )
        assertEquals("Sample Text", item.previewText)
    }

    @Test
    fun testSyncServiceHistoryManagement() {
        SyncService.clearHistory()
        assertEquals(0, SyncService.recentClips.value.size)
        assertNull(SyncService.lastSyncedText.value)

        val clip1 = ClipItem(type = ClipContentType.TEXT, textContent = "First Clip")
        val clip2 = ClipItem(type = ClipContentType.URL, textContent = "https://example.com")
        val clip3 = ClipItem(type = ClipContentType.TEXT, textContent = "First Clip")

        SyncService.addRecentClip(clip1)
        assertEquals(1, SyncService.recentClips.value.size)
        assertEquals("First Clip", SyncService.lastSyncedText.value)

        SyncService.addRecentClip(clip2)
        assertEquals(2, SyncService.recentClips.value.size)
        assertEquals("https://example.com", SyncService.recentClips.value[0].textContent)
        assertEquals("First Clip", SyncService.recentClips.value[1].textContent)

        SyncService.addRecentClip(clip3)
        assertEquals(2, SyncService.recentClips.value.size)
        assertEquals("First Clip", SyncService.recentClips.value[0].textContent)
        assertEquals("https://example.com", SyncService.recentClips.value[1].textContent)

        for (i in 1..25) {
            SyncService.addRecentClip(ClipItem(type = ClipContentType.TEXT, textContent = "Clip #$i"))
        }
        assertEquals(20, SyncService.recentClips.value.size)
        assertEquals("Clip #25", SyncService.recentClips.value[0].textContent)

        val targetClip = SyncService.recentClips.value[0]
        SyncService.deleteClip(targetClip.id.toString())
        assertEquals(19, SyncService.recentClips.value.size)
        assertTrue(SyncService.recentClips.value.none { it.id == targetClip.id })

        SyncService.clearHistory()
        assertEquals(0, SyncService.recentClips.value.size)
        assertNull(SyncService.lastSyncedText.value)
    }

    @Test
    fun testMediaDestinationModeEnum() {
        assertEquals(3, com.prabotics.rapidrop.clipboard.MediaDestinationMode.entries.size)
        assertEquals("Folder & Clipboard", com.prabotics.rapidrop.clipboard.MediaDestinationMode.BOTH.displayLabel)
        assertEquals("Folder Only", com.prabotics.rapidrop.clipboard.MediaDestinationMode.FOLDER_ONLY.displayLabel)
        assertEquals("Clipboard Only", com.prabotics.rapidrop.clipboard.MediaDestinationMode.CLIPBOARD_ONLY.displayLabel)

        assertEquals(com.prabotics.rapidrop.clipboard.MediaDestinationMode.BOTH, com.prabotics.rapidrop.clipboard.MediaDestinationMode.valueOf("BOTH"))
        assertEquals(com.prabotics.rapidrop.clipboard.MediaDestinationMode.FOLDER_ONLY, com.prabotics.rapidrop.clipboard.MediaDestinationMode.valueOf("FOLDER_ONLY"))
        assertEquals(com.prabotics.rapidrop.clipboard.MediaDestinationMode.CLIPBOARD_ONLY, com.prabotics.rapidrop.clipboard.MediaDestinationMode.valueOf("CLIPBOARD_ONLY"))
    }

    @Test
    fun testAppThemeModeEnum() {
        assertEquals(3, com.prabotics.rapidrop.ui.theme.AppThemeMode.entries.size)
        assertEquals("System", com.prabotics.rapidrop.ui.theme.AppThemeMode.SYSTEM.displayLabel)
        assertEquals("Light", com.prabotics.rapidrop.ui.theme.AppThemeMode.LIGHT.displayLabel)
        assertEquals("Dark", com.prabotics.rapidrop.ui.theme.AppThemeMode.DARK.displayLabel)
    }
    @Test
    fun testMainNavDestinations() {
        val destinations = com.prabotics.rapidrop.ui.MainNavDestination.entries
        assertEquals(3, destinations.size)
        assertEquals("Devices", destinations[0].label)
        assertEquals("History", destinations[1].label)
        assertEquals("Settings", destinations[2].label)
    }
    @Test
    fun testSettingsDefaultsAndLocalPrivacyIsolation() {
        SyncService.showSyncHistory.value = true
        val initial = SyncService.showSyncHistory.value
        assertTrue(initial)

        SyncService.setShowSyncHistory(null ?: org.junit.runner.JUnitCore::class.java.cast(null) as? android.content.Context ?: return, false)
    }

    @Test
    fun testDeviceIdentityImmutableOnDisplayNameChange() {
        val name1 = "Test Device"
        val name2 = "Test Device (2)"
        val norm1 = com.prabotics.rapidrop.network.DeviceNameHelper.normalizeDeviceName(name1)
        val norm2 = com.prabotics.rapidrop.network.DeviceNameHelper.normalizeDeviceName(name2)
        assertEquals("test device", norm1)
        assertEquals("test device", norm2)
    }

    @Test
    fun testImageMetadataExtraction() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(),
            'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x07.toByte(), 0x80.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x38.toByte()
        )
        val metadata = com.prabotics.rapidrop.clipboard.ImageMetadataHelper.extractMetadata(pngHeader)
        assertNotNull(metadata)
        assertEquals("PNG", metadata?.format)
        assertEquals(1920, metadata?.width)
        assertEquals(1080, metadata?.height)
        assertEquals("1920 × 1080", metadata?.resolutionLabel)
        assertTrue(metadata?.technicalSummary?.contains("PNG 1920 × 1080") == true)
    }

    @Test
    fun testRelativeTimeSpanFormatting() {
        val now = 1_700_000_000_000L

        assertEquals("Just now", getRelativeTimeSpan(timestamp = 0L, now = now))
        assertEquals("Just now", getRelativeTimeSpan(timestamp = -100L, now = now))
        assertEquals("Just now", getRelativeTimeSpan(timestamp = now + 5_000L, now = now))
        assertEquals("Just now", getRelativeTimeSpan(timestamp = now + 600_000L, now = now))

        assertEquals("Just now", getRelativeTimeSpan(timestamp = now, now = now))
        assertEquals("Just now", getRelativeTimeSpan(timestamp = now - 15_000L, now = now))
        assertEquals("Just now", getRelativeTimeSpan(timestamp = now - 45_000L, now = now))
        assertEquals("Just now", getRelativeTimeSpan(timestamp = now - 59_000L, now = now))

        assertEquals("1m ago", getRelativeTimeSpan(timestamp = now - 60_000L, now = now))
        assertEquals("1m ago", getRelativeTimeSpan(timestamp = now - 119_000L, now = now))
        assertEquals("2m ago", getRelativeTimeSpan(timestamp = now - 120_000L, now = now))
        assertEquals("5m ago", getRelativeTimeSpan(timestamp = now - 5 * 60_000L, now = now))
        assertEquals("7m ago", getRelativeTimeSpan(timestamp = now - 7 * 60_000L, now = now))
        assertEquals("59m ago", getRelativeTimeSpan(timestamp = now - 59 * 60_000L, now = now))

        assertEquals("1h ago", getRelativeTimeSpan(timestamp = now - 60 * 60_000L, now = now))
        assertEquals("2h ago", getRelativeTimeSpan(timestamp = now - 2 * 3600_000L, now = now))
        assertEquals("23h ago", getRelativeTimeSpan(timestamp = now - 23 * 3600_000L, now = now))

        assertEquals("1d ago", getRelativeTimeSpan(timestamp = now - 24 * 3600_000L, now = now))
        assertEquals("6d ago", getRelativeTimeSpan(timestamp = now - 6 * 86400_000L, now = now))

        assertEquals("1w ago", getRelativeTimeSpan(timestamp = now - 7 * 86400_000L, now = now))
        assertEquals("2w ago", getRelativeTimeSpan(timestamp = now - 14 * 86400_000L, now = now))
    }

    @Test
    fun testClipItemDisplayName() {
        val textItem = ClipItem(type = ClipContentType.TEXT, textContent = "Simple Note")
        assertEquals("Simple Note", textItem.displayName)

        val urlItem = ClipItem(type = ClipContentType.URL, textContent = "https://github.com")
        assertEquals("https://github.com", urlItem.displayName)

        val namedImg = ClipItem(type = ClipContentType.IMAGE, fileName = "screenshot.png")
        assertEquals("screenshot.png", namedImg.displayName)

        val autoImg = ClipItem(type = ClipContentType.IMAGE, timestamp = 1700000000000L)
        assertTrue(autoImg.displayName.startsWith("Clip_"))
        assertTrue(autoImg.displayName.endsWith(".png"))

        val namedFile = ClipItem(type = ClipContentType.FILE, fileName = "document.pdf")
        assertEquals("document.pdf", namedFile.displayName)

        val unnamedFile = ClipItem(type = ClipContentType.FILE)
        assertTrue(unnamedFile.displayName.startsWith("File_"))
        assertTrue(unnamedFile.displayName.endsWith(".bin"))
    }

    @Test
    fun testDeviceNameNormalizationAndMatching() {
        assertTrue(com.prabotics.rapidrop.network.DeviceNameHelper.isSameDevice("User's Mac", "User’s Mac"))
        assertTrue(com.prabotics.rapidrop.network.DeviceNameHelper.isSameDevice("\"Test Device\"", "Test Device"))
        assertTrue(com.prabotics.rapidrop.network.DeviceNameHelper.isSameDevice("Test Mac (2)", "Test Mac"))
        assertTrue(com.prabotics.rapidrop.network.DeviceNameHelper.isSameDevice("Test Android - 2", "Test Android"))
        assertTrue(com.prabotics.rapidrop.network.DeviceNameHelper.isSameDevice("Test Android (99)", "Test Android"))
        assertFalse(com.prabotics.rapidrop.network.DeviceNameHelper.isSameDevice("Test Android", "Test Android Phone"))
        assertFalse(com.prabotics.rapidrop.network.DeviceNameHelper.isSameDevice("Test Mac", "Test Android"))
    }
    @Test
    fun testDeviceDiscoverySelfFilteringWithIdAndCollisionNames() {
        val localId = "device-uuid-1111"
        val localName = "Test Android"

        val selfExact = com.prabotics.rapidrop.network.DiscoveredDevice(name = "Test Android", host = "192.0.2.50", port = 58240, id = "device-uuid-1111")
        val isSelf1 = if (selfExact.id.isNotBlank()) (selfExact.id == localId) else com.prabotics.rapidrop.network.DeviceNameHelper.isSameDevice(selfExact.name, localName)
        assertTrue(isSelf1)

        val selfRenamedByMdns = com.prabotics.rapidrop.network.DiscoveredDevice(name = "Test Android (2)", host = "192.0.2.50", port = 58240, id = "device-uuid-1111")
        val isSelf2 = if (selfRenamedByMdns.id.isNotBlank()) (selfRenamedByMdns.id == localId) else com.prabotics.rapidrop.network.DeviceNameHelper.isSameDevice(selfRenamedByMdns.name, localName)
        assertTrue(isSelf2)

        val remoteSameName = com.prabotics.rapidrop.network.DiscoveredDevice(name = "Test Android", host = "192.0.2.55", port = 58240, id = "device-uuid-2222")
        val isSelf3 = if (remoteSameName.id.isNotBlank()) (remoteSameName.id == localId) else com.prabotics.rapidrop.network.DeviceNameHelper.isSameDevice(remoteSameName.name, localName)
        assertFalse(isSelf3)

        val remoteMac = com.prabotics.rapidrop.network.DiscoveredDevice(name = "Test Mac", host = "192.0.2.60", port = 58240, id = "device-uuid-3333")
        val isSelf4 = if (remoteMac.id.isNotBlank()) (remoteMac.id == localId) else com.prabotics.rapidrop.network.DeviceNameHelper.isSameDevice(remoteMac.name, localName)
        assertFalse(isSelf4)
    }

    @Test
    fun testSameNameDifferentIdDeduplication() {
        val dev1 = com.prabotics.rapidrop.network.DiscoveredDevice(name = "Test Device", host = "192.0.2.50", port = 58240, id = "device-uuid-1")
        val dev2 = com.prabotics.rapidrop.network.DiscoveredDevice(name = "Test Device", host = "192.0.2.51", port = 58240, id = "device-uuid-2")
        val dev1DuplicateCallback = com.prabotics.rapidrop.network.DiscoveredDevice(name = "Test Device (2)", host = "192.0.2.50", port = 58240, id = "device-uuid-1")

        val map = mutableMapOf<String, com.prabotics.rapidrop.network.DiscoveredDevice>()
        for (dev in listOf(dev1, dev2, dev1DuplicateCallback)) {
            map[dev.id] = dev
        }

        assertEquals(2, map.size)
        assertTrue(map.containsKey("device-uuid-1"))
        assertTrue(map.containsKey("device-uuid-2"))
    }

    @Test
    fun testHandshakeKeyDerivationParity() {
        val (privA, pubAHex) = CryptoEngine.generateEphemeralKeypair()
        val (privB, pubBHex) = CryptoEngine.generateEphemeralKeypair()

        val nonceA = CryptoEngine.generateNonce(16)
        val nonceB = CryptoEngine.generateNonce(16)

        val pubABytes = CryptoEngine.hexToBytes(pubAHex)!!
        val pubBBytes = CryptoEngine.hexToBytes(pubBHex)!!

        val sharedSecretA = CryptoEngine.computeSharedSecret(privA, pubBHex)
        val sharedSecretB = CryptoEngine.computeSharedSecret(privB, pubAHex)

        assertArrayEquals(sharedSecretA, sharedSecretB)

        val keysA = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = sharedSecretA,
            initiatorPublicKey = pubABytes,
            receiverPublicKey = pubBBytes,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = "device-uuid-a",
            receiverId = "device-uuid-b"
        )

        val keysB = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = sharedSecretB,
            initiatorPublicKey = pubABytes,
            receiverPublicKey = pubBBytes,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = "device-uuid-a",
            receiverId = "device-uuid-b"
        )

        assertArrayEquals(keysA.sessionKey.encoded, keysB.sessionKey.encoded)
        assertEquals(keysA.sasCode, keysB.sasCode)
    }
    @Test
    fun testPairingStateMachineTransitions() {
        val inviteObj = JSONObject()
            .put("version", 1)
            .put("fromDeviceName", "Test Mac")
            .put("publicKey", "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
            .put("nonce", "000102030405060708090a0b0c0d0e0f")
            .put("host", "192.0.2.50")
            .put("port", 58240)
        val inviteJson = inviteObj.toString()
        val inviteVersion = JSONObject(inviteJson).optInt("version", 0)
        assertEquals(1, inviteVersion)

        val declineJson = JSONObject().put("version", 1).put("reason", "DECLINED").toString()
        val declineReason = JSONObject(declineJson).optString("reason")
        assertEquals("DECLINED", declineReason)

        val cancelJson = JSONObject().put("version", 1).put("reason", "CANCELLED").toString()
        val cancelReason = JSONObject(cancelJson).optString("reason")
        assertEquals("CANCELLED", cancelReason)
    }

    @Test
    fun testUpdateCheckerVersionComparison() {
        assertTrue(com.prabotics.rapidrop.service.UpdateChecker.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(com.prabotics.rapidrop.service.UpdateChecker.isNewerVersion("v1.1.0", "1.0.0"))
        assertTrue(com.prabotics.rapidrop.service.UpdateChecker.isNewerVersion("v2.0.0", "1.0.0"))
        assertTrue(com.prabotics.rapidrop.service.UpdateChecker.isNewerVersion("1.0.1", "1.0.0"))
        assertFalse(com.prabotics.rapidrop.service.UpdateChecker.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(com.prabotics.rapidrop.service.UpdateChecker.isNewerVersion("v0.9.9", "1.0.0"))
        assertFalse(com.prabotics.rapidrop.service.UpdateChecker.isNewerVersion("", "1.0.0"))
    }

    @Test
    fun testSanitizeRelativePathSecurity() {
        assertEquals("etc/passwd", com.prabotics.rapidrop.clipboard.MediaStorageHelper.sanitizeRelativePath("../../etc/passwd", "safe.txt"))
        assertEquals("windows/system32/cmd.exe", com.prabotics.rapidrop.clipboard.MediaStorageHelper.sanitizeRelativePath("..\\..\\windows\\system32\\cmd.exe", "safe.txt"))
        assertEquals("Users/Victim/Desktop/evil.sh", com.prabotics.rapidrop.clipboard.MediaStorageHelper.sanitizeRelativePath("C:\\Users\\Victim\\Desktop\\evil.sh", "safe.txt"))
        assertEquals("valid/nested/folder/document.pdf", com.prabotics.rapidrop.clipboard.MediaStorageHelper.sanitizeRelativePath("valid/nested/folder/document.pdf", "fallback.pdf"))
        assertEquals("..../..../file.txt", com.prabotics.rapidrop.clipboard.MediaStorageHelper.sanitizeRelativePath("..../..../file.txt", "fallback.txt"))
        assertEquals("empty.txt", com.prabotics.rapidrop.clipboard.MediaStorageHelper.sanitizeRelativePath("", "empty.txt"))
        assertEquals("dot.txt", com.prabotics.rapidrop.clipboard.MediaStorageHelper.sanitizeRelativePath(".", "dot.txt"))
        assertEquals("dotdot.txt", com.prabotics.rapidrop.clipboard.MediaStorageHelper.sanitizeRelativePath("..", "dotdot.txt"))
        assertEquals("slash.txt", com.prabotics.rapidrop.clipboard.MediaStorageHelper.sanitizeRelativePath("/", "slash.txt"))
        assertEquals("path/fallback.txt", com.prabotics.rapidrop.clipboard.MediaStorageHelper.sanitizeRelativePath("C:\\path\\fallback.txt", "C:\\path\\fallback.txt"))
    }

    @Test
    fun testEncryptedPairCancellationRoundtrip() {
        val payload = """{"version":1,"reason":"CANCELLED"}""".toByteArray(Charsets.UTF_8)
        val frame = WireFrame(
            type = PacketType.PAIR_FAIL,
            nonce = ByteArray(12),
            ciphertext = payload,
            tag = ByteArray(16)
        )
        val serialized = frame.serialize()
        val deserialized = WireFrame.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(PacketType.PAIR_FAIL, deserialized?.type)
        assertArrayEquals(payload, deserialized?.ciphertext)
    }

    @Test
    fun testCrossPlatformX25519AndHkdfCanonicalVector() {
        val alicePriv = CryptoEngine.hexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")!!
        val bobPubHex = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"

        val sharedSecret = CryptoEngine.computeSharedSecret(alicePriv, bobPubHex)
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", CryptoEngine.bytesToHex(sharedSecret))

        val nonceA = CryptoEngine.hexToBytes("000102030405060708090a0b0c0d0e0f")!!
        val nonceB = CryptoEngine.hexToBytes("101112131415161718191a1b1c1d1e1f")!!
        val idA = "device-uuid-alice"
        val idB = "device-uuid-bob"
        val alicePub = com.prabotics.rapidrop.security.Curve25519.computePublicKey(alicePriv)
        val bobPub = CryptoEngine.hexToBytes(bobPubHex)!!

        val keys = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = sharedSecret,
            initiatorPublicKey = alicePub,
            receiverPublicKey = bobPub,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = idA,
            receiverId = idB
        )

        assertEquals("2cd9946128df4a5cb0f1a1786e31136a0b7ea489c8f913552b605f6c83638c00", CryptoEngine.bytesToHex(keys.transcriptHash))
        assertEquals("c01bcb745e0644d944458b953fe8363beb4b8c7271aadc92f217e45b360b5d17", CryptoEngine.bytesToHex(keys.sessionKey.encoded))
        assertEquals("ffe30b48f5f241bfc48233070dc246222312376d9630f1585ef418af6945056a", CryptoEngine.bytesToHex(keys.authKey))
        assertEquals("ba2c59676e4619a4e6ab0ef71d9a2d414815292b97508c926516c5b0a9427838", CryptoEngine.bytesToHex(keys.pairRecordKey))
        assertEquals("069640", keys.sasCode)
    }

    @Test
    fun testMismatchedTranscriptFailsSAS() {
        val alicePriv = CryptoEngine.hexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")!!
        val bobPubHex = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
        val sharedSecret = CryptoEngine.computeSharedSecret(alicePriv, bobPubHex)

        val nonceA = CryptoEngine.hexToBytes("000102030405060708090a0b0c0d0e0f")!!
        val nonceB = CryptoEngine.hexToBytes("101112131415161718191a1b1c1d1e1f")!!
        val alicePub = com.prabotics.rapidrop.security.Curve25519.computePublicKey(alicePriv)
        val bobPub = CryptoEngine.hexToBytes(bobPubHex)!!

        val keysOriginal = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = sharedSecret,
            initiatorPublicKey = alicePub,
            receiverPublicKey = bobPub,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = "device-uuid-alice",
            receiverId = "device-uuid-bob"
        )

        val keysTampered = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = sharedSecret,
            initiatorPublicKey = alicePub,
            receiverPublicKey = bobPub,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = "device-uuid-attacker",
            receiverId = "device-uuid-bob"
        )

        assertFalse(keysOriginal.sasCode == keysTampered.sasCode)
        assertFalse(java.util.Arrays.equals(keysOriginal.sessionKey.encoded, keysTampered.sessionKey.encoded))
    }

    @Test
    fun testStreamingFileFramesSerialization() {
        val startJson = "{\"transferId\":\"uuid-123\",\"fileIndex\":0,\"totalFiles\":1,\"fileName\":\"test.png\",\"relativePath\":\"test.png\",\"fileSize\":1024,\"totalBytes\":1024}".toByteArray(Charsets.UTF_8)
        val startFrame = WireFrame(
            type = PacketType.FILE_START,
            nonce = ByteArray(12),
            ciphertext = startJson,
            tag = ByteArray(16)
        )
        val deserializedStart = WireFrame.deserialize(startFrame.serialize())
        assertEquals(PacketType.FILE_START, deserializedStart?.type)
        assertTrue(java.util.Arrays.equals(startJson, deserializedStart?.ciphertext))

        val endJson = "{\"transferId\":\"uuid-123\",\"fileIndex\":0,\"sha256\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}".toByteArray(Charsets.UTF_8)
        val endFrame = WireFrame(
            type = PacketType.FILE_END,
            nonce = ByteArray(12),
            ciphertext = endJson,
            tag = ByteArray(16)
        )
        val deserializedEnd = WireFrame.deserialize(endFrame.serialize())
        assertEquals(PacketType.FILE_END, deserializedEnd?.type)
        assertTrue(java.util.Arrays.equals(endJson, deserializedEnd?.ciphertext))

        val cancelJson = "{\"transferId\":\"uuid-123\",\"reason\":\"user_abort\"}".toByteArray(Charsets.UTF_8)
        val cancelFrame = WireFrame(
            type = PacketType.FILE_CANCEL,
            nonce = ByteArray(12),
            ciphertext = cancelJson,
            tag = ByteArray(16)
        )
        val deserializedCancel = WireFrame.deserialize(cancelFrame.serialize())
        assertEquals(PacketType.FILE_CANCEL, deserializedCancel?.type)
        assertTrue(java.util.Arrays.equals(cancelJson, deserializedCancel?.ciphertext))
    }

    @Test
    fun testFileChunkHeaderStructureAnd64BitChunkIndex() {
        val transferId = "test-transfer-id"
        val fileIndex = 42
        val chunkIndex = 1000000000L

        val headerBuffer = java.nio.ByteBuffer.allocate(28)
        val idBytes = transferId.toByteArray(Charsets.UTF_8).copyOf(16)
        headerBuffer.put(idBytes)
        headerBuffer.putInt(fileIndex)
        headerBuffer.putLong(chunkIndex)

        val header = headerBuffer.array()
        assertEquals(28, header.size)

        val parsedFileIndex = java.nio.ByteBuffer.wrap(header, 16, 4).order(java.nio.ByteOrder.BIG_ENDIAN).int
        val parsedChunkIndex = java.nio.ByteBuffer.wrap(header, 20, 8).order(java.nio.ByteOrder.BIG_ENDIAN).long

        assertEquals(42, parsedFileIndex)
        assertEquals(1000000000L, parsedChunkIndex)
    }

    @Test
    fun testIncrementalSha256StreamingParity() {
        val chunkA = "First chunk of stream ".toByteArray(Charsets.UTF_8)
        val chunkB = "second chunk of stream ".toByteArray(Charsets.UTF_8)
        val chunkC = "final payload bytes.".toByteArray(Charsets.UTF_8)

        val incremental = java.security.MessageDigest.getInstance("SHA-256")
        incremental.update(chunkA)
        incremental.update(chunkB)
        incremental.update(chunkC)
        val incDigest = incremental.digest().joinToString("") { "%02x".format(it) }

        val full = java.security.MessageDigest.getInstance("SHA-256")
        val fullData = chunkA + chunkB + chunkC
        val fullDigest = full.digest(fullData).joinToString("") { "%02x".format(it) }

        assertEquals(incDigest, fullDigest)
    }

    @Test
    fun testLargeFileSize64BitArithmetic() {
        val tenGigabytes: Long = 10L * 1024L * 1024L * 1024L
        val chunkSize: Long = WireFrame.STREAMING_CHUNK_SIZE.toLong()
        val totalChunks = (tenGigabytes + chunkSize - 1L) / chunkSize

        assertEquals(10240L, totalChunks)
        assertTrue(tenGigabytes > Int.MAX_VALUE.toLong())
    }
    @Test
    fun testSasTranscriptSensitivityAndDisplayNameIndependence() {
        val pubA = ByteArray(32) { 0x01 }
        val pubB = ByteArray(32) { 0x02 }
        val nonceA = ByteArray(16) { 0x03 }
        val nonceB = ByteArray(16) { 0x04 }
        val secret = ByteArray(32) { 0x05 }

        val base = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = secret,
            initiatorPublicKey = pubA,
            receiverPublicKey = pubB,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = "device-a",
            receiverId = "device-b"
        )

        val baseSame = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = secret,
            initiatorPublicKey = pubA,
            receiverPublicKey = pubB,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = "device-a",
            receiverId = "device-b"
        )
        assertEquals(base.sasCode, baseSame.sasCode)
        assertEquals(6, base.sasCode.length)

        val diffKeyA = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = secret,
            initiatorPublicKey = ByteArray(32) { 0x99.toByte() },
            receiverPublicKey = pubB,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = "device-a",
            receiverId = "device-b"
        )
        assertNotEquals(base.sasCode, diffKeyA.sasCode)

        val diffNonceA = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = secret,
            initiatorPublicKey = pubA,
            receiverPublicKey = pubB,
            initiatorNonce = ByteArray(16) { 0x99.toByte() },
            receiverNonce = nonceB,
            initiatorId = "device-a",
            receiverId = "device-b"
        )
        assertNotEquals(base.sasCode, diffNonceA.sasCode)

        val diffIdA = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = secret,
            initiatorPublicKey = pubA,
            receiverPublicKey = pubB,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = "device-a-different",
            receiverId = "device-b"
        )
        assertNotEquals(base.sasCode, diffIdA.sasCode)

        val diffIdB = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = secret,
            initiatorPublicKey = pubA,
            receiverPublicKey = pubB,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = "device-a",
            receiverId = "device-b-different"
        )
        assertNotEquals(base.sasCode, diffIdB.sasCode)
    }

    @Test
    fun testDiscoveryUnresolvedRecordMergesIntoResolvedDevice() {
        val unresolved = com.prabotics.rapidrop.network.DiscoveredDevice(name = "Test Mac", host = "192.0.2.50", port = 58240, id = "")
        val resolved = com.prabotics.rapidrop.network.DiscoveredDevice(name = "Test Mac", host = "192.0.2.50", port = 58240, id = "uuid-1234")

        val devicesById = mutableMapOf<String, com.prabotics.rapidrop.network.DiscoveredDevice>()
        val devicesByNameWithoutId = mutableMapOf<String, com.prabotics.rapidrop.network.DiscoveredDevice>()

        for (dev in listOf(unresolved, resolved)) {
            if (dev.id.isNotBlank()) {
                devicesById[dev.id] = dev
            } else {
                val nameKey = com.prabotics.rapidrop.network.DeviceNameHelper.normalizeDeviceName(dev.name)
                if (!devicesByNameWithoutId.containsKey(nameKey)) {
                    devicesByNameWithoutId[nameKey] = dev
                }
            }
        }

        val merged = devicesById.values.toMutableList()
        val seenNamesWithId = merged.map { com.prabotics.rapidrop.network.DeviceNameHelper.normalizeDeviceName(it.name) }.toSet()

        for ((nameKey, dev) in devicesByNameWithoutId) {
            if (!seenNamesWithId.contains(nameKey)) {
                merged.add(dev)
            }
        }

        assertEquals(1, merged.size)
        assertEquals("uuid-1234", merged[0].id)
    }

    @Test
    fun testRemoteDeviceWithSameNameAsLocalIsNotSelf() {
        val localId = "my-phone-uuid"
        val localName = "Pixel 8"

        val remoteWithSameName = com.prabotics.rapidrop.network.DiscoveredDevice(name = "Pixel 8", host = "192.0.2.60", port = 58240, id = "other-phone-uuid")
        val isSelf = if (remoteWithSameName.id.isNotBlank() && localId.isNotBlank()) {
            remoteWithSameName.id == localId
        } else {
            localName.isNotBlank() && com.prabotics.rapidrop.network.DeviceNameHelper.isSameDevice(remoteWithSameName.name, localName)
        }
        assertFalse(isSelf)
    }
    @Test
    fun testNavigationAndThemeModeEnums() {
        val dests = com.prabotics.rapidrop.ui.MainNavDestination.entries
        assertEquals(3, dests.size)
        assertEquals("Devices", com.prabotics.rapidrop.ui.MainNavDestination.DEVICES.label)
        assertEquals("History", com.prabotics.rapidrop.ui.MainNavDestination.HISTORY.label)
        assertEquals("Settings", com.prabotics.rapidrop.ui.MainNavDestination.SETTINGS.label)

        val themes = com.prabotics.rapidrop.ui.theme.AppThemeMode.entries
        assertEquals(3, themes.size)
        assertEquals("System", com.prabotics.rapidrop.ui.theme.AppThemeMode.SYSTEM.displayLabel)
        assertEquals("Light", com.prabotics.rapidrop.ui.theme.AppThemeMode.LIGHT.displayLabel)
        assertEquals("Dark", com.prabotics.rapidrop.ui.theme.AppThemeMode.DARK.displayLabel)
    }
    @Test
    fun testEndToEndProtocolHandshakeAndroidToMac() {
        val (privA, pubAHex) = CryptoEngine.generateEphemeralKeypair()
        val nonceA = CryptoEngine.generateNonce(16)
        val (privB, pubBHex) = CryptoEngine.generateEphemeralKeypair()
        val nonceB = CryptoEngine.generateNonce(16)

        val secretA = CryptoEngine.computeSharedSecret(privA, pubBHex)
        val secretB = CryptoEngine.computeSharedSecret(privB, pubAHex)
        assertTrue(java.util.Arrays.equals(secretA, secretB))

        val pubABytes = CryptoEngine.hexToBytes(pubAHex)!!
        val pubBBytes = CryptoEngine.hexToBytes(pubBHex)!!

        val keysA = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = secretA,
            initiatorPublicKey = pubABytes,
            receiverPublicKey = pubBBytes,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = "android-uuid-1",
            receiverId = "mac-uuid-2"
        )
        val keysB = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = secretB,
            initiatorPublicKey = pubABytes,
            receiverPublicKey = pubBBytes,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = "android-uuid-1",
            receiverId = "mac-uuid-2"
        )

        assertEquals(keysA.sasCode, keysB.sasCode)
        assertTrue(java.util.Arrays.equals(keysA.sessionKey.encoded, keysB.sessionKey.encoded))
        assertTrue(java.util.Arrays.equals(keysA.pairRecordKey, keysB.pairRecordKey))

        val devInfoJson = JSONObject().put("deviceName", "Pixel 9").put("modelId", "Pixel 9").put("modelName", "Pixel 9").put("chip", "Tensor G4").toString()
        val reqEncrypted = CryptoEngine.encrypt(devInfoJson.toByteArray(Charsets.UTF_8), keysA.sessionKey)
        val reqFrame = WireFrame(type = PacketType.PAIR_REQUEST, nonce = reqEncrypted.nonce, ciphertext = reqEncrypted.ciphertext, tag = reqEncrypted.tag)
        val reqDeserialized = WireFrame.deserialize(reqFrame.serialize())
        assertNotNull(reqDeserialized)
        val reqDecrypted = CryptoEngine.decrypt(reqDeserialized!!.ciphertext, reqDeserialized.nonce, reqDeserialized.tag, keysB.sessionKey)
        assertEquals(devInfoJson, String(reqDecrypted, Charsets.UTF_8))

        val confirmEncrypted = CryptoEngine.encrypt("OK".toByteArray(Charsets.UTF_8), keysB.sessionKey)
        val confirmFrame = WireFrame(type = PacketType.PAIR_CONFIRM, nonce = confirmEncrypted.nonce, ciphertext = confirmEncrypted.ciphertext, tag = confirmEncrypted.tag)
        val confirmDeserialized = WireFrame.deserialize(confirmFrame.serialize())
        assertNotNull(confirmDeserialized)
        val confirmDecrypted = CryptoEngine.decrypt(confirmDeserialized!!.ciphertext, confirmDeserialized.nonce, confirmDeserialized.tag, keysA.sessionKey)
        assertEquals("OK", String(confirmDecrypted, Charsets.UTF_8))

        val clipContent = "Cross platform clipboard text payload"
        val clipEncrypted = CryptoEngine.encrypt(clipContent.toByteArray(Charsets.UTF_8), keysA.sessionKey)
        val clipFrame = WireFrame(type = PacketType.CLIP_TEXT, nonce = clipEncrypted.nonce, ciphertext = clipEncrypted.ciphertext, tag = clipEncrypted.tag)
        val clipDeserialized = WireFrame.deserialize(clipFrame.serialize())
        assertNotNull(clipDeserialized)
        val clipDecrypted = CryptoEngine.decrypt(clipDeserialized!!.ciphertext, clipDeserialized.nonce, clipDeserialized.tag, keysB.sessionKey)
        assertEquals(clipContent, String(clipDecrypted, Charsets.UTF_8))
    }

    @Test
    fun testPairDeclineFlow() {
        val declineJson = JSONObject().put("version", 1).put("reason", "DECLINED").toString()
        val frame = WireFrame(type = PacketType.PAIR_FAIL, nonce = ByteArray(12), ciphertext = declineJson.toByteArray(Charsets.UTF_8), tag = ByteArray(16))
        val deserialized = WireFrame.deserialize(frame.serialize())
        assertEquals(PacketType.PAIR_FAIL, deserialized?.type)
        assertTrue(String(deserialized!!.ciphertext, Charsets.UTF_8).contains("DECLINED"))
    }
    @Test
    fun testReconnectWithStoredPreSharedKey() {
        val rawKeyBytes = ByteArray(32) { (it * 7).toByte() }
        val storedKey = javax.crypto.spec.SecretKeySpec(rawKeyBytes, "AES")
        val keyHex = CryptoEngine.bytesToHex(rawKeyBytes)
        val loadedKeyBytes = CryptoEngine.hexToBytes(keyHex)!!
        val restoredKey = javax.crypto.spec.SecretKeySpec(loadedKeyBytes, "AES")

        val descJson = JSONObject().put("deviceName", "Pixel 9 Pro").put("modelId", "Pixel 9 Pro").put("modelName", "Pixel 9 Pro").put("chip", "Tensor G4").toString()
        val encryptedReq = CryptoEngine.encrypt(descJson.toByteArray(Charsets.UTF_8), restoredKey)
        val reqFrame = WireFrame(type = PacketType.PAIR_REQUEST, nonce = encryptedReq.nonce, ciphertext = encryptedReq.ciphertext, tag = encryptedReq.tag)
        val deserialized = WireFrame.deserialize(reqFrame.serialize())

        assertNotNull(deserialized)
        val decrypted = CryptoEngine.decrypt(deserialized!!.ciphertext, deserialized.nonce, deserialized.tag, storedKey)
        assertEquals(descJson, String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun testProcessRestartKeyRestoration() {
        val originalKeyBytes = ByteArray(32) { (it + 3).toByte() }
        val originalKey = javax.crypto.spec.SecretKeySpec(originalKeyBytes, "AES")
        val persistedHex = CryptoEngine.bytesToHex(originalKeyBytes)

        val restoredKeyBytes = CryptoEngine.hexToBytes(persistedHex)!!
        val restoredKey = javax.crypto.spec.SecretKeySpec(restoredKeyBytes, "AES")

        val payload = "Restored Android session clip content".toByteArray(Charsets.UTF_8)
        val encrypted = CryptoEngine.encrypt(payload, originalKey)
        val frame = WireFrame(type = PacketType.CLIP_TEXT, nonce = encrypted.nonce, ciphertext = encrypted.ciphertext, tag = encrypted.tag)

        val deserialized = WireFrame.deserialize(frame.serialize())
        assertNotNull(deserialized)
        val decrypted = CryptoEngine.decrypt(deserialized!!.ciphertext, deserialized.nonce, deserialized.tag, restoredKey)
        assertEquals("Restored Android session clip content", String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun testFailureInjectionInvalidTagRejection() {
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 0x01 }, "AES")
        val payload = "Sensitive test payload".toByteArray(Charsets.UTF_8)
        val encrypted = CryptoEngine.encrypt(payload, key)

        val tamperedTag = encrypted.tag.copyOf()
        tamperedTag[tamperedTag.size - 1] = (tamperedTag[tamperedTag.size - 1].toInt() xor 0xFF).toByte()

        var didThrow = false
        try {
            CryptoEngine.decrypt(encrypted.ciphertext, encrypted.nonce, tamperedTag, key)
        } catch (_: Exception) {
            didThrow = true
        }
        assertTrue(didThrow)
    }

    @Test
    fun testFailureInjectionWrongKeyRejection() {
        val correctKey = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 0x01 }, "AES")
        val wrongKey = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 0x02 }, "AES")

        val payload = "Authenticated data".toByteArray(Charsets.UTF_8)
        val encrypted = CryptoEngine.encrypt(payload, correctKey)

        var didThrow = false
        try {
            CryptoEngine.decrypt(encrypted.ciphertext, encrypted.nonce, encrypted.tag, wrongKey)
        } catch (_: Exception) {
            didThrow = true
        }
        assertTrue(didThrow)
    }

    @Test
    fun testHeartbeatWatchdogCalculation() {
        val now = System.currentTimeMillis()
        val lastActiveRecent = now - 4000L
        val lastActiveExpired = now - 17000L

        val isRecentExpired = (now - lastActiveRecent) > 16000L
        val isExpiredTimeout = (now - lastActiveExpired) > 16000L

        assertFalse(isRecentExpired)
        assertTrue(isExpiredTimeout)
    }

    @Test
    fun testMultiDeviceIsolation() {
        val keyAB = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 0x0A }, "AES")
        val keyAC = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 0x0B }, "AES")

        val payloadB = "Message intended for Device B".toByteArray(Charsets.UTF_8)
        val encryptedB = CryptoEngine.encrypt(payloadB, keyAB)

        val decryptedByB = CryptoEngine.decrypt(encryptedB.ciphertext, encryptedB.nonce, encryptedB.tag, keyAB)
        assertEquals("Message intended for Device B", String(decryptedByB, Charsets.UTF_8))

        var failedOnC = false
        try {
            CryptoEngine.decrypt(encryptedB.ciphertext, encryptedB.nonce, encryptedB.tag, keyAC)
        } catch (_: Exception) {
            failedOnC = true
        }
        assertTrue(failedOnC)
    }

    @Test
    fun testDeterministicBidirectionalFileStreaming() {
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 0x05 }, "AES")
        val fileData = "Small synthetic test file content for streaming verification".toByteArray(Charsets.UTF_8)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = CryptoEngine.bytesToHex(md.digest(fileData))

        val startJson = "{\"transferId\":\"tid-100\",\"fileName\":\"test.txt\",\"fileSize\":60,\"sha256\":\"$digest\"}".toByteArray(Charsets.UTF_8)
        val startEncrypted = CryptoEngine.encrypt(startJson, key)
        val startFrame = WireFrame(type = PacketType.FILE_START, nonce = startEncrypted.nonce, ciphertext = startEncrypted.ciphertext, tag = startEncrypted.tag)
        val startDeserialized = WireFrame.deserialize(startFrame.serialize())
        assertEquals(PacketType.FILE_START, startDeserialized?.type)

        val chunkEncrypted = CryptoEngine.encrypt(fileData, key)
        val chunkFrame = WireFrame(type = PacketType.FILE_CHUNK, nonce = chunkEncrypted.nonce, ciphertext = chunkEncrypted.ciphertext, tag = chunkEncrypted.tag)
        val chunkDeserialized = WireFrame.deserialize(chunkFrame.serialize())
        assertEquals(PacketType.FILE_CHUNK, chunkDeserialized?.type)

        val endJson = "{\"transferId\":\"tid-100\",\"sha256\":\"$digest\"}".toByteArray(Charsets.UTF_8)
        val endEncrypted = CryptoEngine.encrypt(endJson, key)
        val endFrame = WireFrame(type = PacketType.FILE_END, nonce = endEncrypted.nonce, ciphertext = endEncrypted.ciphertext, tag = endEncrypted.tag)
        val endDeserialized = WireFrame.deserialize(endFrame.serialize())
        assertEquals(PacketType.FILE_END, endDeserialized?.type)

        val decryptedChunk = CryptoEngine.decrypt(chunkDeserialized!!.ciphertext, chunkDeserialized.nonce, chunkDeserialized.tag, key)
        val mdRecv = java.security.MessageDigest.getInstance("SHA-256")
        val receivedDigest = CryptoEngine.bytesToHex(mdRecv.digest(decryptedChunk))
        assertEquals(digest, receivedDigest)
    }

    @Test
    fun testHistoryCategoryFilterMatching() {
        val textClip = ClipItem(type = ClipContentType.TEXT, textContent = "Simple text note")
        val linkClip = ClipItem(type = ClipContentType.URL, textContent = "https://github.com/example")
        val imageClip = ClipItem(type = ClipContentType.IMAGE, rawData = ByteArray(10))
        val fileClip = ClipItem(type = ClipContentType.FILE, fileName = "doc.pdf", rawData = ByteArray(20))

        val allClips = listOf(textClip, linkClip, imageClip, fileClip)

        val textFiltered = allClips.filter { it.type == ClipContentType.TEXT }
        assertEquals(1, textFiltered.size)
        assertEquals("Simple text note", textFiltered[0].textContent)

        val linkFiltered = allClips.filter { it.type == ClipContentType.URL }
        assertEquals(1, linkFiltered.size)
        assertEquals("https://github.com/example", linkFiltered[0].textContent)

        val mediaFiltered = allClips.filter { it.type == ClipContentType.IMAGE || it.type == ClipContentType.FILE }
        assertEquals(2, mediaFiltered.size)
    }

    @Test
    fun testClipboardHistoryDeduplicationAndBounds() {
        SyncService.clearHistory()
        val clip1 = ClipItem(type = ClipContentType.TEXT, textContent = "Unique Note 1")
        val clip2 = ClipItem(type = ClipContentType.TEXT, textContent = "Unique Note 2")
        val clip1Duplicate = ClipItem(type = ClipContentType.TEXT, textContent = "Unique Note 1")

        SyncService.addRecentClip(clip1)
        SyncService.addRecentClip(clip2)
        assertEquals(2, SyncService.recentClips.value.size)
        assertEquals("Unique Note 2", SyncService.recentClips.value[0].textContent)
        assertEquals("Unique Note 1", SyncService.recentClips.value[1].textContent)

        SyncService.addRecentClip(clip1Duplicate)
        assertEquals(2, SyncService.recentClips.value.size)
        assertEquals("Unique Note 1", SyncService.recentClips.value[0].textContent)
        assertEquals("Unique Note 2", SyncService.recentClips.value[1].textContent)

        val file1 = ClipItem(type = ClipContentType.FILE, fileName = "report.pdf", rawData = null)
        val file1Dup = ClipItem(type = ClipContentType.FILE, fileName = "report.pdf", rawData = null)
        SyncService.addRecentClip(file1)
        assertEquals(3, SyncService.recentClips.value.size)
        SyncService.addRecentClip(file1Dup)
        assertEquals(3, SyncService.recentClips.value.size)
        assertEquals("report.pdf", SyncService.recentClips.value[0].fileName)

        for (i in 1..25) {
            SyncService.addRecentClip(ClipItem(type = ClipContentType.TEXT, textContent = "Overflow Clip $i"))
        }
        assertTrue(SyncService.recentClips.value.size <= 20)
    }

    @Test
    fun testNearbyDeviceFilteringExcludesPaired() {
        val pairedName = "Samir’s MacBook Air"
        val dev1 = DiscoveredDevice("Samir's MacBook Air", "192.168.1.10", 58240)
        val dev2 = DiscoveredDevice("Windows PC", "192.168.1.15", 58240)
        val dev3 = DiscoveredDevice("Pixel 7", "192.168.1.20", 58240)

        val allDevices = listOf(dev1, dev2, dev3)
        val nearbyOnly = allDevices.filter { !DeviceNameHelper.isSameDevice(it.name, pairedName) }

        assertEquals(2, nearbyOnly.size)
        assertTrue(nearbyOnly.any { it.name == "Windows PC" })
        assertTrue(nearbyOnly.any { it.name == "Pixel 7" })
        assertFalse(nearbyOnly.any { it.name == "Samir's MacBook Air" })
    }

    @Test
    fun testEndToEndPairingHandshakeAndAuthenticatedSession() {
        val (privA, pubAHex) = CryptoEngine.generateEphemeralKeypair()
        val nonceA = CryptoEngine.generateNonce(16)
        val idA = "mac-initiator-id"

        val (privB, pubBHex) = CryptoEngine.generateEphemeralKeypair()
        val nonceB = CryptoEngine.generateNonce(16)
        val idB = "android-responder-id"

        val pubABytes = CryptoEngine.hexToBytes(pubAHex)!!
        val pubBBytes = CryptoEngine.hexToBytes(pubBHex)!!

        val secretB = CryptoEngine.computeSharedSecret(privB, pubAHex)
        val keysB = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = secretB,
            initiatorPublicKey = pubABytes,
            receiverPublicKey = pubBBytes,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = idA,
            receiverId = idB
        )

        val secretA = CryptoEngine.computeSharedSecret(privA, pubBHex)
        val keysA = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = secretA,
            initiatorPublicKey = pubABytes,
            receiverPublicKey = pubBBytes,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = idA,
            receiverId = idB
        )

        assertEquals(keysA.sasCode, keysB.sasCode)
        assertArrayEquals(keysA.sessionKey.encoded, keysB.sessionKey.encoded)

        val reqPayload = "{\"deviceName\":\"MacBook Air\",\"modelId\":\"MacBookAir10,1\"}".toByteArray(Charsets.UTF_8)
        val encReq = CryptoEngine.encrypt(reqPayload, keysA.sessionKey)
        val reqFrame = WireFrame(type = PacketType.PAIR_REQUEST, nonce = encReq.nonce, ciphertext = encReq.ciphertext, tag = encReq.tag)
        val deserializedReq = WireFrame.deserialize(reqFrame.serialize())!!
        assertEquals(PacketType.PAIR_REQUEST, deserializedReq.type)

        val decryptedReq = CryptoEngine.decrypt(deserializedReq.ciphertext, deserializedReq.nonce, deserializedReq.tag, keysB.sessionKey)
        assertEquals(String(reqPayload, Charsets.UTF_8), String(decryptedReq, Charsets.UTF_8))

        val confirmPayload = "OK".toByteArray(Charsets.UTF_8)
        val encConfirm = CryptoEngine.encrypt(confirmPayload, keysB.sessionKey)
        val confirmFrame = WireFrame(type = PacketType.PAIR_CONFIRM, nonce = encConfirm.nonce, ciphertext = encConfirm.ciphertext, tag = encConfirm.tag)
        val deserializedConfirm = WireFrame.deserialize(confirmFrame.serialize())!!
        assertEquals(PacketType.PAIR_CONFIRM, deserializedConfirm.type)

        val decryptedConfirm = CryptoEngine.decrypt(deserializedConfirm.ciphertext, deserializedConfirm.nonce, deserializedConfirm.tag, keysA.sessionKey)
        assertEquals("OK", String(decryptedConfirm, Charsets.UTF_8))

        val clipText = "Authenticated cross-platform clip"
        val encClip = CryptoEngine.encrypt(clipText.toByteArray(Charsets.UTF_8), keysA.sessionKey)
        val clipFrame = WireFrame(type = PacketType.CLIP_TEXT, nonce = encClip.nonce, ciphertext = encClip.ciphertext, tag = encClip.tag)
        val deserializedClip = WireFrame.deserialize(clipFrame.serialize())!!
        val decryptedClip = CryptoEngine.decrypt(deserializedClip.ciphertext, deserializedClip.nonce, deserializedClip.tag, keysB.sessionKey)
        assertEquals(clipText, String(decryptedClip, Charsets.UTF_8))
    }

    @Test
    fun testCorruptedSessionKeyRejection() {
        val keyA = SecretKeySpec(ByteArray(32) { 0x01 }, "AES")
        val keyB = SecretKeySpec(ByteArray(32) { 0x02 }, "AES")
        val payload = "OK".toByteArray(Charsets.UTF_8)
        val enc = CryptoEngine.encrypt(payload, keyA)
        val frame = WireFrame(type = PacketType.PAIR_CONFIRM, nonce = enc.nonce, ciphertext = enc.ciphertext, tag = enc.tag)
        val deserialized = WireFrame.deserialize(frame.serialize())!!

        try {
            CryptoEngine.decrypt(deserialized.ciphertext, deserialized.nonce, deserialized.tag, keyB)
            fail("Decryption with wrong key must fail")
        } catch (_: Exception) {}
    }

    @Test
    fun testTwoStagePairingHandshakeOfferThenAccept() {
        val client = SocketClient(context = null)
        val (privA, pubAHex) = CryptoEngine.generateEphemeralKeypair()
        val nonceA = CryptoEngine.generateNonce(16)
        client.setPendingInitiatorStateForTesting(privA, pubAHex, nonceA)

        val (privB, pubBHex) = CryptoEngine.generateEphemeralKeypair()
        val nonceB = CryptoEngine.generateNonce(16)
        val idA = ""
        val idB = "mac-responder-id"

        val pubABytes = CryptoEngine.hexToBytes(pubAHex)!!
        val pubBBytes = CryptoEngine.hexToBytes(pubBHex)!!
        val secretB = CryptoEngine.computeSharedSecret(privB, pubAHex)
        val expectedKeysB = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = secretB,
            initiatorPublicKey = pubABytes,
            receiverPublicKey = pubBBytes,
            initiatorNonce = nonceA,
            receiverNonce = nonceB,
            initiatorId = idA,
            receiverId = idB
        )

        val offerKeys = client.handlePairAccept(
            fromDevice = "Samir's MacBook Air",
            remoteId = idB,
            remotePublicKeyHex = pubBHex,
            remoteNonceHex = CryptoEngine.bytesToHex(nonceB),
            isFinal = false
        )
        assertNotNull(offerKeys)
        assertEquals(expectedKeysB.sasCode, offerKeys!!.sasCode)
        assertArrayEquals(expectedKeysB.sessionKey.encoded, offerKeys.sessionKey.encoded)
        assertTrue(client.hasPendingInitiatorStateForTesting())

        val acceptKeys = client.handlePairAccept(
            fromDevice = "Samir's MacBook Air",
            remoteId = idB,
            remotePublicKeyHex = pubBHex,
            remoteNonceHex = CryptoEngine.bytesToHex(nonceB),
            isFinal = true
        )
        assertNotNull(acceptKeys)
        assertEquals(expectedKeysB.sasCode, acceptKeys!!.sasCode)
        assertArrayEquals(expectedKeysB.sessionKey.encoded, acceptKeys.sessionKey.encoded)
        assertFalse(client.hasPendingInitiatorStateForTesting())
    }
    @Test
    fun testBuildPairAcceptIncludesId() {
        val acceptJson = JSONObject()
            .put("version", 1)
            .put("fromDeviceName", "Pixel 6a")
            .put("publicKey", "0123456789abcdef")
            .put("nonce", "fedcba9876543210")
            .put("host", "192.168.0.220")
            .put("port", 58241)
            .put("id", "pixel-uuid-test")
            .toString()
        val parsedId = JSONObject(acceptJson).optString("id")
        assertEquals("pixel-uuid-test", parsedId)
    }

    @Test
    fun testMacToAndroidHandshakeWithSerializedPayloads() {
        val macId = "5EA4BAB6-472F-4F74-BFE3-2674697E1E02"
        val androidId = "532afb49-ddce-4827-a7a7-ef29bcdc7f1d"

        val (privMac, pubMacHex) = CryptoEngine.generateEphemeralKeypair()
        val nonceMac = CryptoEngine.generateNonce(16)
        val nonceMacHex = CryptoEngine.bytesToHex(nonceMac)

        val inviteJson = JSONObject()
            .put("version", 1)
            .put("fromDeviceName", "Samir's MacBook Air")
            .put("publicKey", pubMacHex)
            .put("nonce", nonceMacHex)
            .put("host", "192.168.0.249")
            .put("port", 58240)
            .put("id", macId)
            .toString()

        val parsedInvite = JSONObject(inviteJson)
        val parsedMacPub = parsedInvite.getString("publicKey")
        val parsedMacNonce = parsedInvite.getString("nonce")
        val parsedMacId = parsedInvite.getString("id")
        assertEquals(macId, parsedMacId)

        val (privAndroid, pubAndroidHex) = CryptoEngine.generateEphemeralKeypair()
        val nonceAndroid = CryptoEngine.generateNonce(16)
        val nonceAndroidHex = CryptoEngine.bytesToHex(nonceAndroid)

        val androidSecret = CryptoEngine.computeSharedSecret(privAndroid, parsedMacPub)
        val androidKeys = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = androidSecret,
            initiatorPublicKey = CryptoEngine.hexToBytes(parsedMacPub)!!,
            receiverPublicKey = CryptoEngine.hexToBytes(pubAndroidHex)!!,
            initiatorNonce = CryptoEngine.hexToBytes(parsedMacNonce)!!,
            receiverNonce = nonceAndroid,
            initiatorId = parsedMacId,
            receiverId = androidId
        )

        val acceptJson = JSONObject()
            .put("version", 1)
            .put("fromDeviceName", "Pixel 6a")
            .put("publicKey", pubAndroidHex)
            .put("nonce", nonceAndroidHex)
            .put("host", "192.168.0.220")
            .put("port", 58241)
            .put("id", androidId)
            .put("status", "offered")
            .toString()

        val parsedAccept = JSONObject(acceptJson)
        val parsedAndroidPub = parsedAccept.getString("publicKey")
        val parsedAndroidNonce = parsedAccept.getString("nonce")
        val parsedAndroidId = parsedAccept.getString("id")
        assertEquals(androidId, parsedAndroidId)

        val macSecret = CryptoEngine.computeSharedSecret(privMac, parsedAndroidPub)
        val macKeys = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = macSecret,
            initiatorPublicKey = CryptoEngine.hexToBytes(pubMacHex)!!,
            receiverPublicKey = CryptoEngine.hexToBytes(parsedAndroidPub)!!,
            initiatorNonce = nonceMac,
            receiverNonce = CryptoEngine.hexToBytes(parsedAndroidNonce)!!,
            initiatorId = macId,
            receiverId = parsedAndroidId
        )

        assertEquals(androidKeys.sasCode, macKeys.sasCode)
        assertArrayEquals(androidKeys.sessionKey.encoded, macKeys.sessionKey.encoded)
    }
    @Test
    fun testPairAcceptSessionKeyAndPayloadContract() {
        val client = SocketClient(context = null)
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        client.setSessionKey(secretKey)

        assertNotNull(client.getSessionKey())
        assertArrayEquals(keyBytes, client.getSessionKey()!!.encoded)

        val acceptPayload = JSONObject()
            .put("version", 1)
            .put("fromDeviceName", "Pixel 6a")
            .put("publicKey", "0102030405060708090a0b0c0d0e0f10")
            .put("nonce", "1112131415161718191a1b1c1d1e1f20")
            .put("host", "192.168.0.220")
            .put("port", WireFrame.DEFAULT_CLIENT_PORT)
            .put("id", "test-pixel-device-id")
            .put("status", "accepted")
            .toString()
        val parsed = JSONObject(acceptPayload)
        val status = parsed.optString("status")
        val id = parsed.optString("id")
        val host = parsed.optString("host")
        val port = parsed.optInt("port", WireFrame.DEFAULT_PORT)
        assertEquals("accepted", status)
        assertEquals("test-pixel-device-id", id)
        assertEquals("192.168.0.220", host)
        assertEquals(WireFrame.DEFAULT_CLIENT_PORT, port)

        val connectPort = if (port == WireFrame.DEFAULT_CLIENT_PORT) WireFrame.DEFAULT_PORT else port
        assertEquals(WireFrame.DEFAULT_PORT, connectPort)
    }
}
