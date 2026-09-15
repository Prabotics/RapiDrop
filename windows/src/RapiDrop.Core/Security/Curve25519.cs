using System.Numerics;
using System.Security.Cryptography;

namespace RapiDrop.Core.Security;

public static class Curve25519
{
    private static readonly BigInteger P = BigInteger.Pow(2, 255) - 19;
    private static readonly BigInteger A24 = 121665;
    private static readonly byte[] BasePoint = new byte[32] { 9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };

    public static byte[] GeneratePrivateKey()
    {
        byte[] key = new byte[32];
        RandomNumberGenerator.Fill(key);
        return key;
    }

    public static byte[] ComputePublicKey(byte[] privateKey)
    {
        return ScalarMult(privateKey, BasePoint);
    }

    public static byte[] ComputeSharedSecret(byte[] privateKey, byte[] remotePublicKey)
    {
        return ScalarMult(privateKey, remotePublicKey);
    }

    private static byte[] Clamp(byte[] k)
    {
        byte[] clamped = new byte[32];
        Buffer.BlockCopy(k, 0, clamped, 0, 32);
        clamped[0] &= 248;
        clamped[31] &= 127;
        clamped[31] |= 64;
        return clamped;
    }

    private static byte[] ScalarMult(byte[] scalar, byte[] uBytes)
    {
        byte[] clamped = Clamp(scalar);
        BigInteger kInt = DecodeLittleEndian(clamped);
        BigInteger u = Mod(DecodeLittleEndian(uBytes), P);

        BigInteger x1 = u;
        BigInteger x2 = BigInteger.One;
        BigInteger z2 = BigInteger.Zero;
        BigInteger x3 = u;
        BigInteger z3 = BigInteger.One;
        int swap = 0;

        for (int t = 254; t >= 0; t--)
        {
            int kt = (int)((kInt >> t) & 1);
            swap ^= kt;

            if (swap == 1)
            {
                (x2, x3) = (x3, x2);
                (z2, z3) = (z3, z2);
            }
            swap = kt;

            BigInteger a = Mod(x2 + z2, P);
            BigInteger aa = Mod(a * a, P);
            BigInteger b = Mod(x2 - z2, P);
            BigInteger bb = Mod(b * b, P);
            BigInteger e = Mod(aa - bb, P);
            BigInteger c = Mod(x3 + z3, P);
            BigInteger d = Mod(x3 - z3, P);
            BigInteger da = Mod(d * a, P);
            BigInteger cb = Mod(c * b, P);

            BigInteger daPlusCb = Mod(da + cb, P);
            x3 = Mod(daPlusCb * daPlusCb, P);

            BigInteger daMinusCb = Mod(da - cb, P);
            z3 = Mod(x1 * Mod(daMinusCb * daMinusCb, P), P);

            x2 = Mod(aa * bb, P);
            BigInteger a24TimesE = Mod(A24 * e, P);
            z2 = Mod(e * Mod(aa + a24TimesE, P), P);
        }

        if (swap == 1)
        {
            (x2, x3) = (x3, x2);
            (z2, z3) = (z3, z2);
        }

        BigInteger zInv = BigInteger.ModPow(z2, P - 2, P);
        BigInteger result = Mod(x2 * zInv, P);
        return EncodeLittleEndian(result);
    }

    private static BigInteger Mod(BigInteger x, BigInteger m)
    {
        BigInteger r = x % m;
        return r < 0 ? r + m : r;
    }

    private static BigInteger DecodeLittleEndian(byte[] bytes)
    {
        byte[] buffer = new byte[bytes.Length + 1];
        Buffer.BlockCopy(bytes, 0, buffer, 0, bytes.Length);
        buffer[bytes.Length] = 0;
        return new BigInteger(buffer);
    }

    private static byte[] EncodeLittleEndian(BigInteger value)
    {
        byte[] raw = value.ToByteArray();
        byte[] result = new byte[32];
        int count = Math.Min(raw.Length, 32);
        Buffer.BlockCopy(raw, 0, result, 0, count);
        return result;
    }
}
