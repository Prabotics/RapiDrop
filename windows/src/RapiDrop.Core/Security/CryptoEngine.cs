using System.Security.Cryptography;
using System.Text;

namespace RapiDrop.Core.Security;

public record HandshakeKeys(
    byte[] SessionKey,
    byte[] AuthKey,
    byte[] PairRecordKey,
    string SasCode,
    byte[] TranscriptHash
);

public static class CryptoEngine
{
    private const int NonceSize = 12;
    private const int TagSize = 16;
    private const int KeySize = 32;

    public static byte[] DeriveKeyFromPin(string pin)
    {
        string cleanPin = new string(pin.Where(char.IsDigit).ToArray());
        byte[] secret = Encoding.UTF8.GetBytes($"RapiDrop-PIN-{cleanPin}");
        return SHA256.HashData(secret);
    }

    public static (byte[] PrivateKey, string PublicKeyHex) GenerateEphemeralKeypair()
    {
        byte[] priv = Curve25519.GeneratePrivateKey();
        byte[] pub = Curve25519.ComputePublicKey(priv);
        return (priv, Convert.ToHexString(pub).ToLowerInvariant());
    }

    public static byte[] GenerateNonce(int count = 16)
    {
        byte[] nonce = new byte[count];
        RandomNumberGenerator.Fill(nonce);
        return nonce;
    }

    public static byte[]? HexToBytes(string hex)
    {
        string clean = hex.Trim();
        if (clean.Length % 2 != 0) return null;
        try
        {
            return Convert.FromHexString(clean);
        }
        catch
        {
            return null;
        }
    }

    public static byte[] ComputeSharedSecret(byte[] privateKey, string remotePublicKeyHex)
    {
        byte[]? remotePub = HexToBytes(remotePublicKeyHex);
        if (remotePub == null || remotePub.Length != 32)
            throw new ArgumentException("Invalid public key hex");
        return Curve25519.ComputeSharedSecret(privateKey, remotePub);
    }

    public static HandshakeKeys DeriveHandshakeKeys(
        byte[] sharedSecret,
        byte[] initiatorPublicKey,
        byte[] receiverPublicKey,
        byte[] initiatorNonce,
        byte[] receiverNonce,
        string initiatorId,
        string receiverId
    )
    {
        byte[] prefix = Encoding.UTF8.GetBytes("RapiDrop-v1|");
        byte[] pipe = Encoding.UTF8.GetBytes("|");
        byte[] idA = Encoding.UTF8.GetBytes(initiatorId);
        byte[] idB = Encoding.UTF8.GetBytes(receiverId);

        using var ms = new MemoryStream();
        ms.Write(prefix);
        ms.Write(initiatorPublicKey);
        ms.Write(pipe);
        ms.Write(receiverPublicKey);
        ms.Write(pipe);
        ms.Write(initiatorNonce);
        ms.Write(pipe);
        ms.Write(receiverNonce);
        ms.Write(pipe);
        ms.Write(idA);
        ms.Write(pipe);
        ms.Write(idB);
        byte[] transcript = ms.ToArray();
        byte[] transcriptHash = SHA256.HashData(transcript);

        byte[] salt = new byte[initiatorNonce.Length + receiverNonce.Length];
        Buffer.BlockCopy(initiatorNonce, 0, salt, 0, initiatorNonce.Length);
        Buffer.BlockCopy(receiverNonce, 0, salt, initiatorNonce.Length, receiverNonce.Length);

        byte[] prk = HKDF.Extract(HashAlgorithmName.SHA256, sharedSecret, salt);

        byte[] sessInfo = Encoding.UTF8.GetBytes("RapiDrop-v1-Session-Key|").Concat(transcriptHash).ToArray();
        byte[] sessionKey = HKDF.Expand(HashAlgorithmName.SHA256, prk, 32, sessInfo);

        byte[] authInfo = Encoding.UTF8.GetBytes("RapiDrop-v1-Auth-Token|").Concat(transcriptHash).ToArray();
        byte[] authKey = HKDF.Expand(HashAlgorithmName.SHA256, prk, 32, authInfo);

        byte[] pairInfo = Encoding.UTF8.GetBytes("RapiDrop-v1-Pair-Record|").Concat(transcriptHash).ToArray();
        byte[] pairRecordKey = HKDF.Expand(HashAlgorithmName.SHA256, prk, 32, pairInfo);

        uint val32 = ((uint)authKey[0] << 24) |
                     ((uint)authKey[1] << 16) |
                     ((uint)authKey[2] << 8) |
                     (uint)authKey[3];
        uint sasNum = val32 % 1_000_000;
        string sasCode = sasNum.ToString("D6");

        return new HandshakeKeys(sessionKey, authKey, pairRecordKey, sasCode, transcriptHash);
    }

    public static (byte[] Ciphertext, byte[] Nonce, byte[] Tag) Encrypt(byte[] payload, byte[] key)
    {
        if (key.Length != KeySize) throw new ArgumentException($"Key must be {KeySize} bytes", nameof(key));

        byte[] nonce = new byte[NonceSize];
        RandomNumberGenerator.Fill(nonce);

        byte[] ciphertext = new byte[payload.Length];
        byte[] tag = new byte[TagSize];

        using var aesGcm = new AesGcm(key, TagSize);
        aesGcm.Encrypt(nonce, payload, ciphertext, tag);

        return (ciphertext, nonce, tag);
    }

    public static byte[] Decrypt(byte[] ciphertext, byte[] nonce, byte[] tag, byte[] key)
    {
        if (key.Length != KeySize) throw new ArgumentException($"Key must be {KeySize} bytes", nameof(key));
        if (nonce.Length != NonceSize) throw new ArgumentException($"Nonce must be {NonceSize} bytes", nameof(nonce));
        if (tag.Length != TagSize) throw new ArgumentException($"Tag must be {TagSize} bytes", nameof(tag));

        byte[] plaintext = new byte[ciphertext.Length];

        using var aesGcm = new AesGcm(key, TagSize);
        aesGcm.Decrypt(nonce, ciphertext, tag, plaintext);

        return plaintext;
    }

    public static bool TryDecrypt(byte[] ciphertext, byte[] nonce, byte[] tag, byte[] key, out byte[]? plaintext)
    {
        try
        {
            plaintext = Decrypt(ciphertext, nonce, tag, key);
            return true;
        }
        catch
        {
            plaintext = null;
            return false;
        }
    }
}
