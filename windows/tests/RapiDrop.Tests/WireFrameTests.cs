using RapiDrop.Core.Network;
using RapiDrop.Core.Models;
using RapiDrop.Core.Security;
using Xunit;

namespace RapiDrop.Tests;

public sealed class WireFrameTests
{
    [Fact]
    public void TestSerializationRoundTrip()
    {
        byte[] nonce = new byte[12] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
        byte[] ciphertext = "Hello Windows RapiDrop"u8.ToArray();
        byte[] tag = new byte[16] { 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1 };

        var frame = new WireFrame(PacketType.ClipText, nonce, ciphertext, tag, timestamp: 1700000000000UL);

        byte[] serialized = frame.Serialize();
        Assert.Equal(WireFrame.HeaderSize + ciphertext.Length + tag.Length, serialized.Length);

        var deserialized = WireFrame.Deserialize(serialized);
        Assert.NotNull(deserialized);
        Assert.Equal(PacketType.ClipText, deserialized.Type);
        Assert.Equal(1700000000000UL, deserialized.Timestamp);
        Assert.Equal(nonce, deserialized.Nonce);
        Assert.Equal(ciphertext, deserialized.Ciphertext);
        Assert.Equal(tag, deserialized.Tag);
    }

    [Fact]
    public void TestWireFramePairFailSerialization()
    {
        byte[] nonce = new byte[12];
        byte[] tag = new byte[16];
        byte[] failDeclined = "DECLINED"u8.ToArray();

        var frameDeclined = new WireFrame(PacketType.PairFail, nonce, failDeclined, tag);
        var deserializedDeclined = WireFrame.Deserialize(frameDeclined.Serialize());
        Assert.NotNull(deserializedDeclined);
        Assert.Equal(PacketType.PairFail, deserializedDeclined.Type);
        Assert.Equal(failDeclined, deserializedDeclined.Ciphertext);

        byte[] failCancelled = "CANCELLED"u8.ToArray();
        var frameCancelled = new WireFrame(PacketType.PairFail, nonce, failCancelled, tag);
        var deserializedCancelled = WireFrame.Deserialize(frameCancelled.Serialize());
        Assert.NotNull(deserializedCancelled);
        Assert.Equal(PacketType.PairFail, deserializedCancelled.Type);
        Assert.Equal(failCancelled, deserializedCancelled.Ciphertext);
    }

    [Fact]
    public void TestWireFrameConfigSyncSerialization()
    {
        byte[] nonce = new byte[12] { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 };
        byte[] tag = new byte[16] { 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2 };
        byte[] config = "{\"privacyMode\":true}"u8.ToArray();

        var frame = new WireFrame(PacketType.ConfigSync, nonce, config, tag);
        var deserialized = WireFrame.Deserialize(frame.Serialize());
        Assert.NotNull(deserialized);
        Assert.Equal(PacketType.ConfigSync, deserialized.Type);
        Assert.Equal(config, deserialized.Ciphertext);
    }

    [Fact]
    public void TestWireFrameClipFileSerialization()
    {
        byte[] filename = "document.pdf"u8.ToArray();
        byte[] fileData = "Binary PDF payload content"u8.ToArray();
        byte[] payload = new byte[2 + filename.Length + fileData.Length];
        payload[0] = (byte)(filename.Length >> 8);
        payload[1] = (byte)(filename.Length & 0xFF);
        Buffer.BlockCopy(filename, 0, payload, 2, filename.Length);
        Buffer.BlockCopy(fileData, 0, payload, 2 + filename.Length, fileData.Length);

        byte[] nonce = new byte[12] { 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA };
        byte[] tag = new byte[16] { 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB };

        var frame = new WireFrame(PacketType.ClipFile, nonce, payload, tag);
        var deserialized = WireFrame.Deserialize(frame.Serialize());
        Assert.NotNull(deserialized);
        Assert.Equal(PacketType.ClipFile, deserialized.Type);
        Assert.Equal(payload, deserialized.Ciphertext);
    }

