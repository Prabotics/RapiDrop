using System.Buffers.Binary;

namespace RapiDrop.Core.Network;

public sealed class WireFrame
{
    public const int DefaultPort = 58240;
    public const int DefaultClientPort = 58241;
    public const string ServiceType = "_clipsync._tcp";
    public const string ClientServiceType = "_clipsync-cli._tcp";

    public const ushort ProtocolVersion = 0x0001;
    public const int HeaderSize = 28;
    public const int MaxPayloadSize = 104_857_600;
    public const int AuthTagSize = 16;
    public const int NonceSize = 12;
    public const int StreamingChunkSize = 1_048_576;
    public const long InactivityTimeoutMs = 16000L;
    public const long WatchdogIntervalMs = 5000L;
    public const long HeartbeatIntervalMs = 4000L;
    public const long PairingInviteTimeoutMs = 30000L;
    public const int MaxInFlightChunks = 4;
    public const int ChunkHeaderSize = 28;

    public PacketType Type { get; }
    public ulong Timestamp { get; }
    public byte[] Nonce { get; }
    public byte[] Ciphertext { get; }
    public byte[] Tag { get; }

    public WireFrame(
        PacketType type,
        byte[] nonce,
        byte[] ciphertext,
        byte[] tag,
        ulong? timestamp = null)
    {
        Type = type;
        Nonce = nonce ?? throw new ArgumentNullException(nameof(nonce));
        Ciphertext = ciphertext ?? throw new ArgumentNullException(nameof(ciphertext));
        Tag = tag ?? throw new ArgumentNullException(nameof(tag));
        Timestamp = timestamp ?? (ulong)DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    }

    public byte[] Serialize()
    {
        uint payloadLength = (uint)(Ciphertext.Length + Tag.Length);
        int totalLength = HeaderSize + (int)payloadLength;
        byte[] buffer = new byte[totalLength];
        Span<byte> span = buffer.AsSpan();

        BinaryPrimitives.WriteUInt32BigEndian(span.Slice(0, 4), payloadLength);
        BinaryPrimitives.WriteUInt16BigEndian(span.Slice(4, 2), (ushort)Type);
        BinaryPrimitives.WriteUInt16BigEndian(span.Slice(6, 2), ProtocolVersion);
        BinaryPrimitives.WriteUInt64BigEndian(span.Slice(8, 8), Timestamp);

        Nonce.AsSpan().CopyTo(span.Slice(16, NonceSize));
        Ciphertext.AsSpan().CopyTo(span.Slice(HeaderSize, Ciphertext.Length));
        Tag.AsSpan().CopyTo(span.Slice(HeaderSize + Ciphertext.Length, Tag.Length));

        return buffer;
    }

    public static WireFrame? Deserialize(ReadOnlySpan<byte> buffer)
    {
        if (buffer.Length < HeaderSize + AuthTagSize)
            return null;

        uint payloadLength = BinaryPrimitives.ReadUInt32BigEndian(buffer.Slice(0, 4));
        if (payloadLength > MaxPayloadSize)
            return null;

        if (buffer.Length < HeaderSize + (int)payloadLength)
            return null;

        ushort rawType = BinaryPrimitives.ReadUInt16BigEndian(buffer.Slice(4, 2));
        if (!Enum.IsDefined(typeof(PacketType), rawType))
            return null;

        ushort version = BinaryPrimitives.ReadUInt16BigEndian(buffer.Slice(6, 2));
        if (version != ProtocolVersion)
            return null;

        ulong timestamp = BinaryPrimitives.ReadUInt64BigEndian(buffer.Slice(8, 8));
        byte[] nonce = buffer.Slice(16, NonceSize).ToArray();

        int ciphertextLen = (int)payloadLength - AuthTagSize;
        if (ciphertextLen < 0)
            return null;

        byte[] ciphertext = buffer.Slice(HeaderSize, ciphertextLen).ToArray();
        byte[] tag = buffer.Slice(HeaderSize + ciphertextLen, AuthTagSize).ToArray();

        return new WireFrame((PacketType)rawType, nonce, ciphertext, tag, timestamp);
    }
}
