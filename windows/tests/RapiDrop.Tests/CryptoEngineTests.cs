using System.Text;
using RapiDrop.Core.Security;
using Xunit;

namespace RapiDrop.Tests;

public sealed class CryptoEngineTests
{
    [Fact]
    public void TestPinKeyDerivation()
    {
        string pin = "849201";
        byte[] key1 = CryptoEngine.DeriveKeyFromPin(pin);
        byte[] key2 = CryptoEngine.DeriveKeyFromPin(" 849201 ");

        Assert.Equal(32, key1.Length);
        Assert.Equal(key1, key2);
        Assert.Equal("55bfd0250ff5541b66dd32f35a0cac4cd0e400606632d4830c70fcff60833d1c", Convert.ToHexString(key1).ToLowerInvariant());
    }

    [Fact]
    public void TestEncryptDecryptRoundTrip()
    {
        byte[] key = CryptoEngine.DeriveKeyFromPin("123456");
        byte[] plaintext = Encoding.UTF8.GetBytes("Super secret synced text over local network");

        var (ciphertext, nonce, tag) = CryptoEngine.Encrypt(plaintext, key);

        Assert.Equal(plaintext.Length, ciphertext.Length);
        Assert.Equal(12, nonce.Length);
        Assert.Equal(16, tag.Length);

        byte[] decrypted = CryptoEngine.Decrypt(ciphertext, nonce, tag, key);
        Assert.Equal(plaintext, decrypted);
    }

    [Fact]
    public void TestTryDecryptWithInvalidKeyFailsGracefully()
    {
        byte[] correctKey = CryptoEngine.DeriveKeyFromPin("111111");
        byte[] wrongKey = CryptoEngine.DeriveKeyFromPin("222222");

        byte[] plaintext = "Confidential data"u8.ToArray();
        var (ciphertext, nonce, tag) = CryptoEngine.Encrypt(plaintext, correctKey);

        bool success = CryptoEngine.TryDecrypt(ciphertext, nonce, tag, wrongKey, out byte[]? result);
        Assert.False(success);
        Assert.Null(result);
    }

    [Fact]
    public void TestCrossPlatformX25519AndHkdfCanonicalVector()
    {
        byte[] alicePriv = CryptoEngine.HexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")!;
        string bobPubHex = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f";

        byte[] sharedSecret = CryptoEngine.ComputeSharedSecret(alicePriv, bobPubHex);
        Assert.Equal("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", Convert.ToHexString(sharedSecret).ToLowerInvariant());

        byte[] nonceA = CryptoEngine.HexToBytes("000102030405060708090a0b0c0d0e0f")!;
        byte[] nonceB = CryptoEngine.HexToBytes("101112131415161718191a1b1c1d1e1f")!;
        string idA = "device-uuid-alice";
        string idB = "device-uuid-bob";
        byte[] alicePub = Curve25519.ComputePublicKey(alicePriv);
        byte[] bobPub = CryptoEngine.HexToBytes(bobPubHex)!;

        var keys = CryptoEngine.DeriveHandshakeKeys(
            sharedSecret,
            alicePub,
            bobPub,
            nonceA,
            nonceB,
            idA,
            idB
        );

        Assert.Equal("2cd9946128df4a5cb0f1a1786e31136a0b7ea489c8f913552b605f6c83638c00", Convert.ToHexString(keys.TranscriptHash).ToLowerInvariant());
        Assert.Equal("c01bcb745e0644d944458b953fe8363beb4b8c7271aadc92f217e45b360b5d17", Convert.ToHexString(keys.SessionKey).ToLowerInvariant());
        Assert.Equal("ffe30b48f5f241bfc48233070dc246222312376d9630f1585ef418af6945056a", Convert.ToHexString(keys.AuthKey).ToLowerInvariant());
        Assert.Equal("ba2c59676e4619a4e6ab0ef71d9a2d414815292b97508c926516c5b0a9427838", Convert.ToHexString(keys.PairRecordKey).ToLowerInvariant());
        Assert.Equal("069640", keys.SasCode);
    }