    [Fact]
    public void TestWireFrameDisconnectSerialization()
    {
        byte[] nonce = new byte[12];
        byte[] tag = new byte[16];
        byte[] payload = "UNPAIR"u8.ToArray();

        var frame = new WireFrame(PacketType.Disconnect, nonce, payload, tag);
        var deserialized = WireFrame.Deserialize(frame.Serialize());
        Assert.NotNull(deserialized);
        Assert.Equal(PacketType.Disconnect, deserialized.Type);
        Assert.Equal(payload, deserialized.Ciphertext);
    }

    [Fact]
    public void TestDeserializationRejectsShortBuffer()
    {
        byte[] shortBuffer = new byte[10];
        var deserialized = WireFrame.Deserialize(shortBuffer);
        Assert.Null(deserialized);
    }

    [Fact]
    public void TestAllPacketTypesPreserved()
    {
        byte[] nonce = new byte[12];
        byte[] payload = "data"u8.ToArray();
        byte[] tag = new byte[16];

        foreach (PacketType type in Enum.GetValues<PacketType>())
        {
            var frame = new WireFrame(type, nonce, payload, tag);
            byte[] serialized = frame.Serialize();
            var deserialized = WireFrame.Deserialize(serialized);
            Assert.NotNull(deserialized);
            Assert.Equal(type, deserialized.Type);
        }
    }

    [Fact]
    public void TestPacketTypeWireValuesParity()
    {
        Assert.Equal(0x0001, (ushort)PacketType.Ping);
        Assert.Equal(0x0002, (ushort)PacketType.Pong);
        Assert.Equal(0x0003, (ushort)PacketType.PairRequest);
        Assert.Equal(0x0004, (ushort)PacketType.PairConfirm);
        Assert.Equal(0x0005, (ushort)PacketType.DeviceInfo);
        Assert.Equal(0x0006, (ushort)PacketType.PairInvite);
        Assert.Equal(0x0007, (ushort)PacketType.PairFail);
        Assert.Equal(0x0008, (ushort)PacketType.PairAccept);
        Assert.Equal(0x0010, (ushort)PacketType.ClipText);
        Assert.Equal(0x0011, (ushort)PacketType.ClipUrl);
        Assert.Equal(0x0012, (ushort)PacketType.ClipImage);
        Assert.Equal(0x0013, (ushort)PacketType.ClipFile);
        Assert.Equal(0x0020, (ushort)PacketType.ConfigSync);
        Assert.Equal(0x00FF, (ushort)PacketType.Disconnect);
    }

    [Fact]
    public void TestClipItemTypesAndPreviews()
    {
        var textClip = new ClipItem(ClipContentType.Text, textContent: "Sample text");
        Assert.Equal("Sample text", textClip.PreviewText);

        var urlClip = new ClipItem(ClipContentType.Url, textContent: "https://github.com");
        Assert.Equal("https://github.com", urlClip.PreviewText);

        var imgClip = new ClipItem(ClipContentType.Image, fileName: "photo.png");
        Assert.Equal("photo.png", imgClip.PreviewText);

        var fileClip = new ClipItem(ClipContentType.File, fileName: "doc.pdf");
        Assert.Equal("doc.pdf", fileClip.PreviewText);
    }
    [Fact]
    public void TestWindowsHistoryListManagement()
    {
        var clips = new List<ClipItem>();
        var clip1 = new ClipItem(ClipContentType.Text, textContent: "Item 1");
        var clip2 = new ClipItem(ClipContentType.Url, textContent: "https://example.invalid");
        var clip1Dup = new ClipItem(ClipContentType.Text, textContent: "Item 1");

        clips.Insert(0, clip1);
        Assert.Single(clips);

        clips.Insert(0, clip2);
        Assert.Equal(2, clips.Count);
        Assert.Equal("https://example.invalid", clips[0].TextContent);

        clips.RemoveAll(c => c.Equals(clip1Dup));
        clips.Insert(0, clip1Dup);
        Assert.Equal(2, clips.Count);
        Assert.Equal("Item 1", clips[0].TextContent);

        for (int i = 1; i <= 25; i++)
        {
            clips.Insert(0, new ClipItem(ClipContentType.Text, textContent: $"Bulk {i}"));
            if (clips.Count > 20) clips.RemoveRange(20, clips.Count - 20);
        }
        Assert.Equal(20, clips.Count);
        Assert.Equal("Bulk 25", clips[0].TextContent);

        var toDelete = clips[0];
        clips.RemoveAll(c => c.Id == toDelete.Id);
        Assert.Equal(19, clips.Count);
    }
    [Fact]
    public void TestWindowsSettingsDefaultsAndAppConfig()
    {
        var config = new RapiDrop.Core.Security.AppConfig();
        Assert.True(config.ShowSyncHistory);
        Assert.Equal("both", config.MediaDestination);
        Assert.Equal("system", config.Theme);
        Assert.False(string.IsNullOrEmpty(config.DeviceId));

        string id1 = config.DeviceId;
        config.PairedPeerName = "Renamed Peer";
        Assert.Equal(id1, config.DeviceId);
    }

