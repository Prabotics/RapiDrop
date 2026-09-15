package com.prabotics.rapidrop.network

import org.json.JSONObject
import android.content.Context
import com.prabotics.rapidrop.security.CryptoEngine
import com.prabotics.rapidrop.security.HandshakeKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder



class ClientSocketServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val port: Int = WireFrame.DEFAULT_CLIENT_PORT,
    private val onPairInviteReceived: (fromDevice: String, fromId: String, pin: String?, host: String?, port: Int, keys: HandshakeKeys, acceptPayload: String, offerPayload: String) -> Unit,
    private val onPairAcceptReceived: ((fromDevice: String, fromId: String, host: String?, port: Int, remotePublicKeyHex: String, remoteNonceHex: String, status: String) -> Unit)? = null,
    private val onPairDeclinedReceived: ((reason: String) -> Unit)? = null,
    private val onRemoteUnpairedReceived: (() -> Unit)? = null
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    fun start() {
        stop()
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port), 50)
                }
                serverSocket = server
                while (isActive && !server.isClosed) {
                    val client = server.accept()
                    scope.launch(Dispatchers.IO) {
                        handleClient(client)
                    }
                }
            } catch (_: IOException) {
            } catch (_: SecurityException) {}
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (_: IOException) {}
        serverSocket = null
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10000
            socket.sendBufferSize = 2 * 1024 * 1024
            socket.receiveBufferSize = 2 * 1024 * 1024
            val input = socket.getInputStream()
            val lengthBuffer = ByteArray(4)

            if (readFully(input, lengthBuffer, 0, 4)) {
                val payloadLength = ByteBuffer.wrap(lengthBuffer).order(ByteOrder.BIG_ENDIAN).int
                if (payloadLength >= WireFrame.AUTH_TAG_SIZE && payloadLength <= WireFrame.MAX_PAYLOAD_SIZE) {
                    val remainingSize = (WireFrame.HEADER_SIZE - 4) + payloadLength
                    val fullFrameBytes = ByteArray(4 + remainingSize)
                    System.arraycopy(lengthBuffer, 0, fullFrameBytes, 0, 4)
                    if (readFully(input, fullFrameBytes, 4, remainingSize)) {
                        val frame = WireFrame.deserialize(fullFrameBytes) ?: return@withContext
                        when (frame.type) {
                            PacketType.PAIR_INVITE -> {
                                android.util.Log.d("RapiDrop", "pairing.invite_received")
                                val jsonStr = String(frame.ciphertext, Charsets.UTF_8)
                                val json = try { JSONObject(jsonStr) } catch (_: Exception) { return@withContext }
                                val version = json.optInt("version", 1)
                                val fromName = json.optString("fromDeviceName").takeIf { it.isNotBlank() } ?: "Device"
                                val remotePubHex = json.optString("publicKey").takeIf { it.isNotBlank() }
                                val remoteNonceHex = json.optString("nonce").takeIf { it.isNotBlank() }
                                if (version < 1 || remotePubHex == null || remoteNonceHex == null) {
                                    return@withContext
                                }

                                val remotePubBytes = CryptoEngine.hexToBytes(remotePubHex) ?: return@withContext
                                val remoteNonceBytes = CryptoEngine.hexToBytes(remoteNonceHex) ?: return@withContext
                                if (remotePubBytes.size != 32 || remoteNonceBytes.size != 16) return@withContext

                                val (privB, pubBHex) = CryptoEngine.generateEphemeralKeypair()
                                val pubBBytes = CryptoEngine.hexToBytes(pubBHex) ?: return@withContext
                                val nonceBBytes = CryptoEngine.generateNonce(16)
                                val nonceBHex = CryptoEngine.bytesToHex(nonceBBytes)

                                val sharedSecret = try {
                                    CryptoEngine.computeSharedSecret(privB, remotePubHex)
                                } catch (_: java.security.GeneralSecurityException) {
                                    return@withContext
                                }

                                val localDeviceName = DeviceNameHelper.getDeviceFriendlyName(context)
                                val initiatorId = json.optString("id")
                                val receiverId = com.prabotics.rapidrop.preference.PreferencesManager(context).getDeviceId()
                                val keys = CryptoEngine.deriveHandshakeKeys(
                                    sharedSecret = sharedSecret,
                                    initiatorPublicKey = remotePubBytes,
                                    receiverPublicKey = pubBBytes,
                                    initiatorNonce = remoteNonceBytes,
                                    receiverNonce = nonceBBytes,
                                    initiatorId = initiatorId,
                                    receiverId = receiverId
                                )
                                android.util.Log.d("RapiDrop", "authentication.keys_derived")

                                val remoteIp = socket.inetAddress?.hostAddress?.removePrefix("::ffff:")
                                val host = json.optString("host").removePrefix("::ffff:").takeIf { it.isNotBlank() } ?: remoteIp
                                val port = json.optInt("port", WireFrame.DEFAULT_PORT)

                                val localIp = socket.localAddress?.hostAddress?.removePrefix("::ffff:") ?: ""
                                fun buildPayload(status: String): String {
                                    val obj = JSONObject()
                                    obj.put("version", 1)
                                    if (receiverId.isNotBlank()) obj.put("id", receiverId)
                                    obj.put("status", status)
                                    obj.put("fromDeviceName", localDeviceName)
                                    obj.put("deviceType", "android")
                                    obj.put("model", "Device")
                                    obj.put("chip", "Android")
                                    obj.put("publicKey", pubBHex)
                                    obj.put("nonce", nonceBHex)
                                    if (localIp.isNotBlank()) obj.put("host", localIp)
                                    obj.put("port", WireFrame.DEFAULT_CLIENT_PORT)
                                    return obj.toString()
                                }
                                val acceptPayload = buildPayload("accepted")
                                val offerPayload = buildPayload("offered")
                                onPairInviteReceived(fromName, initiatorId, keys.sasCode, host, port, keys, acceptPayload, offerPayload)
                            }
                            PacketType.PAIR_ACCEPT -> {
                                android.util.Log.d("RapiDrop", "pairing.accept_received")
                                val jsonStr = String(frame.ciphertext, Charsets.UTF_8)
                                val json = try { JSONObject(jsonStr) } catch (_: Exception) { return@withContext }
                                val fromName = json.optString("fromDeviceName").takeIf { it.isNotBlank() } ?: "Device"
                                val remoteId = json.optString("id")
                                val remotePubHex = json.optString("publicKey")
                                val remoteNonceHex = json.optString("nonce")
                                val status = json.optString("status").takeIf { it.isNotBlank() } ?: "accepted"
                                val remoteIp = socket.inetAddress?.hostAddress?.removePrefix("::ffff:")
                                val host = json.optString("host").removePrefix("::ffff:").takeIf { it.isNotBlank() } ?: remoteIp
                                val port = json.optInt("port", WireFrame.DEFAULT_PORT)
                                onPairAcceptReceived?.invoke(fromName, remoteId, host, port, remotePubHex, remoteNonceHex, status)
                            }
                            PacketType.PAIR_FAIL -> {
                                val raw = String(frame.ciphertext, Charsets.UTF_8)
                                val reason = try { JSONObject(raw).optString("reason").takeIf { it.isNotBlank() } ?: raw } catch (_: Exception) { raw }
                                onPairDeclinedReceived?.invoke(reason)
                            }
                            PacketType.DISCONNECT -> {
                                onRemoteUnpairedReceived?.invoke()
                            }
                            else -> {}
                        }
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {}
        }
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
}
