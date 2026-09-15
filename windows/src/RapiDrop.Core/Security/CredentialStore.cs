using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace RapiDrop.Core.Security;

public sealed class AppConfig
{
    public string? PairedPeerName { get; set; }
    public string? PairedPeerId { get; set; }
    public string? PairedPeerHost { get; set; }
    public int PairedPeerPort { get; set; }
    public string? PairingPin { get; set; }
    public string? PairedSessionKeyHex { get; set; }
    public string DeviceId { get; set; } = Guid.NewGuid().ToString();
    public string MediaDestination { get; set; } = "both";
    public string? CustomMediaFolder { get; set; }
    public string Theme { get; set; } = "system";
    public bool ShowSyncHistory { get; set; } = true;
    public List<string> PinnedClipIds { get; set; } = new();
    public bool HasShownWelcomeNotification { get; set; }
}

public sealed class CredentialStore
{
    private const string AppDirName = "RapiDrop";
    private const string ConfigFileName = "config.dat";
    private readonly string _configFilePath;
    private readonly object _lock = new();

    public AppConfig Config { get; private set; }

    public string MediaFolderPath
    {
        get
        {
            if (!string.IsNullOrEmpty(Config.CustomMediaFolder) && Directory.Exists(Config.CustomMediaFolder))
            {
                return Config.CustomMediaFolder;
            }
            string downloads = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "RapiDrop");
            return downloads;
        }
    }

    public CredentialStore(string? customPath = null)
    {
        if (!string.IsNullOrEmpty(customPath))
        {
            _configFilePath = customPath;
        }
        else
        {
            string appData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string dir = Path.Combine(appData, AppDirName);
            Directory.CreateDirectory(dir);
            _configFilePath = Path.Combine(dir, ConfigFileName);
        }

        Config = Load();
    }

    public void Save(AppConfig config)
    {
        lock (_lock)
        {
            Config = config;
            string json = JsonSerializer.Serialize(config);
            byte[] plainBytes = Encoding.UTF8.GetBytes(json);

            byte[] encryptedBytes;
            if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
            {
                encryptedBytes = ProtectedData.Protect(plainBytes, null, DataProtectionScope.CurrentUser);
            }
            else
            {
                encryptedBytes = plainBytes;
            }

            File.WriteAllBytes(_configFilePath, encryptedBytes);
        }
    }

    public void ClearPairing()
    {
        lock (_lock)
        {
            Config.PairedPeerName = null;
            Config.PairedPeerId = null;
            Config.PairedPeerHost = null;
            Config.PairedPeerPort = 0;
            Config.PairingPin = null;
            Config.PairedSessionKeyHex = null;
            Save(Config);
        }
    }

    private AppConfig Load()
    {
        lock (_lock)
        {
            if (!File.Exists(_configFilePath))
                return new AppConfig();

            try
            {
                byte[] encryptedBytes = File.ReadAllBytes(_configFilePath);
                byte[] plainBytes;

                if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
                {
                    plainBytes = ProtectedData.Unprotect(encryptedBytes, null, DataProtectionScope.CurrentUser);
                }
                else
                {
                    plainBytes = encryptedBytes;
                }

                string json = Encoding.UTF8.GetString(plainBytes);
                return JsonSerializer.Deserialize<AppConfig>(json) ?? new AppConfig();
            }
            catch
            {
                return new AppConfig();
            }
        }
    }
}
