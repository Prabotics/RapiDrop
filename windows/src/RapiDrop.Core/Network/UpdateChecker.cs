using System.Net;
using System.Text.Json;

namespace RapiDrop.Core.Network;

public record UpdateCheckResult(
    bool IsUpdateAvailable,
    string LatestVersion,
    string ReleaseNotes,
    string? AssetUrl,
    long AssetSize,
    string ReleaseUrl,
    string? ErrorMessage,
    string? NewEtag
);

public static class UpdateChecker
{
    public const string CurrentVersion = "1.0.0";
    private const string GitHubReleasesUrl = "https://api.github.com/repos/Prabotics/RapiDrop/releases/latest";

    public static async Task<UpdateCheckResult> CheckForUpdatesAsync(string? storedEtag = null, HttpClient? httpClient = null)
    {
        bool disposeClient = false;
        var client = httpClient;
        if (client == null)
        {
            client = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
            client.DefaultRequestHeaders.Add("User-Agent", $"RapiDrop/{CurrentVersion}");
            client.DefaultRequestHeaders.Add("Accept", "application/vnd.github.v3+json");
            disposeClient = true;
        }

        if (!string.IsNullOrEmpty(storedEtag))
        {
            client.DefaultRequestHeaders.TryAddWithoutValidation("If-None-Match", storedEtag);
        }

        try
        {
            using var response = await client.GetAsync(GitHubReleasesUrl);
            if (response.StatusCode == HttpStatusCode.NotModified || response.StatusCode == HttpStatusCode.NotFound)
            {
                return new UpdateCheckResult(false, CurrentVersion, "", null, 0, "https://github.com/Prabotics/RapiDrop/releases", null, null);
            }

            if (!response.IsSuccessStatusCode)
            {
                return new UpdateCheckResult(false, CurrentVersion, "", null, 0, "https://github.com/Prabotics/RapiDrop/releases", $"HTTP {(int)response.StatusCode}", null);
            }

            string? newEtag = response.Headers.ETag?.Tag;

            var content = await response.Content.ReadAsStringAsync();
            using var doc = JsonDocument.Parse(content);
            string tagName = doc.RootElement.TryGetProperty("tag_name", out var tagElem) ? tagElem.GetString() ?? "" : "";
            string releaseNotes = doc.RootElement.TryGetProperty("body", out var bodyElem) ? bodyElem.GetString() ?? "" : "";
            string htmlUrl = doc.RootElement.TryGetProperty("html_url", out var urlElem) ? urlElem.GetString() ?? "" : "https://github.com/Prabotics/RapiDrop/releases";

            string? assetUrl = null;
            long assetSize = 0L;
            if (doc.RootElement.TryGetProperty("assets", out var assetsElem) && assetsElem.ValueKind == JsonValueKind.Array)
            {
                foreach (var asset in assetsElem.EnumerateArray())
                {
                    string name = asset.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
                    if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
                    {
                        assetUrl = asset.TryGetProperty("browser_download_url", out var b) ? b.GetString() : null;
                        assetSize = asset.TryGetProperty("size", out var s) ? s.GetInt64() : 0L;
                        break;
                    }
                }
            }

            if (IsNewerVersion(tagName, CurrentVersion))
            {
                return new UpdateCheckResult(true, tagName, releaseNotes, assetUrl, assetSize, htmlUrl, null, newEtag);
            }
            return new UpdateCheckResult(false, CurrentVersion, releaseNotes, assetUrl, assetSize, htmlUrl, null, newEtag);
        }
        catch (Exception ex)
        {
            return new UpdateCheckResult(false, CurrentVersion, "", null, 0, "https://github.com/Prabotics/RapiDrop/releases", ex.Message, null);
        }
        finally
        {
            if (disposeClient)
            {
                client.Dispose();
            }
        }
    }

    public static async Task<string?> DownloadInstallerAsync(string assetUrl, string version, Action<long, long, double>? onProgress = null, HttpClient? httpClient = null)
    {
        bool dispose = false;
        var client = httpClient;
        if (client == null)
        {
            client = new HttpClient { Timeout = TimeSpan.FromSeconds(60) };
            client.DefaultRequestHeaders.Add("User-Agent", $"RapiDrop/{CurrentVersion}");
            dispose = true;
        }

        try
        {
            string tempDir = Path.GetTempPath();
            string destFile = Path.Combine(tempDir, $"RapiDrop-Setup-{version}.exe");
            string partFile = destFile + ".part";
            if (File.Exists(partFile)) File.Delete(partFile);

            using var response = await client.GetAsync(assetUrl, HttpCompletionOption.ResponseHeadersRead);
            if (!response.IsSuccessStatusCode) return null;

            long totalBytes = response.Content.Headers.ContentLength ?? 0L;
            long bytesRead = 0L;
            byte[] buffer = new byte[32768];
            long startTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

            await using (var input = await response.Content.ReadAsStreamAsync())
            await using (var output = new FileStream(partFile, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                int read;
                while ((read = await input.ReadAsync(buffer, 0, buffer.Length)) > 0)
                {
                    await output.WriteAsync(buffer, 0, read);
                    bytesRead += read;
                    double elapsed = Math.Max(0.1, (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - startTime) / 1000.0);
                    double speed = bytesRead / elapsed;
                    onProgress?.Invoke(bytesRead, totalBytes, speed);
                }
            }

            if (File.Exists(destFile)) File.Delete(destFile);
            File.Move(partFile, destFile);
            return destFile;
        }
        catch
        {
            return null;
        }
        finally
        {
            if (dispose) client.Dispose();
        }
    }

    public static bool IsNewerVersion(string latestTag, string current)
    {
        if (string.IsNullOrWhiteSpace(latestTag)) return false;
        string cleanLatest = latestTag.Trim().TrimStart('v', 'V').Trim();
        string cleanCurrent = current.Trim().TrimStart('v', 'V').Trim();
        var latestParts = cleanLatest.Split('.').Select(p => int.TryParse(p, out int v) ? (int?)v : null).Where(v => v.HasValue).Select(v => v!.Value).ToList();
        var currentParts = cleanCurrent.Split('.').Select(p => int.TryParse(p, out int v) ? (int?)v : null).Where(v => v.HasValue).Select(v => v!.Value).ToList();
        if (latestParts.Count == 0 || currentParts.Count == 0) return false;
        int maxLen = Math.Max(latestParts.Count, currentParts.Count);
        for (int i = 0; i < maxLen; i++)
        {
            int l = i < latestParts.Count ? latestParts[i] : 0;
            int c = i < currentParts.Count ? currentParts[i] : 0;
            if (l > c) return true;
            if (l < c) return false;
        }
        return false;
    }
}
