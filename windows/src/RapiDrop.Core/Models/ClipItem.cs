namespace RapiDrop.Core.Models;

public sealed class ClipItem : IEquatable<ClipItem>
{
    public Guid Id { get; }
    public ClipContentType Type { get; }
    public string? TextContent { get; }
    public byte[]? RawData { get; }
    public string? FileName { get; }
    public long Timestamp { get; }

    public ClipItem(
        ClipContentType type,
        string? textContent = null,
        byte[]? rawData = null,
        string? fileName = null,
        Guid? id = null,
        long? timestamp = null)
    {
        Id = id ?? Guid.NewGuid();
        Type = type;
        TextContent = textContent;
        RawData = rawData;
        FileName = fileName;
        Timestamp = timestamp ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    }

    public string PreviewText => Type switch
    {
        ClipContentType.Text => TextContent?.Trim() ?? string.Empty,
        ClipContentType.Url => TextContent?.Trim() ?? string.Empty,
        ClipContentType.Image => !string.IsNullOrEmpty(FileName) ? FileName : "Image",
        ClipContentType.File => !string.IsNullOrEmpty(FileName) ? FileName : "File",
        _ => string.Empty
    };

    public bool Equals(ClipItem? other)
    {
        if (other is null) return false;
        if (ReferenceEquals(this, other)) return true;

        if (Type != other.Type) return false;

        if (TextContent != null && other.TextContent != null)
        {
            return string.Equals(TextContent, other.TextContent, StringComparison.Ordinal);
        }

        if (RawData != null && other.RawData != null)
        {
            return RawData.AsSpan().SequenceEqual(other.RawData);
        }

        return Id == other.Id;
    }

    public override bool Equals(object? obj) => Equals(obj as ClipItem);

    public override int GetHashCode()
    {
        if (TextContent != null) return HashCode.Combine(Type, TextContent);
        if (RawData != null) return HashCode.Combine(Type, RawData.Length);
        return HashCode.Combine(Type, Id);
    }
}