    [Fact]
    public void TestMismatchedTranscriptFailsSAS()
    {
        byte[] alicePriv = CryptoEngine.HexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")!;
        string bobPubHex = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f";
        byte[] sharedSecret = CryptoEngine.ComputeSharedSecret(alicePriv, bobPubHex);

        byte[] nonceA = CryptoEngine.HexToBytes("000102030405060708090a0b0c0d0e0f")!;
        byte[] nonceB = CryptoEngine.HexToBytes("101112131415161718191a1b1c1d1e1f")!;
        byte[] alicePub = Curve25519.ComputePublicKey(alicePriv);
        byte[] bobPub = CryptoEngine.HexToBytes(bobPubHex)!;

        var keysOriginal = CryptoEngine.DeriveHandshakeKeys(
            sharedSecret,
            alicePub,
            bobPub,
            nonceA,
            nonceB,
            "device-uuid-alice",
            "device-uuid-bob"
        );

        var keysTampered = CryptoEngine.DeriveHandshakeKeys(
            sharedSecret,
            alicePub,
            bobPub,
            nonceA,
            nonceB,
            "device-uuid-attacker",
            "device-uuid-bob"
        );

        Assert.NotEqual(keysOriginal.SasCode, keysTampered.SasCode);
        Assert.NotEqual(keysOriginal.SessionKey, keysTampered.SessionKey);
    }

    [Fact]
    public void TestEndToEndPairingHandshakeAndAuthenticatedSession()
    {
        var (privA, pubAHex) = CryptoEngine.GenerateEphemeralKeypair();
        byte[] nonceA = CryptoEngine.GenerateNonce(16);
        string idA = "windows-initiator-id";

        var (privB, pubBHex) = CryptoEngine.GenerateEphemeralKeypair();
        byte[] nonceB = CryptoEngine.GenerateNonce(16);
        string idB = "mac-responder-id";

        byte[] pubABytes = CryptoEngine.HexToBytes(pubAHex)!;
        byte[] pubBBytes = CryptoEngine.HexToBytes(pubBHex)!;

        byte[] secretB = CryptoEngine.ComputeSharedSecret(privB, pubAHex);
        var keysB = CryptoEngine.DeriveHandshakeKeys(
            secretB,
            pubABytes,
            pubBBytes,
            nonceA,
            nonceB,
            idA,
            idB
        );

        byte[] secretA = CryptoEngine.ComputeSharedSecret(privA, pubBHex);
        var keysA = CryptoEngine.DeriveHandshakeKeys(
            secretA,
            pubABytes,
            pubBBytes,
            nonceA,
            nonceB,
            idA,
            idB
        );

        Assert.Equal(keysA.SasCode, keysB.SasCode);
        Assert.Equal(keysA.SessionKey, keysB.SessionKey);

        byte[] reqPayload = Encoding.UTF8.GetBytes("{\"deviceName\":\"Windows PC\",\"modelId\":\"PC\"}");
        var (encCipher, encNonce, encTag) = CryptoEngine.Encrypt(reqPayload, keysA.SessionKey);
        byte[] decryptedReq = CryptoEngine.Decrypt(encCipher, encNonce, encTag, keysB.SessionKey);
        Assert.Equal(Encoding.UTF8.GetString(reqPayload), Encoding.UTF8.GetString(decryptedReq));

        byte[] confirmPayload = Encoding.UTF8.GetBytes("OK");
        var (confCipher, confNonce, confTag) = CryptoEngine.Encrypt(confirmPayload, keysB.SessionKey);
        byte[] decryptedConfirm = CryptoEngine.Decrypt(confCipher, confNonce, confTag, keysA.SessionKey);
        Assert.Equal("OK", Encoding.UTF8.GetString(decryptedConfirm));
    }
}
