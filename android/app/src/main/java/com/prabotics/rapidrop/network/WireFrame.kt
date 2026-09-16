package com.prabotics.rapidrop.network

import java.nio.ByteBuffer
import java.nio.ByteOrder



enum class PacketType(val value: Short) {
    PING(0x0001),
    PONG(0x0002),
    PAIR_REQUEST(0x0003),
    PAIR_CONFIRM(0x0004),
    DEVICE_INFO(0x0005),
    PAIR_INVITE(0x0006),
    PAIR_FAIL(0x0007),
    PAIR_ACCEPT(0x0008),
    CLIP_TEXT(0x0010),
    CLIP_URL(0x0011),
    CLIP_IMAGE(0x0012),
    CLIP_FILE(0x0013),
    FILE_START(0x0014),
    FILE_CHUNK(0x0015),
    FILE_END(0x0016),
    FILE_CANCEL(0x0017),
    CONFIG_SYNC(0x0020),
    DISCONNECT(0x00FF.toShort());

    companion object {
        private val map = entries.associateBy { it.value }
        fun fromValue(value: Short): PacketType? = map[value]
    }
}

data class WireFrame(
    val type: PacketType,
    val timestamp: Long = System.currentTimeMillis(),
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val tag: ByteArray
) {
    companion object {
        const val PROTOCOL_VERSION: Short = 0x0001
        const val HEADER_SIZE: Int = 28
        const val MAX_PAYLOAD_SIZE: Int = 104_857_600
        const val AUTH_TAG_SIZE: Int = 16
        const val NONCE_SIZE: Int = 12
        const val STREAMING_CHUNK_SIZE: Int = 1_048_576
        const val DEFAULT_PORT: Int = 58240
        const val DEFAULT_CLIENT_PORT: Int = 58241
        const val SERVICE_TYPE: String = "_clipsync._tcp"
        const val CLIENT_SERVICE_TYPE: String = "_clipsync-cli._tcp"
        const val INACTIVITY_TIMEOUT_MS: Long = 16000L
        const val WATCHDOG_INTERVAL_MS: Long = 5000L
        const val HEARTBEAT_INTERVAL_MS: Long = 4000L
        const val PAIRING_INVITE_TIMEOUT_MS: Long = 30000L
        const val MAX_IN_FLIGHT_CHUNKS: Int = 4
        const val CHUNK_HEADER_SIZE: Int = 28
        fun deserialize(bytes: ByteArray): WireFrame? {
            if (bytes.size < HEADER_SIZE + AUTH_TAG_SIZE) return null

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val payloadLength = buffer.int
            if (payloadLength < AUTH_TAG_SIZE || payloadLength > MAX_PAYLOAD_SIZE) return null
            if (bytes.size != HEADER_SIZE + payloadLength) return null

            val typeRaw = buffer.short
            val packetType = PacketType.fromValue(typeRaw) ?: return null

            val version = buffer.short
            if (version != PROTOCOL_VERSION) return null

            val timestamp = buffer.long

            val nonce = ByteArray(NONCE_SIZE)
            buffer.get(nonce)

            val ciphertextLength = payloadLength - AUTH_TAG_SIZE
            val ciphertext = ByteArray(ciphertextLength)
            buffer.get(ciphertext)

            val tag = ByteArray(AUTH_TAG_SIZE)
            buffer.get(tag)

            return WireFrame(
                type = packetType,
                timestamp = timestamp,
                nonce = nonce,
                ciphertext = ciphertext,
                tag = tag
            )
        }
    }

    fun serialize(): ByteArray {
        val payloadLength = ciphertext.size + tag.size
        val totalSize = HEADER_SIZE + payloadLength
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(payloadLength)
        buffer.putShort(type.value)
        buffer.putShort(PROTOCOL_VERSION)
        buffer.putLong(timestamp)
        buffer.put(nonce, 0, minOf(nonce.size, NONCE_SIZE))
        buffer.put(ciphertext)
        buffer.put(tag, 0, minOf(tag.size, AUTH_TAG_SIZE))

        return buffer.array()
    }

    fun writeTo(out: java.io.OutputStream) {
        val payloadLength = ciphertext.size + tag.size
        val header = ByteArray(HEADER_SIZE)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(payloadLength)
        buffer.putShort(type.value)
        buffer.putShort(PROTOCOL_VERSION)
        buffer.putLong(timestamp)
        buffer.put(nonce, 0, minOf(nonce.size, NONCE_SIZE))
        out.write(header, 0, HEADER_SIZE)
        out.write(ciphertext)
        out.write(tag, 0, minOf(tag.size, AUTH_TAG_SIZE))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WireFrame
        if (type != other.type) return false
        if (timestamp != other.timestamp) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (!tag.contentEquals(other.tag)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + tag.contentHashCode()
        return result
    }
}
