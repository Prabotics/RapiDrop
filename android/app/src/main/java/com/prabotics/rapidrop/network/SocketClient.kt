package com.prabotics.rapidrop.network

import org.json.JSONObject
import android.content.Context
import android.os.Build
import android.util.Log
import com.prabotics.rapidrop.clipboard.ClipContentType
import com.prabotics.rapidrop.clipboard.ClipItem
import com.prabotics.rapidrop.clipboard.ConnectedDeviceInfo
import com.prabotics.rapidrop.security.CryptoEngine
import com.prabotics.rapidrop.security.HandshakeKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.SecretKey



class SocketClient(
    private val context: Context? = null,
    private val onClipReceived: (ClipItem) -> Unit = {},
    private val onConnectionStateChanged: (Boolean) -> Unit = {},
    private val onDeviceInfoReceived: (ConnectedDeviceInfo) -> Unit = {},
    private val onPairFailed: ((reason: String) -> Unit)? = null,
    private val onRemoteUnpaired: (() -> Unit)? = null,
    var onPrivacyModeUpdated: ((Boolean) -> Unit)? = null,
    var onTransferProgress: ((transferId: String, fileName: String, bytesTransferred: Long, totalBytes: Long, fileIndex: Int, totalFiles: Int, isComplete: Boolean) -> Unit)? = null,
    var onTransferCancelled: ((transferId: String, reason: String) -> Unit)? = null
) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var sessionKey: SecretKey? = null
    private var pendingInitiatorKeypair: Pair<ByteArray, String>? = null
    private var pendingInitiatorNonce: ByteArray? = null
    private val isConnecting = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isConnectedState = java.util.concurrent.atomic.AtomicBoolean(false)
    private val connectionGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private var heartbeatJob: Job? = null
    private data class IncomingStreamTransfer(
        val transferId: String,
        val fileIndex: Int,
        val totalFiles: Int,
        val fileName: String,
        val relativePath: String,
        val fileSize: Long,
        val totalBytes: Long,
        var bytesReceived: Long = 0L,
        var nextChunkIndex: Long = 0L,
        val outputStream: java.io.OutputStream,
        val targetUri: android.net.Uri?,
        val digest: java.security.MessageDigest = java.security.MessageDigest.getInstance("SHA-256"),
        val writeChannel: Channel<ByteArray> = Channel(capacity = 8),
        val writerJob: Job? = null
    )
    private var activeIncomingTransfer: IncomingStreamTransfer? = null

    fun setSessionKey(key: SecretKey?) {
        this.sessionKey = key
    }
    fun getSessionKey(): SecretKey? = sessionKey

    private fun getFriendlyDeviceName(): String {
        return context?.let { DeviceNameHelper.getDeviceFriendlyName(it) } ?: Build.MODEL
    }

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        if (socket?.isConnected == true && !socket!!.isClosed) {
            return@withContext
        }
        if (!isConnecting.compareAndSet(false, true)) {
            return@withContext
        }
        val currentGen = connectionGeneration.incrementAndGet()
        Log.d("RapiDrop", "connection.attempt_started gen=$currentGen host=$host port=$port")
        try {
            try {
                socket?.close()
            } catch (_: IOException) {}
            socket = null
            outputStream = null
            val sock = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                soTimeout = 20000
                sendBufferSize = 4 * 1024 * 1024
                receiveBufferSize = 4 * 1024 * 1024
            }
            val cleanHost = host.removePrefix("::ffff:").substringBefore("%")
            if (cleanHost.isBlank()) return@withContext
            sock.connect(InetSocketAddress(cleanHost, port), 4000)
            if (connectionGeneration.get() != currentGen) {
                try { sock.close() } catch (_: IOException) {}
                return@withContext
            }
            Log.d("RapiDrop", "connection.tcp_connected gen=$currentGen host=$cleanHost port=$port")
            socket = sock
            outputStream = BufferedOutputStream(sock.getOutputStream(), WireFrame.STREAMING_CHUNK_SIZE)
            sendPairRequest()
            readLoop(BufferedInputStream(sock.getInputStream(), WireFrame.STREAMING_CHUNK_SIZE), currentGen)
        } catch (e: IOException) {
            Log.d("RapiDrop", "connection.failed gen=$currentGen error=io_exception")
            disconnect(currentGen)
        } catch (e: SecurityException) {
            Log.d("RapiDrop", "connection.failed gen=$currentGen error=security_exception")
            disconnect(currentGen)
        } finally {
            isConnecting.set(false)
        }
    }

    suspend fun sendPairInvite(host: String, port: Int, pin: String = "") = withContext(Dispatchers.IO) {
        try {
            val cleanHost = host.removePrefix("::ffff:")
            val sock = Socket().apply {
                tcpNoDelay = true
                soTimeout = 5000
                sendBufferSize = 2 * 1024 * 1024
                receiveBufferSize = 2 * 1024 * 1024
            }
            sock.connect(InetSocketAddress(cleanHost, port), 4000)
            val out = sock.getOutputStream()
            val localIp = sock.localAddress?.hostAddress?.removePrefix("::ffff:") ?: ""

            val (priv, pubHex) = CryptoEngine.generateEphemeralKeypair()
            val nonce = CryptoEngine.generateNonce(16)
            pendingInitiatorKeypair = Pair(priv, pubHex)
            pendingInitiatorNonce = nonce

            val localId = context?.let { com.prabotics.rapidrop.preference.PreferencesManager(it).getDeviceId() } ?: ""
            val obj = JSONObject()
            obj.put("version", 1)
            if (localId.isNotBlank()) obj.put("id", localId)
            obj.put("fromDeviceName", getFriendlyDeviceName())
            obj.put("deviceType", "android")
            obj.put("model", "Device")
            obj.put("chip", "Android")
            obj.put("publicKey", pubHex)
            obj.put("nonce", CryptoEngine.bytesToHex(nonce))
            if (localIp.isNotBlank()) obj.put("host", localIp)
            obj.put("port", WireFrame.DEFAULT_CLIENT_PORT)
            val json = obj.toString()
            val frame = WireFrame(
                type = PacketType.PAIR_INVITE,
                nonce = ByteArray(WireFrame.NONCE_SIZE),
                ciphertext = json.toByteArray(Charsets.UTF_8),
                tag = ByteArray(WireFrame.AUTH_TAG_SIZE)
            )
            val serialized = frame.serialize()
            out.write(serialized)
            out.flush()
            delay(200)
            try {
                sock.shutdownOutput()
            } catch (_: IOException) {}
            sock.close()
        } catch (_: IOException) {
        } catch (_: SecurityException) {}
    }

    suspend fun sendPairDecline(host: String, port: Int, reason: String = "DECLINED") = withContext(Dispatchers.IO) {
        pendingInitiatorKeypair = null
        pendingInitiatorNonce = null
        try {
            val cleanHost = host.removePrefix("::ffff:")
            val sock = Socket().apply {
                tcpNoDelay = true
                soTimeout = 5000
                sendBufferSize = 2 * 1024 * 1024
                receiveBufferSize = 2 * 1024 * 1024
            }
            sock.connect(InetSocketAddress(cleanHost, port), 4000)
            val out = sock.getOutputStream()
            val json = JSONObject().put("version", 1).put("reason", reason).toString()
            val frame = WireFrame(
                type = PacketType.PAIR_FAIL,
                nonce = ByteArray(WireFrame.NONCE_SIZE),
                ciphertext = json.toByteArray(Charsets.UTF_8),
                tag = ByteArray(WireFrame.AUTH_TAG_SIZE)
            )
            out.write(frame.serialize())
            out.flush()
            delay(150)
            sock.close()
        } catch (_: IOException) {
        } catch (_: SecurityException) {}
    }

    suspend fun sendPairAccept(host: String, port: Int, acceptPayload: String? = null) = withContext(Dispatchers.IO) {
        try {
            val cleanHost = host.removePrefix("::ffff:")
            val sock = Socket().apply {
                tcpNoDelay = true
                soTimeout = 5000
                sendBufferSize = 2 * 1024 * 1024
                receiveBufferSize = 2 * 1024 * 1024
            }
            sock.connect(InetSocketAddress(cleanHost, port), 4000)
            val out = sock.getOutputStream()
            val localIp = sock.localAddress?.hostAddress?.removePrefix("::ffff:") ?: ""
            val localId = context?.let { com.prabotics.rapidrop.preference.PreferencesManager(it).getDeviceId() } ?: ""
            val json = acceptPayload ?: run {
                val obj = JSONObject()
                obj.put("version", 1)
                if (localId.isNotBlank()) obj.put("id", localId)
                obj.put("status", "accepted")
                obj.put("fromDeviceName", getFriendlyDeviceName())
                obj.put("deviceType", "android")
                obj.put("model", "Device")
                obj.put("chip", "Android")
                obj.put("publicKey", "")
                obj.put("nonce", "")
                if (localIp.isNotBlank()) obj.put("host", localIp)
                obj.put("port", WireFrame.DEFAULT_CLIENT_PORT)
                obj.toString()
            }
            val frame = WireFrame(
                type = PacketType.PAIR_ACCEPT,
                nonce = ByteArray(WireFrame.NONCE_SIZE),
                ciphertext = json.toByteArray(Charsets.UTF_8),
                tag = ByteArray(WireFrame.AUTH_TAG_SIZE)
            )
            out.write(frame.serialize())
            out.flush()
            delay(250)
            try {
                sock.shutdownOutput()
            } catch (_: IOException) {}
            sock.close()
        } catch (_: IOException) {
        } catch (_: SecurityException) {}
    }

    fun handlePairAccept(fromDevice: String, remoteId: String, remotePublicKeyHex: String, remoteNonceHex: String, isFinal: Boolean = true): HandshakeKeys? {
        val initiator = pendingInitiatorKeypair ?: return null
        val initNonce = pendingInitiatorNonce ?: return null
        val remotePubBytes = CryptoEngine.hexToBytes(remotePublicKeyHex) ?: return null
        val remoteNonceBytes = CryptoEngine.hexToBytes(remoteNonceHex) ?: return null
        if (remotePubBytes.size != 32 || remoteNonceBytes.size != 16) return null

        val initPubBytes = CryptoEngine.hexToBytes(initiator.second) ?: return null
        val sharedSecret = try {
            CryptoEngine.computeSharedSecret(initiator.first, remotePublicKeyHex)
        } catch (_: java.security.GeneralSecurityException) {
            return null
        }

        val initiatorId = context?.let { com.prabotics.rapidrop.preference.PreferencesManager(it).getDeviceId() } ?: ""
        val keys = CryptoEngine.deriveHandshakeKeys(
            sharedSecret = sharedSecret,
            initiatorPublicKey = initPubBytes,
            receiverPublicKey = remotePubBytes,
            initiatorNonce = initNonce,
            receiverNonce = remoteNonceBytes,
            initiatorId = initiatorId,
            receiverId = remoteId
        )
        this.sessionKey = keys.sessionKey
        if (isFinal) {
            this.pendingInitiatorKeypair = null
            this.pendingInitiatorNonce = null
        }
        return keys
    }

    fun setPendingInitiatorStateForTesting(privateKey: ByteArray, publicKeyHex: String, nonce: ByteArray) {
        this.pendingInitiatorKeypair = Pair(privateKey, publicKeyHex)
        this.pendingInitiatorNonce = nonce
    }

    fun hasPendingInitiatorStateForTesting(): Boolean =
        pendingInitiatorKeypair != null && pendingInitiatorNonce != null

    suspend fun sendDisconnect() = withContext(Dispatchers.IO) {
        try {
            val out = outputStream ?: return@withContext
            val frame = WireFrame(
                type = PacketType.DISCONNECT,
                nonce = ByteArray(WireFrame.NONCE_SIZE),
                ciphertext = "UNPAIR".toByteArray(Charsets.UTF_8),
                tag = ByteArray(WireFrame.AUTH_TAG_SIZE)
            )
            synchronized(out) {
                out.write(frame.serialize())
                out.flush()
            }
            delay(120)
        } catch (_: IOException) {
        } catch (_: SecurityException) {}
        disconnect()
    }

    fun disconnect(gen: Long = 0) {
        if (gen > 0 && connectionGeneration.get() != gen) {
            return
        }
        val nextGen = connectionGeneration.incrementAndGet()
        Log.d("RapiDrop", "connection.disconnected gen=$gen nextGen=$nextGen")
        heartbeatJob?.cancel()
        heartbeatJob = null
        pendingInitiatorKeypair = null
        pendingInitiatorNonce = null
        activeIncomingTransfer?.let { transfer ->
            transfer.writeChannel.close()
            transfer.writerJob?.cancel()
            try { transfer.outputStream.close() } catch (_: IOException) {}
            context?.let { ctx ->
                transfer.targetUri?.let { uri ->
                    com.prabotics.rapidrop.clipboard.MediaStorageHelper.deleteDownloadUri(ctx, uri)
                }
            }
            activeIncomingTransfer = null
            onTransferCancelled?.invoke(transfer.transferId, "Connection lost")
        }
        try {
            socket?.close()
        } catch (_: IOException) {}
        socket = null
        outputStream = null
        if (isConnectedState.compareAndSet(true, false)) {
            onConnectionStateChanged(false)
        }
    }

    private suspend fun readLoop(input: InputStream, gen: Long) = withContext(Dispatchers.IO) {
        val lengthBuffer = ByteArray(4)
        while (socket?.isConnected == true && !socket!!.isClosed && connectionGeneration.get() == gen) {
            try {
                if (!readFully(input, lengthBuffer, 0, 4)) {
                    break
                }
                val payloadLength = ByteBuffer.wrap(lengthBuffer).order(ByteOrder.BIG_ENDIAN).int
                if (payloadLength < WireFrame.AUTH_TAG_SIZE || payloadLength > WireFrame.MAX_PAYLOAD_SIZE) {
                    break
                }
                val remainingSize = (WireFrame.HEADER_SIZE - 4) + payloadLength
                val fullFrameBytes = ByteArray(4 + remainingSize)
                System.arraycopy(lengthBuffer, 0, fullFrameBytes, 0, 4)
                if (!readFully(input, fullFrameBytes, 4, remainingSize)) {
                    break
                }
                val frame = WireFrame.deserialize(fullFrameBytes) ?: break
                if (connectionGeneration.get() != gen) {
                    break
                }
                processIncomingFrame(frame)
            } catch (_: IOException) {
                break
            } catch (_: java.security.GeneralSecurityException) {
                break
            } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
                break
            } catch (_: Exception) {
                break
            }
        }
        disconnect(gen)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Boolean {
        var totalRead = 0
        while (totalRead < length) {
            val read = input.read(buffer, offset + totalRead, length - totalRead)
            if (read == -1) return false
            totalRead += read
        }
        return true
    }

    private fun sendPairRequest() {
        val out = outputStream ?: return
        val key = sessionKey ?: return
        try {
            val deviceName = getFriendlyDeviceName()
            val json = JSONObject()
                .put("deviceName", deviceName)
                .put("modelId", Build.MODEL ?: "Android")
                .put("modelName", deviceName)
                .put("chip", Build.HARDWARE ?: "ARM")
                .toString()
            val enc = CryptoEngine.encrypt(json.toByteArray(Charsets.UTF_8), key)
            val frame = WireFrame(
                type = PacketType.PAIR_REQUEST,
                nonce = enc.nonce,
                ciphertext = enc.ciphertext,
                tag = enc.tag
            )
            synchronized(out) {
                out.write(frame.serialize())
                out.flush()
            }
        } catch (_: IOException) {
        } catch (_: SecurityException) {}
    }

    fun sendDeviceInfo() {
        val out = outputStream ?: return
        val key = sessionKey ?: return
        try {
            val deviceName = getFriendlyDeviceName()
            val json = JSONObject()
                .put("deviceName", deviceName)
                .put("modelId", Build.MODEL ?: "Android")
                .put("modelName", deviceName)
                .put("chip", Build.HARDWARE ?: "ARM")
                .toString()
            val enc = CryptoEngine.encrypt(json.toByteArray(Charsets.UTF_8), key)
            val frame = WireFrame(
                type = PacketType.DEVICE_INFO,
                nonce = enc.nonce,
                ciphertext = enc.ciphertext,
                tag = enc.tag
            )
            synchronized(out) {
                out.write(frame.serialize())
                out.flush()
            }
        } catch (_: IOException) {
        } catch (_: SecurityException) {}
    }
    private fun startHeartbeat(gen: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && connectionGeneration.get() == gen && socket?.isConnected == true) {
                delay(4000)
                if (connectionGeneration.get() != gen) break
                val key = sessionKey ?: continue
                val out = outputStream ?: continue
                try {
                    val (cipher, nonce, tag) = CryptoEngine.encrypt("PING".toByteArray(Charsets.UTF_8), key)
                    val pingFrame = WireFrame(type = PacketType.PING, nonce = nonce, ciphertext = cipher, tag = tag)
                    synchronized(out) {
                        out.write(pingFrame.serialize())
                        out.flush()
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }


    private suspend fun processIncomingFrame(frame: WireFrame) {
        if (frame.type == PacketType.PING) {
            val out = outputStream ?: return
            val key = sessionKey ?: return
            try {
                val encrypted = CryptoEngine.encrypt("PONG".toByteArray(Charsets.UTF_8), key)
                val responseFrame = WireFrame(
                    type = PacketType.PONG,
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext,
                    tag = encrypted.tag
                )
                val serialized = responseFrame.serialize()
                synchronized(out) {
                    out.write(serialized)
                    out.flush()
                }
            } catch (_: IOException) {
                disconnect()
            } catch (_: SecurityException) {
                disconnect()
            }
            return
        }
        if (frame.type == PacketType.PONG) {
            return
        }
        if (frame.type == PacketType.DISCONNECT) {
            onRemoteUnpaired?.invoke()
            disconnect()
            return
        }
        if (frame.type == PacketType.PAIR_FAIL) {
            val raw = String(frame.ciphertext, Charsets.UTF_8)
            val reason = try { JSONObject(raw).optString("reason").takeIf { it.isNotBlank() } ?: raw } catch (_: Exception) { raw }
            onPairFailed?.invoke(reason)
            disconnect()
            return
        }
        val key = sessionKey
        if (key == null) {
            disconnect()
            return
        }
        if (frame.type == PacketType.CONFIG_SYNC) {
            val decrypted = try {
                CryptoEngine.decrypt(frame.ciphertext, frame.nonce, frame.tag, key)
            } catch (_: java.security.GeneralSecurityException) {
                return
            }
            val jsonStr = String(decrypted, Charsets.UTF_8)
            val privacyMode = try {
                val obj = JSONObject(jsonStr)
                if (obj.has("privacyMode")) obj.getBoolean("privacyMode") else null
            } catch (_: Exception) { null }
            if (privacyMode != null) {
                onPrivacyModeUpdated?.invoke(privacyMode)
            }
            return
        }
        val decrypted = try {
            CryptoEngine.decrypt(frame.ciphertext, frame.nonce, frame.tag, key)
        } catch (_: java.security.GeneralSecurityException) {
            disconnect()
            return
        }
        when (frame.type) {
            PacketType.PAIR_CONFIRM -> {
                Log.d("RapiDrop", "pairing.confirm_received state=connected")
                if (isConnectedState.compareAndSet(false, true)) {
                    onConnectionStateChanged(true)
                }
                startHeartbeat(connectionGeneration.get())
                sendDeviceInfo()
            }
            PacketType.DEVICE_INFO -> {
                Log.d("RapiDrop", "protocol.frame_received type=device_info")
                if (isConnectedState.compareAndSet(false, true)) {
                    onConnectionStateChanged(true)
                }
                startHeartbeat(connectionGeneration.get())
                val jsonStr = String(decrypted, Charsets.UTF_8)
                val json = try { JSONObject(jsonStr) } catch (_: Exception) { JSONObject() }
                val deviceName = json.optString("deviceName").takeIf { it.isNotBlank() } ?: "Device"
                val modelId = json.optString("modelId").takeIf { it.isNotBlank() } ?: "Android"
                val modelName = json.optString("modelName").takeIf { it.isNotBlank() } ?: deviceName
                val chip = json.optString("chip").takeIf { it.isNotBlank() } ?: "ARM"
                val info = ConnectedDeviceInfo(
                    modelId = modelId,
                    modelName = modelName,
                    chip = chip,
                    deviceName = deviceName
                )
                onDeviceInfoReceived(info)
            }
            PacketType.CLIP_TEXT -> {
                val text = String(decrypted, Charsets.UTF_8)
                onClipReceived(ClipItem(type = ClipContentType.TEXT, textContent = text))
            }
            PacketType.CLIP_URL -> {
                val url = String(decrypted, Charsets.UTF_8)
                onClipReceived(ClipItem(type = ClipContentType.URL, textContent = url))
            }
            PacketType.CLIP_IMAGE -> {
                onClipReceived(ClipItem(type = ClipContentType.IMAGE, rawData = decrypted))
            }
            PacketType.CLIP_FILE -> {
                if (decrypted.size >= 2) {
                    val nameLength = ((decrypted[0].toInt() and 0xFF) shl 8) or (decrypted[1].toInt() and 0xFF)
                    if (decrypted.size >= 2 + nameLength) {
                        val fileName = String(decrypted, 2, nameLength, Charsets.UTF_8)
                        val fileData = ByteArray(decrypted.size - 2 - nameLength)
                        System.arraycopy(decrypted, 2 + nameLength, fileData, 0, fileData.size)
                        onClipReceived(ClipItem(type = ClipContentType.FILE, fileName = fileName, rawData = fileData))
                    }
                }
            }
            PacketType.FILE_START -> {
                val jsonStr = String(decrypted, Charsets.UTF_8)
                val json = try { JSONObject(jsonStr) } catch (_: Exception) { JSONObject() }
                val tid = json.optString("transferId")
                val fIdx = json.optInt("fileIndex", 0)
                val totalF = json.optInt("totalFiles", 1)
                val fName = json.optString("fileName").takeIf { it.isNotBlank() } ?: "file.bin"
                val relPath = json.optString("relativePath").takeIf { it.isNotBlank() } ?: fName
                val fSize = json.optLong("fileSize", 0L)
                val totalB = json.optLong("totalBytes", fSize)

                activeIncomingTransfer?.let { old ->
                    old.writeChannel.close()
                    old.writerJob?.cancel()
                    try { old.outputStream.close() } catch (_: IOException) {}
                    context?.let { ctx ->
                        old.targetUri?.let { uri ->
                            com.prabotics.rapidrop.clipboard.MediaStorageHelper.deleteDownloadUri(ctx, uri)
                        }
                    }
                    activeIncomingTransfer = null
                }
                context?.let { ctx ->
                    val (rawStream, uri) = com.prabotics.rapidrop.clipboard.MediaStorageHelper.openOutputStreamForDownload(ctx, fName, relPath)
                    val stream = rawStream?.let { java.io.BufferedOutputStream(it, WireFrame.STREAMING_CHUNK_SIZE) }
                    if (stream != null) {
                        val channel = Channel<ByteArray>(capacity = 8)
                        val writer = CoroutineScope(Dispatchers.IO).launch {
                            try {
                                for (chunk in channel) {
                                    stream.write(chunk)
                                }
                                stream.flush()
                            } catch (_: IOException) {}
                        }
                        activeIncomingTransfer = IncomingStreamTransfer(
                            transferId = tid,
                            fileIndex = fIdx,
                            totalFiles = totalF,
                            fileName = fName,
                            relativePath = relPath,
                            fileSize = fSize,
                            totalBytes = totalB,
                            bytesReceived = 0L,
                            outputStream = stream,
                            targetUri = uri,
                            writeChannel = channel,
                            writerJob = writer
                        )
                        onTransferProgress?.invoke(
                            tid,
                            fName,
                            0L,
                            fSize,
                            fIdx,
                            totalF,
                            false
                        )
                    }
                }
            }
            PacketType.FILE_CHUNK -> {
                val transfer = activeIncomingTransfer
                if (transfer != null && decrypted.size >= 28) {
                    val incomingFileIndex = ByteBuffer.wrap(decrypted, 16, 4).order(ByteOrder.BIG_ENDIAN).int
                    val incomingChunkIndex = ByteBuffer.wrap(decrypted, 20, 8).order(ByteOrder.BIG_ENDIAN).long
                    val expectedIdBytes = transfer.transferId.toByteArray(Charsets.UTF_8).copyOf(16)
                    val actualIdBytes = decrypted.copyOfRange(0, 16)
                    if (java.util.Arrays.equals(expectedIdBytes, actualIdBytes) &&
                        incomingFileIndex == transfer.fileIndex &&
                        incomingChunkIndex == transfer.nextChunkIndex
                    ) {
                        val chunkPayloadLength = decrypted.size - 28
                        if (transfer.fileSize > 0L && transfer.bytesReceived + chunkPayloadLength > transfer.fileSize) {
                            transfer.writeChannel.close()
                            transfer.writerJob?.cancel()
                            try { transfer.outputStream.close() } catch (_: IOException) {}
                            context?.let { ctx ->
                                transfer.targetUri?.let { uri ->
                                    com.prabotics.rapidrop.clipboard.MediaStorageHelper.deleteDownloadUri(ctx, uri)
                                }
                            }
                            val tid = transfer.transferId
                            activeIncomingTransfer = null
                            onTransferCancelled?.invoke(tid, "Oversized chunk received")
                            return
                        }
                        val payload = decrypted.copyOfRange(28, 28 + chunkPayloadLength)
                        transfer.digest.update(payload)
                        transfer.bytesReceived += chunkPayloadLength
                        transfer.nextChunkIndex += 1L
                        try {
                            transfer.writeChannel.send(payload)
                        } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
                            return
                        }
                        onTransferProgress?.invoke(
                            transfer.transferId,
                            transfer.fileName,
                            transfer.bytesReceived,
                            transfer.fileSize,
                            transfer.fileIndex,
                            transfer.totalFiles,
                            false
                        )
                    }
                }
            }
            PacketType.FILE_END -> {
                val transfer = activeIncomingTransfer
                if (transfer != null) {
                    transfer.writeChannel.close()
                    transfer.writerJob?.join()
                    try {
                        transfer.outputStream.flush()
                        transfer.outputStream.close()
                    } catch (_: IOException) {}
                    val computedShaBytes = transfer.digest.digest()
                    val computedSha = CryptoEngine.bytesToHex(computedShaBytes)
                    val json = String(decrypted, Charsets.UTF_8)
                    val sentSha = try { JSONObject(json).optString("sha256").takeIf { it.isNotBlank() } } catch (_: Exception) { null }
                    val isSizeValid = transfer.fileSize <= 0L || transfer.bytesReceived == transfer.fileSize
                    if (sentSha != null && sentSha.isNotBlank() && sentSha.equals(computedSha, ignoreCase = true) && isSizeValid) {
                        context?.let { ctx ->
                            transfer.targetUri?.let { uri ->
                                com.prabotics.rapidrop.clipboard.MediaStorageHelper.finalizeDownloadUri(ctx, uri)
                            }
                        }
                        onTransferProgress?.invoke(
                            transfer.transferId,
                            transfer.fileName,
                            transfer.fileSize,
                            transfer.fileSize,
                            transfer.fileIndex,
                            transfer.totalFiles,
                            true
                        )
                        onClipReceived(ClipItem(type = ClipContentType.FILE, fileName = transfer.fileName, rawData = null))
                        activeIncomingTransfer = null
                    } else {
                        context?.let { ctx ->
                            transfer.targetUri?.let { uri ->
                                com.prabotics.rapidrop.clipboard.MediaStorageHelper.deleteDownloadUri(ctx, uri)
                            }
                        }
                        val tid = transfer.transferId
                        activeIncomingTransfer = null
                        onTransferCancelled?.invoke(tid, "Integrity verification failed")
                    }
                }
            }
            PacketType.FILE_CANCEL -> {
                val transfer = activeIncomingTransfer
                if (transfer != null) {
                    transfer.writeChannel.close()
                    transfer.writerJob?.cancel()
                    try {
                        transfer.outputStream.close()
                    } catch (_: IOException) {}
                    context?.let { ctx ->
                        transfer.targetUri?.let { uri ->
                            com.prabotics.rapidrop.clipboard.MediaStorageHelper.deleteDownloadUri(ctx, uri)
                        }
                    }
                    val tid = transfer.transferId
                    activeIncomingTransfer = null
                    onTransferCancelled?.invoke(tid, "Transfer cancelled")
                }
            }
            else -> {}
        }
    }

    suspend fun sendCancelTransfer(transferId: String, reason: String = "user_cancelled") = withContext(Dispatchers.IO) {
        val out = outputStream ?: return@withContext
        val key = sessionKey ?: return@withContext
        val cancelJson = org.json.JSONObject().apply {
            put("transferId", transferId)
            put("reason", reason)
        }
        val cancelBytes = cancelJson.toString().toByteArray(Charsets.UTF_8)
        val encrypted = CryptoEngine.encrypt(cancelBytes, key)
        val frame = WireFrame(
            type = PacketType.FILE_CANCEL,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
            tag = encrypted.tag
        )
        try {
            out.write(frame.serialize())
            out.flush()
        } catch (_: IOException) {}
    }

    fun cancelIncomingTransfer() {
        val transfer = activeIncomingTransfer ?: return
        transfer.writeChannel.close()
        transfer.writerJob?.cancel()
        try {
            transfer.outputStream.close()
        } catch (_: IOException) {}
        context?.let { ctx ->
            transfer.targetUri?.let { uri ->
                com.prabotics.rapidrop.clipboard.MediaStorageHelper.deleteDownloadUri(ctx, uri)
            }
        }
        val tid = transfer.transferId
        activeIncomingTransfer = null
        onTransferCancelled?.invoke(tid, "Transfer cancelled")
    }

    suspend fun sendClip(item: ClipItem) = withContext(Dispatchers.IO) {
        val out = outputStream ?: return@withContext
        val key = sessionKey ?: return@withContext
        try {
            when (item.type) {
                ClipContentType.TEXT -> {
                    val text = item.textContent ?: return@withContext
                    val encrypted = CryptoEngine.encrypt(text.toByteArray(Charsets.UTF_8), key)
                    val frame = WireFrame(
                        type = PacketType.CLIP_TEXT,
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                        tag = encrypted.tag
                    )
                    synchronized(out) {
                        out.write(frame.serialize())
                        out.flush()
                    }
                }
                ClipContentType.URL -> {
                    val url = item.textContent ?: return@withContext
                    val encrypted = CryptoEngine.encrypt(url.toByteArray(Charsets.UTF_8), key)
                    val frame = WireFrame(
                        type = PacketType.CLIP_URL,
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                        tag = encrypted.tag
                    )
                    synchronized(out) {
                        out.write(frame.serialize())
                        out.flush()
                    }
                }
                ClipContentType.IMAGE -> {
                    val bytes = item.rawData ?: return@withContext
                    if (bytes.size > WireFrame.MAX_PAYLOAD_SIZE - WireFrame.HEADER_SIZE) return@withContext
                    val encrypted = CryptoEngine.encrypt(bytes, key)
                    val frame = WireFrame(
                        type = PacketType.CLIP_IMAGE,
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                        tag = encrypted.tag
                    )
                    synchronized(out) {
                        out.write(frame.serialize())
                        out.flush()
                    }
                }
                ClipContentType.FILE -> {
                    val bytes = item.rawData ?: return@withContext
                    val name = item.fileName ?: "file"
                    val nameBytes = name.toByteArray(Charsets.UTF_8)
                    val nameLength = nameBytes.size
                    val totalSize = 2 + nameLength + bytes.size
                    if (totalSize > WireFrame.MAX_PAYLOAD_SIZE - WireFrame.HEADER_SIZE) return@withContext

                    val payload = ByteArray(totalSize)
                    payload[0] = ((nameLength shr 8) and 0xFF).toByte()
                    payload[1] = (nameLength and 0xFF).toByte()
                    System.arraycopy(nameBytes, 0, payload, 2, nameLength)
                    System.arraycopy(bytes, 0, payload, 2 + nameLength, bytes.size)

                    val encrypted = CryptoEngine.encrypt(payload, key)
                    val frame = WireFrame(
                        type = PacketType.CLIP_FILE,
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                        tag = encrypted.tag
                    )
                    synchronized(out) {
                        out.write(frame.serialize())
                        out.flush()
                    }
                }
            }
        } catch (_: IOException) {
            disconnect()
        } catch (_: SecurityException) {
            disconnect()
        }
    }

    suspend fun sendConfigSync(privacyMode: Boolean) = withContext(Dispatchers.IO) {
        val out = outputStream ?: return@withContext
        val key = sessionKey ?: return@withContext
        try {
            val json = "{\"privacyMode\":$privacyMode}"
            val enc = CryptoEngine.encrypt(json.toByteArray(Charsets.UTF_8), key)
            val frame = WireFrame(
                type = PacketType.CONFIG_SYNC,
                nonce = enc.nonce,
                ciphertext = enc.ciphertext,
                tag = enc.tag
            )
            synchronized(out) {
                out.write(frame.serialize())
                out.flush()
            }
        } catch (_: IOException) {
        } catch (_: SecurityException) {}
    }
    suspend fun sendStreamingFile(
        fileName: String,
        relativePath: String,
        fileSize: Long,
        fileIndex: Int,
        totalFiles: Int,
        totalBytes: Long,
        transferId: String,
        inputStream: InputStream,
        onProgress: (bytesSentForFile: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val out = outputStream ?: return@withContext
        val key = sessionKey ?: return@withContext

        val startJson = JSONObject()
            .put("transferId", transferId)
            .put("fileIndex", fileIndex)
            .put("totalFiles", totalFiles)
            .put("fileName", fileName)
            .put("relativePath", relativePath)
            .put("fileSize", fileSize)
            .put("totalBytes", totalBytes)
            .toString()
        val startEnc = CryptoEngine.encrypt(startJson.toByteArray(Charsets.UTF_8), key)
        val startFrame = WireFrame(
            type = PacketType.FILE_START,
            nonce = startEnc.nonce,
            ciphertext = startEnc.ciphertext,
            tag = startEnc.tag
        )
        synchronized(out) {
            out.write(startFrame.serialize())
            out.flush()
        }

        val payloadBuffer = ByteArray(28 + WireFrame.STREAMING_CHUNK_SIZE)
        val idBytes = transferId.toByteArray(Charsets.UTF_8).copyOf(16)
        System.arraycopy(idBytes, 0, payloadBuffer, 0, 16)
        var chunkIndex = 0L
        var bytesSent = 0L
        val digest = java.security.MessageDigest.getInstance("SHA-256")

        while (isActive) {
            val read = inputStream.read(payloadBuffer, 28, WireFrame.STREAMING_CHUNK_SIZE)
            if (read == -1) break

            digest.update(payloadBuffer, 28, read)

            payloadBuffer[16] = ((fileIndex shr 24) and 0xFF).toByte()
            payloadBuffer[17] = ((fileIndex shr 16) and 0xFF).toByte()
            payloadBuffer[18] = ((fileIndex shr 8) and 0xFF).toByte()
            payloadBuffer[19] = (fileIndex and 0xFF).toByte()

            payloadBuffer[20] = ((chunkIndex shr 56) and 0xFF).toByte()
            payloadBuffer[21] = ((chunkIndex shr 48) and 0xFF).toByte()
            payloadBuffer[22] = ((chunkIndex shr 40) and 0xFF).toByte()
            payloadBuffer[23] = ((chunkIndex shr 32) and 0xFF).toByte()
            payloadBuffer[24] = ((chunkIndex shr 24) and 0xFF).toByte()
            payloadBuffer[25] = ((chunkIndex shr 16) and 0xFF).toByte()
            payloadBuffer[26] = ((chunkIndex shr 8) and 0xFF).toByte()
            payloadBuffer[27] = (chunkIndex and 0xFF).toByte()

            val chunkEnc = CryptoEngine.encrypt(payloadBuffer, 0, 28 + read, key)
            val chunkFrame = WireFrame(
                type = PacketType.FILE_CHUNK,
                nonce = chunkEnc.nonce,
                ciphertext = chunkEnc.ciphertext,
                tag = chunkEnc.tag
            )
            synchronized(out) {
                chunkFrame.writeTo(out)
            }

            chunkIndex++
            bytesSent += read
            onProgress(bytesSent)
        }
        synchronized(out) {
            out.flush()
        }

        val fileSha = digest.digest().joinToString("") { "%02x".format(it) }
        val endJson = JSONObject()
            .put("transferId", transferId)
            .put("fileIndex", fileIndex)
            .put("sha256", fileSha)
            .put("status", "OK")
            .toString()
        val endEnc = CryptoEngine.encrypt(endJson.toByteArray(Charsets.UTF_8), key)
        val endFrame = WireFrame(
            type = PacketType.FILE_END,
            nonce = endEnc.nonce,
            ciphertext = endEnc.ciphertext,
            tag = endEnc.tag
        )
        synchronized(out) {
            out.write(endFrame.serialize())
            out.flush()
        }
    }
}
