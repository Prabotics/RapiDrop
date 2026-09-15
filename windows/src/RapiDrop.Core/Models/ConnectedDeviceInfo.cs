using System.Text.Json.Serialization;

namespace RapiDrop.Core.Models;

public sealed class ConnectedDeviceInfo
{
    [JsonPropertyName("modelId")]
    public string ModelId { get; init; } = "PC";

    [JsonPropertyName("modelName")]
    public string ModelName { get; init; } = "Windows PC";

    [JsonPropertyName("chip")]
    public string Chip { get; init; } = "x86_64";

    [JsonPropertyName("deviceName")]
    public string DeviceName { get; init; } = Environment.MachineName;

    public static ConnectedDeviceInfo Current()
    {
        string arch = System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture.ToString();
        return new ConnectedDeviceInfo
        {
            ModelId = "PC",
            ModelName = "Windows PC",
            Chip = arch,
            DeviceName = Environment.MachineName
        };
    }
}