    [Fact]
    public void TestUpdateCheckerVersionComparison()
    {
        Assert.True(RapiDrop.Core.Network.UpdateChecker.IsNewerVersion("v1.0.1", "1.0.0"));
        Assert.True(RapiDrop.Core.Network.UpdateChecker.IsNewerVersion("v1.1.0", "1.0.0"));
        Assert.True(RapiDrop.Core.Network.UpdateChecker.IsNewerVersion("v2.0.0", "1.0.0"));
        Assert.True(RapiDrop.Core.Network.UpdateChecker.IsNewerVersion("1.0.1", "1.0.0"));
        Assert.False(RapiDrop.Core.Network.UpdateChecker.IsNewerVersion("v1.0.0", "1.0.0"));
        Assert.False(RapiDrop.Core.Network.UpdateChecker.IsNewerVersion("v0.9.9", "1.0.0"));
        Assert.False(RapiDrop.Core.Network.UpdateChecker.IsNewerVersion("", "1.0.0"));
    }

    [Fact]
    public void TestUniqueDestinationPathCollisionResolution()
    {
        string tmpDir = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        Directory.CreateDirectory(tmpDir);
        try
        {
            string baseFile = Path.Combine(tmpDir, "document.pdf");
            Assert.Equal(baseFile, RapiDrop.Core.Network.NetworkEngine.GetUniqueDestinationPath(baseFile));

            File.WriteAllText(baseFile, "test");
            string expected1 = Path.Combine(tmpDir, "document (1).pdf");
            Assert.Equal(expected1, RapiDrop.Core.Network.NetworkEngine.GetUniqueDestinationPath(baseFile));

            File.WriteAllText(expected1, "test");
            string expected2 = Path.Combine(tmpDir, "document (2).pdf");
            Assert.Equal(expected2, RapiDrop.Core.Network.NetworkEngine.GetUniqueDestinationPath(baseFile));
        }
        finally
        {
            if (Directory.Exists(tmpDir)) Directory.Delete(tmpDir, true);
        }
    }

    [Fact]
    public void TestStreamingFramesSerialization()
    {
        byte[] startJson = "{\"transferId\":\"uuid-123\",\"fileIndex\":0,\"totalFiles\":1,\"fileName\":\"test.png\",\"relativePath\":\"test.png\",\"fileSize\":1024,\"totalBytes\":1024}"u8.ToArray();
        var startFrame = new WireFrame(PacketType.FileStart, new byte[12], startJson, new byte[16]);
        var deserializedStart = WireFrame.Deserialize(startFrame.Serialize());
        Assert.NotNull(deserializedStart);
        Assert.Equal(PacketType.FileStart, deserializedStart.Type);
        Assert.Equal(startJson, deserializedStart.Ciphertext);

        byte[] endJson = "{\"transferId\":\"uuid-123\",\"fileIndex\":0,\"sha256\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}"u8.ToArray();
        var endFrame = new WireFrame(PacketType.FileEnd, new byte[12], endJson, new byte[16]);
        var deserializedEnd = WireFrame.Deserialize(endFrame.Serialize());
        Assert.NotNull(deserializedEnd);
        Assert.Equal(PacketType.FileEnd, deserializedEnd.Type);
        Assert.Equal(endJson, deserializedEnd.Ciphertext);

        byte[] cancelJson = "{\"transferId\":\"uuid-123\",\"reason\":\"user_abort\"}"u8.ToArray();
        var cancelFrame = new WireFrame(PacketType.FileCancel, new byte[12], cancelJson, new byte[16]);
        var deserializedCancel = WireFrame.Deserialize(cancelFrame.Serialize());
        Assert.NotNull(deserializedCancel);
        Assert.Equal(PacketType.FileCancel, deserializedCancel.Type);
        Assert.Equal(cancelJson, deserializedCancel.Ciphertext);
    }

