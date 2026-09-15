package com.prabotics.rapidrop.security

import java.math.BigInteger
import java.security.SecureRandom



object Curve25519 {
    private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BASE_POINT: ByteArray = ByteArray(32).apply { this[0] = 9 }

    fun generatePrivateKey(random: SecureRandom = SecureRandom()): ByteArray {
        val key = ByteArray(32)
        random.nextBytes(key)
        return key
    }

    fun computePublicKey(privateKey: ByteArray): ByteArray {
        return scalarMult(privateKey, BASE_POINT)
    }

    fun computeSharedSecret(privateKey: ByteArray, remotePublicKey: ByteArray): ByteArray {
        return scalarMult(privateKey, remotePublicKey)
    }

    private fun clamp(k: ByteArray): ByteArray {
        val clamped = k.copyOf(32)
        clamped[0] = (clamped[0].toInt() and 248).toByte()
        clamped[31] = (clamped[31].toInt() and 127).toByte()
        clamped[31] = (clamped[31].toInt() or 64).toByte()
        return clamped
    }

    private fun scalarMult(scalar: ByteArray, uBytes: ByteArray): ByteArray {
        val clamped = clamp(scalar)
        val kInt = decodeLittleEndian(clamped)
        val u = decodeLittleEndian(uBytes).mod(P)

        val x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = kInt.testBit(t)
            val ktInt = if (kt) 1 else 0
            swap = swap xor ktInt

            if (swap == 1) {
                var dummy = x2
                x2 = x3
                x3 = dummy

                dummy = z2
                z2 = z3
                z3 = dummy
            }
            swap = ktInt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            val daPlusCb = da.add(cb).mod(P)
            x3 = daPlusCb.multiply(daPlusCb).mod(P)

            val daMinusCb = da.subtract(cb).mod(P)
            z3 = x1.multiply(daMinusCb.multiply(daMinusCb).mod(P)).mod(P)

            x2 = aa.multiply(bb).mod(P)
            val a24TimesE = A24.multiply(e).mod(P)
            z2 = e.multiply(aa.add(a24TimesE).mod(P)).mod(P)
        }

        if (swap == 1) {
            var dummy = x2
            x2 = x3
            x3 = dummy

            dummy = z2
            z2 = z3
            z3 = dummy
        }

        val zInv = z2.modInverse(P)
        val result = x2.multiply(zInv).mod(P)
        return encodeLittleEndian(result)
    }

    private fun decodeLittleEndian(bytes: ByteArray): BigInteger {
        val reversed = ByteArray(bytes.size)
        for (i in bytes.indices) {
            reversed[i] = bytes[bytes.size - 1 - i]
        }
        return BigInteger(1, reversed)
    }

    private fun encodeLittleEndian(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val result = ByteArray(32)
        var srcPos = raw.size - 1
        var dstPos = 0
        while (srcPos >= 0 && dstPos < 32) {
            result[dstPos++] = raw[srcPos--]
        }
        return result
    }
}
