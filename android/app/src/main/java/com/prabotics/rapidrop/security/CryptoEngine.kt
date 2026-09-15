package com.prabotics.rapidrop.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec



data class EncryptionResult(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val tag: ByteArray
)

data class HandshakeKeys(
    val sessionKey: SecretKey,
    val authKey: ByteArray,
    val pairRecordKey: ByteArray,
    val sasCode: String,
    val transcriptHash: ByteArray
)

object CryptoEngine {
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_NONCE_LENGTH = 12
    private val secureRandom = SecureRandom()
    private val cipherThreadLocal = ThreadLocal.withInitial {
        Cipher.getInstance("AES/GCM/NoPadding")
    }

    private fun getCipher(): Cipher =
        cipherThreadLocal.get() ?: Cipher.getInstance("AES/GCM/NoPadding")

    fun deriveKeyFromPin(pin: String): SecretKey {
        val cleanPin = pin.filter { it.isDigit() }
        val secret = "RapiDrop-PIN-$cleanPin".toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        val keyBytes = md.digest(secret)
        return SecretKeySpec(keyBytes, "AES")
    }

    fun generateEphemeralKeypair(): Pair<ByteArray, String> {
        val priv = Curve25519.generatePrivateKey(secureRandom)
        val pub = Curve25519.computePublicKey(priv)
        return Pair(priv, bytesToHex(pub))
    }

    fun generateNonce(count: Int = 16): ByteArray {
        val nonce = ByteArray(count)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.trim()
        if (clean.length % 2 != 0) return null
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            val byteStr = clean.substring(i * 2, i * 2 + 2)
            result[i] = byteStr.toIntOrNull(16)?.toByte() ?: return null
        }
        return result
    }

    fun computeSharedSecret(privateKey: ByteArray, remotePublicKeyHex: String): ByteArray {
        val remotePub = hexToBytes(remotePublicKeyHex) ?: throw IllegalArgumentException("Invalid public key hex")
        if (remotePub.size != 32) throw IllegalArgumentException("Invalid public key size")
        return Curve25519.computeSharedSecret(privateKey, remotePub)
    }

    fun deriveHandshakeKeys(
        sharedSecret: ByteArray,
        initiatorPublicKey: ByteArray,
        receiverPublicKey: ByteArray,
        initiatorNonce: ByteArray,
        receiverNonce: ByteArray,
        initiatorId: String,
        receiverId: String
    ): HandshakeKeys {
        val pipe = "|".toByteArray(Charsets.UTF_8)
        val transcript = ByteArray(
            "RapiDrop-v1|".toByteArray(Charsets.UTF_8).size +
            initiatorPublicKey.size + pipe.size +
            receiverPublicKey.size + pipe.size +
            initiatorNonce.size + pipe.size +
            receiverNonce.size + pipe.size +
            initiatorId.toByteArray(Charsets.UTF_8).size + pipe.size +
            receiverId.toByteArray(Charsets.UTF_8).size
        )

        var offset = 0
        fun append(bytes: ByteArray) {
            System.arraycopy(bytes, 0, transcript, offset, bytes.size)
            offset += bytes.size
        }

        append("RapiDrop-v1|".toByteArray(Charsets.UTF_8))
        append(initiatorPublicKey)
        append(pipe)
        append(receiverPublicKey)
        append(pipe)
        append(initiatorNonce)
        append(pipe)
        append(receiverNonce)
        append(pipe)
        append(initiatorId.toByteArray(Charsets.UTF_8))
        append(pipe)
        append(receiverId.toByteArray(Charsets.UTF_8))
        val md = MessageDigest.getInstance("SHA-256")
        val transcriptHash = md.digest(transcript)

        val salt = ByteArray(initiatorNonce.size + receiverNonce.size)
        System.arraycopy(initiatorNonce, 0, salt, 0, initiatorNonce.size)
        System.arraycopy(receiverNonce, 0, salt, initiatorNonce.size, receiverNonce.size)

        val prk = hkdfExtract(salt, sharedSecret)

        val sessInfo = "RapiDrop-v1-Session-Key|".toByteArray(Charsets.UTF_8) + transcriptHash
        val sessionKeyBytes = hkdfExpand(prk, sessInfo, 32)
        val sessionKey = SecretKeySpec(sessionKeyBytes, "AES")

        val authInfo = "RapiDrop-v1-Auth-Token|".toByteArray(Charsets.UTF_8) + transcriptHash
        val authKey = hkdfExpand(prk, authInfo, 32)

        val pairInfo = "RapiDrop-v1-Pair-Record|".toByteArray(Charsets.UTF_8) + transcriptHash
        val pairRecordKey = hkdfExpand(prk, pairInfo, 32)

        val val32 = ((authKey[0].toInt() and 0xFF).toLong() shl 24) or
                    ((authKey[1].toInt() and 0xFF).toLong() shl 16) or
                    ((authKey[2].toInt() and 0xFF).toLong() shl 8) or
                    (authKey[3].toInt() and 0xFF).toLong()
        val sasNum = (val32 % 1_000_000).toInt()
        val sasCode = String.format(java.util.Locale.US, "%06d", sasNum)

        return HandshakeKeys(
            sessionKey = sessionKey,
            authKey = authKey,
            pairRecordKey = pairRecordKey,
            sasCode = sasCode,
            transcriptHash = transcriptHash
        )
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val actualSalt = if (salt.isNotEmpty()) salt else ByteArray(32)
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var i = 1
        while (offset < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val toCopy = Math.min(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
            i++
        }
        return okm
    }

    fun encrypt(payload: ByteArray, key: SecretKey): EncryptionResult =
        encrypt(payload, 0, payload.size, key)

    fun encrypt(input: ByteArray, offset: Int, length: Int, key: SecretKey): EncryptionResult {
        val nonce = ByteArray(GCM_NONCE_LENGTH)
        secureRandom.nextBytes(nonce)

        val cipher = getCipher()
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val cipherWithTag = cipher.doFinal(input, offset, length)
        val tagLength = GCM_TAG_LENGTH_BITS / 8
        val ciphertextLength = cipherWithTag.size - tagLength

        val ciphertext = ByteArray(ciphertextLength)
        val tag = ByteArray(tagLength)

        System.arraycopy(cipherWithTag, 0, ciphertext, 0, ciphertextLength)
        System.arraycopy(cipherWithTag, ciphertextLength, tag, 0, tagLength)

        return EncryptionResult(ciphertext, nonce, tag)
    }

    fun decrypt(
        ciphertext: ByteArray,
        nonce: ByteArray,
        tag: ByteArray,
        key: SecretKey
    ): ByteArray {
        val cipher = getCipher()
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val p1 = cipher.update(ciphertext)
        val p2 = cipher.doFinal(tag)
        return when {
            p1 == null || p1.isEmpty() -> p2 ?: ByteArray(0)
            p2 == null || p2.isEmpty() -> p1
            else -> {
                val combined = ByteArray(p1.size + p2.size)
                System.arraycopy(p1, 0, combined, 0, p1.size)
                System.arraycopy(p2, 0, combined, p1.size, p2.size)
                combined
            }
        }
    }
}