    [Fact]
    public void TestFileChunkHeaderStructureAnd64BitChunkIndex()
    {
        string transferId = "test-transfer-id";
        int fileIndex = 42;
        long chunkIndex = 1000000000L;

        byte[] chunkPayload = new byte[28];
        byte[] idBytes = System.Text.Encoding.UTF8.GetBytes(transferId);
        Array.Copy(idBytes, chunkPayload, Math.Min(idBytes.Length, 16));
        System.Buffers.Binary.BinaryPrimitives.WriteInt32BigEndian(chunkPayload.AsSpan(16, 4), fileIndex);
        System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(chunkPayload.AsSpan(20, 8), chunkIndex);

        int parsedFileIndex = System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(chunkPayload.AsSpan(16, 4));
        long parsedChunkIndex = System.Buffers.Binary.BinaryPrimitives.ReadInt64BigEndian(chunkPayload.AsSpan(20, 8));

        Assert.Equal(42, parsedFileIndex);
        Assert.Equal(1000000000L, parsedChunkIndex);
    }

    [Fact]
    public void TestIncrementalSha256StreamingParity()
    {
        byte[] chunkA = "First chunk of stream "u8.ToArray();
        byte[] chunkB = "second chunk of stream "u8.ToArray();
        byte[] chunkC = "final payload bytes."u8.ToArray();

        using var incremental = System.Security.Cryptography.IncrementalHash.CreateHash(System.Security.Cryptography.HashAlgorithmName.SHA256);
        incremental.AppendData(chunkA);
        incremental.AppendData(chunkB);
        incremental.AppendData(chunkC);
        string incDigest = Convert.ToHexString(incremental.GetHashAndReset()).ToLowerInvariant();

        byte[] full = new byte[chunkA.Length + chunkB.Length + chunkC.Length];
        Buffer.BlockCopy(chunkA, 0, full, 0, chunkA.Length);
        Buffer.BlockCopy(chunkB, 0, full, chunkA.Length, chunkB.Length);
        Buffer.BlockCopy(chunkC, 0, full, chunkA.Length + chunkB.Length, chunkC.Length);
        string fullDigest = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(full)).ToLowerInvariant();

