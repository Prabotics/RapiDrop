namespace RapiDrop.Core.Models;

public sealed class DiscoveredDevice : IEquatable<DiscoveredDevice>
{
    public string Id { get; set; } = "";
    public string Name { get; set; }
    public string Host { get; set; }
    public int Port { get; set; }
    public string DeviceType { get; set; } = "unknown";
    public string? Model { get; set; }
    public string? Chip { get; set; }
    public DateTime LastSeen { get; set; } = DateTime.UtcNow;

    public DiscoveredDevice(string name = "", string host = "", int port = 0)
    {
        Name = name;
        Host = host;
        Port = port;
    }

    public bool Equals(DiscoveredDevice? other)
    {
        if (other is null) return false;
        if (ReferenceEquals(this, other)) return true;
        return string.Equals(Name, other.Name, StringComparison.OrdinalIgnoreCase) &&
               string.Equals(Host, other.Host, StringComparison.OrdinalIgnoreCase) &&
               Port == other.Port;
    }

    public override bool Equals(object? obj) => Equals(obj as DiscoveredDevice);

    public override int GetHashCode() =>
        HashCode.Combine(
            StringComparer.OrdinalIgnoreCase.GetHashCode(Name),
            StringComparer.OrdinalIgnoreCase.GetHashCode(Host),
            Port);
}