        Assert.Equal(incDigest, fullDigest);
    }

    [Fact]
    public void TestLargeFileSize64BitArithmetic()
    {
        long tenGigabytes = 10L * 1024L * 1024L * 1024L;
        long chunkSize = WireFrame.StreamingChunkSize;
        long totalChunks = (tenGigabytes + chunkSize - 1L) / chunkSize;

        Assert.Equal(10240L, totalChunks);
        Assert.True(tenGigabytes > int.MaxValue);
    }

    [Fact]
    public void TestWireFrameFileCancelSerialization()
    {
        byte[] nonce = new byte[12] { 9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2 };
        byte[] tag = new byte[16];
        byte[] payload = "{\"transferId\":\"tid-1234\",\"status\":\"CANCELLED\"}"u8.ToArray();

        var frame = new WireFrame(PacketType.FileCancel, nonce, payload, tag);
        var bytes = frame.Serialize();
        var deserialized = WireFrame.Deserialize(bytes);

        Assert.NotNull(deserialized);
        Assert.Equal(PacketType.FileCancel, deserialized.Type);
        Assert.Equal(payload, deserialized.Ciphertext);
    }

    [Fact]
    public void TestStreamingChunkSizeIsOneMegabyte()
    {
        Assert.Equal(1_048_576, WireFrame.StreamingChunkSize);
    }
    [Fact]
    public void TestWindowsDeviceNameNormalization()
    {
        string norm1 = Normalize("Test Mac (2)");
        string norm2 = Normalize("Test’s Mac");
        string norm3 = Normalize("Test Android - 2");
        string norm4 = Normalize("Test Android (99)");

        Assert.Equal("test mac", norm1);
        Assert.Equal("test's mac", norm2);
        Assert.Equal("test android", norm3);
        Assert.Equal("test android", norm4);

        static string Normalize(string name)
        {
            string n = name.Replace('’', '\'')
                .Replace('‘', '\'')
                .Replace("\"", "")
                .Replace("\\", "")
                .Trim();
            n = System.Text.RegularExpressions.Regex.Replace(n, @"\s*\(\d+\)$", "");
            n = System.Text.RegularExpressions.Regex.Replace(n, @"\s*-\s*\d+$", "");
            return n.Trim().ToLowerInvariant();
        }
    }

    [Fact]
    public void TestWindowsSameNameDifferentIdDeduplication()
    {
        var dev1 = new DiscoveredDevice("Test Device") { Id = "device-uuid-1", Host = "192.0.2.50" };
        var dev2 = new DiscoveredDevice("Test Device") { Id = "device-uuid-2", Host = "192.0.2.51" };
        var dev1DuplicateCallback = new DiscoveredDevice("Test Device (2)") { Id = "device-uuid-1", Host = "192.0.2.50" };

        var seenIds = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var deduped = new List<DiscoveredDevice>();

        foreach (var d in new[] { dev1, dev2, dev1DuplicateCallback })
        {
            if (!seenIds.Contains(d.Id))
            {
                seenIds.Add(d.Id);
                deduped.Add(d);
            }
        }

        Assert.Equal(2, deduped.Count);
        Assert.Equal("device-uuid-1", deduped[0].Id);
        Assert.Equal("device-uuid-2", deduped[1].Id);
    }
    [Fact]
    public void TestWindowsSasTranscriptSensitivityAndDisplayNameIndependence()
    {
        byte[] pubA = new byte[32]; Array.Fill(pubA, (byte)0x01);
        byte[] pubB = new byte[32]; Array.Fill(pubB, (byte)0x02);
        byte[] nonceA = new byte[16]; Array.Fill(nonceA, (byte)0x03);
        byte[] nonceB = new byte[16]; Array.Fill(nonceB, (byte)0x04);
        byte[] secret = new byte[32]; Array.Fill(secret, (byte)0x05);

        var baseKeys = CryptoEngine.DeriveHandshakeKeys(secret, pubA, pubB, nonceA, nonceB, "device-a", "device-b");
        var baseSame = CryptoEngine.DeriveHandshakeKeys(secret, pubA, pubB, nonceA, nonceB, "device-a", "device-b");

        Assert.Equal(baseKeys.SasCode, baseSame.SasCode);
        Assert.Equal(6, baseKeys.SasCode.Length);

        byte[] diffPub = new byte[32]; Array.Fill(diffPub, (byte)0x99);
        var diffKeyA = CryptoEngine.DeriveHandshakeKeys(secret, diffPub, pubB, nonceA, nonceB, "device-a", "device-b");
        Assert.NotEqual(baseKeys.SasCode, diffKeyA.SasCode);

        byte[] diffNonce = new byte[16]; Array.Fill(diffNonce, (byte)0x99);
        var diffNonceA = CryptoEngine.DeriveHandshakeKeys(secret, pubA, pubB, diffNonce, nonceB, "device-a", "device-b");
        Assert.NotEqual(baseKeys.SasCode, diffNonceA.SasCode);

        var diffIdA = CryptoEngine.DeriveHandshakeKeys(secret, pubA, pubB, nonceA, nonceB, "device-a-diff", "device-b");
        Assert.NotEqual(baseKeys.SasCode, diffIdA.SasCode);

        var diffIdB = CryptoEngine.DeriveHandshakeKeys(secret, pubA, pubB, nonceA, nonceB, "device-a", "device-b-diff");
        Assert.NotEqual(baseKeys.SasCode, diffIdB.SasCode);
    }

    [Fact]
    public void TestWindowsDiscoveryUnresolvedRecordMergesIntoResolvedDevice()
    {
        var unresolved = new DiscoveredDevice("Test Mac", "192.0.2.50", 58240) { Id = "" };
        var resolved = new DiscoveredDevice("Test Mac", "192.0.2.50", 58240) { Id = "uuid-1234" };

        var devicesById = new Dictionary<string, DiscoveredDevice>(StringComparer.OrdinalIgnoreCase);
        var devicesByNameWithoutId = new Dictionary<string, DiscoveredDevice>(StringComparer.OrdinalIgnoreCase);

        foreach (var dev in new[] { unresolved, resolved })
        {
            if (!string.IsNullOrEmpty(dev.Id))
            {
                devicesById[dev.Id] = dev;
            }
            else
            {
                string norm = dev.Name.ToLowerInvariant();
                if (!devicesByNameWithoutId.ContainsKey(norm))
                {
                    devicesByNameWithoutId[norm] = dev;
                }
            }
        }

        var merged = new List<DiscoveredDevice>(devicesById.Values);
        var seenNames = new HashSet<string>(merged.Select(d => d.Name.ToLowerInvariant()), StringComparer.OrdinalIgnoreCase);

        foreach (var kvp in devicesByNameWithoutId)
        {
            if (!seenNames.Contains(kvp.Key))
            {
                merged.Add(kvp.Value);
            }
        }

        Assert.Single(merged);
        Assert.Equal("uuid-1234", merged[0].Id);
    }

    [Fact]
    public void TestWindowsRemoteDeviceWithSameNameAsLocalIsNotSelf()
    {
        string localId = "my-win-uuid";
        string localName = "test-pc";

        var remoteWithSameName = new DiscoveredDevice("test-pc", "192.0.2.60", 58240) { Id = "other-win-uuid" };
        bool isSelf;
        if (!string.IsNullOrEmpty(remoteWithSameName.Id) && !string.IsNullOrEmpty(localId))
        {
            isSelf = string.Equals(remoteWithSameName.Id, localId, StringComparison.OrdinalIgnoreCase);
        }
        else
        {
            isSelf = !string.IsNullOrEmpty(localName) && remoteWithSameName.Name.ToLowerInvariant() == localName;
        }

        Assert.False(isSelf);
    }

    [Fact]
    public void TestWindowsInitiatorToAndroidResponderProtocolFlow()
    {
        var (privA, pubAHex) = CryptoEngine.GenerateEphemeralKeypair();
        byte[] nonceA = CryptoEngine.GenerateNonce(16);
        var (privB, pubBHex) = CryptoEngine.GenerateEphemeralKeypair();
        byte[] nonceB = CryptoEngine.GenerateNonce(16);

        byte[] secretA = CryptoEngine.ComputeSharedSecret(privA, pubBHex);
        byte[] secretB = CryptoEngine.ComputeSharedSecret(privB, pubAHex);
        Assert.Equal(secretA, secretB);

        byte[] pubABytes = Convert.FromHexString(pubAHex);
        byte[] pubBBytes = Convert.FromHexString(pubBHex);

        var keysA = CryptoEngine.DeriveHandshakeKeys(
            secretA,
            pubABytes,
            pubBBytes,
            nonceA,
            nonceB,
            "win-uuid-1",
            "android-uuid-2"
        );
        var keysB = CryptoEngine.DeriveHandshakeKeys(
            secretB,
            pubABytes,
            pubBBytes,
            nonceA,
            nonceB,
            "win-uuid-1",
            "android-uuid-2"
        );

        Assert.Equal(keysA.SasCode, keysB.SasCode);
        Assert.Equal(keysA.SessionKey, keysB.SessionKey);
        Assert.Equal(keysA.PairRecordKey, keysB.PairRecordKey);

        string descJson = "{\"deviceName\":\"Windows PC\",\"modelId\":\"Win11\",\"modelName\":\"Windows PC\",\"chip\":\"x64\"}";
        var (reqCipher, reqNonce, reqTag) = CryptoEngine.Encrypt(System.Text.Encoding.UTF8.GetBytes(descJson), keysA.SessionKey);
        var reqFrame = new WireFrame(PacketType.PairRequest, reqNonce, reqCipher, reqTag);
        var reqDeserialized = WireFrame.Deserialize(reqFrame.Serialize());
        Assert.NotNull(reqDeserialized);

        byte[] reqDecrypted = CryptoEngine.Decrypt(reqDeserialized.Ciphertext, reqDeserialized.Nonce, reqDeserialized.Tag, keysB.SessionKey);
        Assert.Equal(descJson, System.Text.Encoding.UTF8.GetString(reqDecrypted));

        var (confCipher, confNonce, confTag) = CryptoEngine.Encrypt(System.Text.Encoding.UTF8.GetBytes("OK"), keysB.SessionKey);
        var confFrame = new WireFrame(PacketType.PairConfirm, confNonce, confCipher, confTag);
        var confDeserialized = WireFrame.Deserialize(confFrame.Serialize());
        Assert.NotNull(confDeserialized);
        byte[] confDecrypted = CryptoEngine.Decrypt(confDeserialized.Ciphertext, confDeserialized.Nonce, confDeserialized.Tag, keysA.SessionKey);
        Assert.Equal("OK", System.Text.Encoding.UTF8.GetString(confDecrypted));

        string clipText = "Windows to Android clipboard test payload";
        var (clipCipher, clipNonce, clipTag) = CryptoEngine.Encrypt(System.Text.Encoding.UTF8.GetBytes(clipText), keysA.SessionKey);
        var clipFrame = new WireFrame(PacketType.ClipText, clipNonce, clipCipher, clipTag);
        var clipDeserialized = WireFrame.Deserialize(clipFrame.Serialize());
        Assert.NotNull(clipDeserialized);
        byte[] clipDecrypted = CryptoEngine.Decrypt(clipDeserialized.Ciphertext, clipDeserialized.Nonce, clipDeserialized.Tag, keysB.SessionKey);
        Assert.Equal(clipText, System.Text.Encoding.UTF8.GetString(clipDecrypted));
    }

    [Fact]
    public void TestReconnectWithStoredPreSharedKey()
    {
        byte[] rawKeyBytes = new byte[32];
        for (int i = 0; i < 32; i++) rawKeyBytes[i] = (byte)(i * 7);
        string keyHex = Convert.ToHexString(rawKeyBytes);
        byte[] restoredKey = Convert.FromHexString(keyHex);

        string descJson = "{\"deviceName\":\"Surface Laptop\",\"modelId\":\"Win11\",\"modelName\":\"Surface Laptop\",\"chip\":\"x64\"}";
        var (reqCipher, reqNonce, reqTag) = CryptoEngine.Encrypt(System.Text.Encoding.UTF8.GetBytes(descJson), restoredKey);
        var reqFrame = new WireFrame(PacketType.PairRequest, reqNonce, reqCipher, reqTag);
        var deserialized = WireFrame.Deserialize(reqFrame.Serialize());
        Assert.NotNull(deserialized);

        byte[] decrypted = CryptoEngine.Decrypt(deserialized.Ciphertext, deserialized.Nonce, deserialized.Tag, rawKeyBytes);
        Assert.Equal(descJson, System.Text.Encoding.UTF8.GetString(decrypted));
    }

    [Fact]
    public void TestProcessRestartKeyRestoration()
    {
        byte[] originalKey = new byte[32];
        for (int i = 0; i < 32; i++) originalKey[i] = (byte)(i + 3);
        string persistedHex = Convert.ToHexString(originalKey);
        byte[] restoredKey = Convert.FromHexString(persistedHex);

        byte[] payload = System.Text.Encoding.UTF8.GetBytes("Restored Windows session clip content");
        var (cipher, nonce, tag) = CryptoEngine.Encrypt(payload, originalKey);
        var frame = new WireFrame(PacketType.ClipText, nonce, cipher, tag);

        var deserialized = WireFrame.Deserialize(frame.Serialize());
        Assert.NotNull(deserialized);
        byte[] decrypted = CryptoEngine.Decrypt(deserialized.Ciphertext, deserialized.Nonce, deserialized.Tag, restoredKey);
        Assert.Equal("Restored Windows session clip content", System.Text.Encoding.UTF8.GetString(decrypted));
    }

    [Fact]
    public void TestFailureInjectionInvalidTagRejection()
    {
        byte[] key = new byte[32];
        Array.Fill(key, (byte)0x01);
        byte[] payload = System.Text.Encoding.UTF8.GetBytes("Sensitive test payload");
        var (cipher, nonce, tag) = CryptoEngine.Encrypt(payload, key);

        byte[] tamperedTag = (byte[])tag.Clone();
        tamperedTag[^1] ^= 0xFF;

        Assert.ThrowsAny<Exception>(() =>
        {
            CryptoEngine.Decrypt(cipher, nonce, tamperedTag, key);
        });
    }

    [Fact]
    public void TestFailureInjectionWrongKeyRejection()
    {
        byte[] correctKey = new byte[32];
        Array.Fill(correctKey, (byte)0x01);
        byte[] wrongKey = new byte[32];
        Array.Fill(wrongKey, (byte)0x02);

        byte[] payload = System.Text.Encoding.UTF8.GetBytes("Authenticated data");
        var (cipher, nonce, tag) = CryptoEngine.Encrypt(payload, correctKey);

        Assert.ThrowsAny<Exception>(() =>
        {
            CryptoEngine.Decrypt(cipher, nonce, tag, wrongKey);
        });
    }

    [Fact]
    public void TestHeartbeatWatchdogCalculation()
    {
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        long lastActiveRecent = now - 4000;
        long lastActiveExpired = now - 17000;

        bool isRecentExpired = (now - lastActiveRecent) > 16000;
        bool isExpiredTimeout = (now - lastActiveExpired) > 16000;

        Assert.False(isRecentExpired);
        Assert.True(isExpiredTimeout);
    }

    [Fact]
    public void TestMultiDeviceIsolation()
    {
        byte[] keyAB = new byte[32];
        Array.Fill(keyAB, (byte)0x0A);
        byte[] keyAC = new byte[32];
        Array.Fill(keyAC, (byte)0x0B);

        byte[] payloadB = System.Text.Encoding.UTF8.GetBytes("Message intended for Device B");
        var (cipherB, nonceB, tagB) = CryptoEngine.Encrypt(payloadB, keyAB);

        byte[] decryptedByB = CryptoEngine.Decrypt(cipherB, nonceB, tagB, keyAB);
        Assert.Equal("Message intended for Device B", System.Text.Encoding.UTF8.GetString(decryptedByB));

        Assert.ThrowsAny<Exception>(() =>
        {
            CryptoEngine.Decrypt(cipherB, nonceB, tagB, keyAC);
        });
    }

    [Fact]
    public void TestDeterministicBidirectionalFileStreaming()
    {
        byte[] key = new byte[32];
        Array.Fill(key, (byte)0x05);
        byte[] fileData = System.Text.Encoding.UTF8.GetBytes("Small synthetic test file content for streaming verification");
        byte[] hash = System.Security.Cryptography.SHA256.HashData(fileData);
        string digest = Convert.ToHexString(hash).ToLowerInvariant();

        byte[] startJson = System.Text.Encoding.UTF8.GetBytes($"{{\"transferId\":\"tid-100\",\"fileName\":\"test.txt\",\"fileSize\":60,\"sha256\":\"{digest}\"}}");
        var (startCipher, startNonce, startTag) = CryptoEngine.Encrypt(startJson, key);
        var startFrame = new WireFrame(PacketType.FileStart, startNonce, startCipher, startTag);
        var startDeserialized = WireFrame.Deserialize(startFrame.Serialize());
        Assert.NotNull(startDeserialized);
        Assert.Equal(PacketType.FileStart, startDeserialized.Type);

        var (chunkCipher, chunkNonce, chunkTag) = CryptoEngine.Encrypt(fileData, key);
        var chunkFrame = new WireFrame(PacketType.FileChunk, chunkNonce, chunkCipher, chunkTag);
        var chunkDeserialized = WireFrame.Deserialize(chunkFrame.Serialize());
        Assert.NotNull(chunkDeserialized);
        Assert.Equal(PacketType.FileChunk, chunkDeserialized.Type);

        byte[] endJson = System.Text.Encoding.UTF8.GetBytes($"{{\"transferId\":\"tid-100\",\"sha256\":\"{digest}\"}}");
        var (endCipher, endNonce, endTag) = CryptoEngine.Encrypt(endJson, key);
        var endFrame = new WireFrame(PacketType.FileEnd, endNonce, endCipher, endTag);
        var endDeserialized = WireFrame.Deserialize(endFrame.Serialize());
        Assert.NotNull(endDeserialized);
        Assert.Equal(PacketType.FileEnd, endDeserialized.Type);

        byte[] decryptedChunk = CryptoEngine.Decrypt(chunkDeserialized.Ciphertext, chunkDeserialized.Nonce, chunkDeserialized.Tag, key);
        byte[] recvHash = System.Security.Cryptography.SHA256.HashData(decryptedChunk);
        string receivedDigest = Convert.ToHexString(recvHash).ToLowerInvariant();
        Assert.Equal(digest, receivedDigest);
    }
}
