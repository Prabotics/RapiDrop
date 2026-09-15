using System.Buffers;
using System.Buffers.Binary;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using RapiDrop.Core.Models;
using RapiDrop.Core.Security;

namespace RapiDrop.Core.Network;

public sealed class NetworkEngine : IDisposable
{
    public static string GetUniqueDestinationPath(string path)
    {
        if (!File.Exists(path)) return path;
        string dir = Path.GetDirectoryName(path) ?? "";
        string ext = Path.GetExtension(path);
        string baseName = Path.GetFileNameWithoutExtension(path);
        int counter = 1;
        string candidate;
        do
        {
            string newName = string.IsNullOrEmpty(ext) ? $"{baseName} ({counter})" : $"{baseName} ({counter}){ext}";
            candidate = Path.Combine(dir, newName);
            counter++;
        } while (File.Exists(candidate));
        return candidate;
    }
    public static string SanitizeRelativePath(string relativePath, string fallbackFileName = "file")
    {
        string cleanFallback = Path.GetFileName(fallbackFileName.Replace('/', '\\'))?.Trim() ?? "";
        string fallback = string.IsNullOrEmpty(cleanFallback) ? "file" : cleanFallback;
        string normalized = relativePath.Replace('\\', '/');
        var components = normalized.Split('/', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(segment => segment != "." && segment != ".." && !segment.Contains(':') && !segment.Contains('\0'))
            .ToArray();
        return components.Length == 0 ? fallback : string.Join(Path.DirectorySeparatorChar.ToString(), components);
    }

    public const int DefaultPort = WireFrame.DefaultPort;

    public event Action<ClipItem>? ClipReceived;
    public event Action<bool, string?>? ConnectionStateChanged;
    public event Action<IReadOnlyList<DiscoveredDevice>>? DiscoveredDevicesChanged;
    public event Action<DiscoveredDevice, string?>? PairInviteReceived;
    public event Action<DiscoveredDevice, string>? PairOfferReceived;
    public event Action<DiscoveredDevice, string>? PairAcceptReceived;
    public event Action<string>? PairDeclinedReceived;
    public event Action? RemoteUnpairedReceived;
    public event Action<bool>? PrivacyModeReceived;
    public event Action<string, string, long, long, int, int, bool>? TransferProgressUpdated;
    public event Action<string>? TransferCancelled;

    private readonly MdnsDiscovery _mdns;
    private TcpListener? _listener;
    private TcpListener? _clientListener;
    private readonly List<TcpClient> _activeClients = new();
    private readonly object _clientsLock = new();
    private CancellationTokenSource? _cts;
    private Task? _reconnectTask;
    private Task? _heartbeatTask;

    private byte[]? _sessionKey;
    private string? _pairedPeerName;
    private string? _pairedPeerId;
    private string? _pairedHost;
    private int? _pairedPort;
    private bool _isConnected;
    private string? _connectedPeerName;
    private long _lastDataOrPongReceivedTimestamp;

    private (byte[] PrivateKey, string PublicKeyHex, byte[] Nonce)? _pendingInitiatorKeypair;
    private (byte[] SessionKey, string SasCode, DiscoveredDevice Device)? _pendingReceiverState;
    private string? _pendingReceiverAcceptPayload;
    public bool IsConnected => _isConnected;
    public string? ConnectedPeerName => _connectedPeerName;

    public string DownloadFolderPath { get; set; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "RapiDrop");
    public string LocalDeviceId { get; set; } = Guid.NewGuid().ToString();

    private CancellationTokenSource? _activeSendCts;
    private string? _activeSendTransferId;
    private readonly object _transferLock = new();

    private sealed class IncomingStreamTransfer : IDisposable
    {
        public string TransferId { get; }
        public int FileIndex { get; }
        public int TotalFiles { get; }
        public string FileName { get; }
        public string RelativePath { get; }
        public long FileSize { get; }
        public long TotalBytes { get; }
        public long BytesReceived { get; set; }
        public long NextChunkIndex { get; set; }
        public FileStream Stream { get; }
        public IncrementalHash Hasher { get; }
        public string DestinationPath { get; }
        public string PartPath { get; }
        public IncomingStreamTransfer(string transferId, int fileIndex, int totalFiles, string fileName, string relativePath, long fileSize, long totalBytes, string destPath)
        {
            TransferId = transferId;
            FileIndex = fileIndex;
            TotalFiles = totalFiles;
            FileName = fileName;
            RelativePath = relativePath;
            FileSize = fileSize;
            TotalBytes = totalBytes;
            DestinationPath = destPath;
            PartPath = destPath + ".rapidrop_part";
            string? dir = Path.GetDirectoryName(destPath);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            Stream = new FileStream(PartPath, FileMode.Create, FileAccess.Write, FileShare.None);
            Hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        }

        public void Dispose()
        {
            Stream.Dispose();
            Hasher.Dispose();
        }
    }
    private IncomingStreamTransfer? _activeIncomingTransfer;
    public NetworkEngine()
    {
        _mdns = new MdnsDiscovery();
        _mdns.DevicesUpdated += devices =>
        {
            if (!_isConnected && _sessionKey != null && (!string.IsNullOrEmpty(_pairedPeerId) || !string.IsNullOrEmpty(_pairedPeerName)))
            {
                var match = devices.FirstOrDefault(d =>
                    (!string.IsNullOrEmpty(_pairedPeerId) && !string.IsNullOrEmpty(d.Id) && string.Equals(d.Id, _pairedPeerId, StringComparison.OrdinalIgnoreCase)) ||
                    (!string.IsNullOrEmpty(_pairedPeerName) && string.Equals(d.Name, _pairedPeerName, StringComparison.OrdinalIgnoreCase)));
                if (match != null && !string.IsNullOrEmpty(match.Host))
                {
                    _pairedHost = match.Host;
                    if (match.Port > 0 && match.Port != WireFrame.DefaultClientPort)
                    {
                        _pairedPort = match.Port;
                    }
                }
            }
            DiscoveredDevicesChanged?.Invoke(devices);
        };
        NetworkChange.NetworkAddressChanged += OnNetworkAddressChanged;
    }

    public byte[]? SessionKey => _sessionKey;

    public void SetSessionKey(byte[]? key)
    {
        _sessionKey = key;
    }

    public void CancelActiveTransfer(string? transferId = null)
    {
        string? targetId = null;
        lock (_transferLock)
        {
            if (_activeSendCts != null && !_activeSendCts.IsCancellationRequested)
            {
                targetId = _activeSendTransferId;
                _activeSendCts.Cancel();
            }
        }

        if (_activeIncomingTransfer != null)
        {
            targetId ??= _activeIncomingTransfer.TransferId;
            string part = _activeIncomingTransfer.PartPath;
            _activeIncomingTransfer.Dispose();
            try { File.Delete(part); } catch { }
            _activeIncomingTransfer = null;
        }

        string finalId = targetId ?? transferId ?? Guid.NewGuid().ToString();
        SendFileCancel(finalId);
        TransferCancelled?.Invoke(finalId);
    }

    public void SendFileCancel(string transferId)
    {
        if (_sessionKey == null) return;
        try
        {
            var cancelObj = new { transferId, status = "CANCELLED" };
            byte[] cancelJson = JsonSerializer.SerializeToUtf8Bytes(cancelObj);
            var (cancelCipher, cancelNonce, cancelTag) = CryptoEngine.Encrypt(cancelJson, _sessionKey);
            var cancelFrame = new WireFrame(PacketType.FileCancel, cancelNonce, cancelCipher, cancelTag);
            BroadcastFrame(cancelFrame);
        }
        catch { }
    }

    public void Start(string? pairedPeerName, string? pairedHost, int? pairedPort, byte[]? sessionKey, string? pairedPeerId = null)
    {
        _pairedPeerName = pairedPeerName;
        _pairedPeerId = pairedPeerId;
        _pairedHost = pairedHost;
        _pairedPort = pairedPort;
        _sessionKey = sessionKey;
        _lastDataOrPongReceivedTimestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

        _cts = new CancellationTokenSource();

        StartServer(DefaultPort);
        StartClientServer(WireFrame.DefaultClientPort);
        _mdns.Start(DefaultPort, LocalDeviceId);

        _reconnectTask = Task.Run(() => AutoReconnectLoopAsync(_cts.Token));
        _heartbeatTask = Task.Run(() => HeartbeatLoopAsync(_cts.Token));
    }

    public void Stop()
    {
        _cts?.Cancel();
        _mdns.Stop();

        try
        {
            _listener?.Stop();
        }
        catch { }
        _listener = null;
        try
        {
            _clientListener?.Stop();
        }
        catch { }
        _clientListener = null;

        lock (_clientsLock)
        {
            foreach (var client in _activeClients)
            {
                try { client.Close(); } catch { }
            }
            _activeClients.Clear();
        }

        UpdateConnectionState(false, null);
    }

    public void InitiatePairing(DiscoveredDevice target, string? pin = null)
    {
        byte[]? key = _sessionKey;
        if (key == null && !string.IsNullOrEmpty(pin))
        {
            key = CryptoEngine.DeriveKeyFromPin(pin);
            SetSessionKey(key);
        }
        int connectPort = (target.Port > 0 && target.Port != WireFrame.DefaultClientPort) ? target.Port : WireFrame.DefaultPort;
        _pairedPeerName = target.Name;
        if (!string.IsNullOrEmpty(target.Id)) _pairedPeerId = target.Id;
        _pairedHost = target.Host;
        _pairedPort = connectPort;
        if (key == null) return;
        byte[] sessionKeyToUse = key;

        Task.Run(async () =>
        {
            try
            {
                var client = new TcpClient();
                ConfigureSocketOptimizations(client);
                await client.ConnectAsync(target.Host, connectPort);
                AddActiveClient(client);

                var desc = ConnectedDeviceInfo.Current();
                string json = JsonSerializer.Serialize(desc);
                byte[] payload = Encoding.UTF8.GetBytes(json);

                var (ciphertext, nonce, tag) = CryptoEngine.Encrypt(payload, sessionKeyToUse);
                var frame = new WireFrame(PacketType.PairRequest, nonce, ciphertext, tag);
                await SendFrameAsync(client, frame);
                await SendDeviceInfoAsync(client, sessionKeyToUse);

                UpdateConnectionState(true, target.Name);

                _ = Task.Run(() => ReceiveLoopAsync(client, _cts?.Token ?? CancellationToken.None));
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Pairing failed to {target.Host}:{connectPort}: {ex.Message}");
            }
        });
    }

    public void SendPairInvite(DiscoveredDevice target, string? pin = null)
    {
        var (priv, pubHex) = CryptoEngine.GenerateEphemeralKeypair();
        byte[] nonce = CryptoEngine.GenerateNonce(16);
        string nonceHex = Convert.ToHexString(nonce).ToLowerInvariant();
        _pendingInitiatorKeypair = (priv, pubHex, nonce);

        Task.Run(async () =>
        {
            try
            {
                using var client = new TcpClient();
                ConfigureSocketOptimizations(client);
                await client.ConnectAsync(target.Host, target.Port);
                var addr = ((IPEndPoint)client.Client.LocalEndPoint!).Address;
                if (addr.IsIPv4MappedToIPv6) addr = addr.MapToIPv4();
                string localIp = addr.ToString();

                var payloadObj = new Dictionary<string, object?>
                {
                    ["version"] = 1,
                    ["id"] = LocalDeviceId,
                    ["fromDeviceName"] = Environment.MachineName,
                    ["deviceType"] = "windows",
                    ["model"] = "PC",
                    ["chip"] = RuntimeInformation.ProcessArchitecture.ToString(),
                    ["publicKey"] = pubHex,
                    ["nonce"] = nonceHex,
                    ["host"] = localIp,
                    ["port"] = DefaultPort
                };
                string json = JsonSerializer.Serialize(payloadObj);
                byte[] payload = Encoding.UTF8.GetBytes(json);
                var frame = new WireFrame(PacketType.PairInvite, new byte[12], payload, new byte[16]);
                await SendFrameAsync(client, frame);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to send PairInvite to {target.Host}:{target.Port}: {ex.Message}");
            }
        });
    }

    public void SendPairAccept(string host, int port)
    {
        if (_pendingReceiverState != null)
        {
            _sessionKey = _pendingReceiverState.Value.SessionKey;
        }

        Task.Run(async () =>
        {
            try
            {
                using var client = new TcpClient();
                ConfigureSocketOptimizations(client);
                await client.ConnectAsync(host, port);
                var addr = ((IPEndPoint)client.Client.LocalEndPoint!).Address;
                if (addr.IsIPv4MappedToIPv6) addr = addr.MapToIPv4();
                string localIp = addr.ToString();

                string json = _pendingReceiverAcceptPayload ?? JsonSerializer.Serialize(new Dictionary<string, object?>
                {
                    ["version"] = 1,
                    ["id"] = LocalDeviceId,
                    ["status"] = "accepted",
                    ["fromDeviceName"] = Environment.MachineName,
                    ["deviceType"] = "windows",
                    ["host"] = localIp,
                    ["port"] = DefaultPort
                });

                byte[] payload = Encoding.UTF8.GetBytes(json);
                var frame = new WireFrame(PacketType.PairAccept, new byte[12], payload, new byte[16]);
                await SendFrameAsync(client, frame);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to send PairAccept: {ex.Message}");
            }
        });
    }

    private void SendPairOffer(string host, int port, string payloadJson)
    {
        Task.Run(async () =>
        {
            try
            {
                using var client = new TcpClient();
                ConfigureSocketOptimizations(client);
                await client.ConnectAsync(host, port);
                byte[] payload = Encoding.UTF8.GetBytes(payloadJson);
                var frame = new WireFrame(PacketType.PairAccept, new byte[12], payload, new byte[16]);
                await SendFrameAsync(client, frame);
            }
            catch { }
        });
    }

    public void SendPairDecline(string host, int port, string reason = "DECLINED")
    {
        _pendingReceiverState = null;
        Task.Run(async () =>
        {
            try
            {
                using var client = new TcpClient();
                ConfigureSocketOptimizations(client);
                await client.ConnectAsync(host, port);
                string json = JsonSerializer.Serialize(new Dictionary<string, object?>
                {
                    ["version"] = 1,
                    ["reason"] = reason
                });
                byte[] payload = Encoding.UTF8.GetBytes(json);
                var frame = new WireFrame(PacketType.PairFail, new byte[12], payload, new byte[16]);
                await SendFrameAsync(client, frame);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to send PairDecline: {ex.Message}");
            }
        });
    }

    public void SendClip(ClipItem item)
    {
        byte[]? key = _sessionKey;
        if (key == null) return;

        byte[] payload;
        PacketType packetType;

        switch (item.Type)
        {
            case ClipContentType.Text:
                if (string.IsNullOrEmpty(item.TextContent)) return;
                payload = Encoding.UTF8.GetBytes(item.TextContent);
                packetType = PacketType.ClipText;
                break;

            case ClipContentType.Url:
                if (string.IsNullOrEmpty(item.TextContent)) return;
                payload = Encoding.UTF8.GetBytes(item.TextContent);
                packetType = PacketType.ClipUrl;
                break;

            case ClipContentType.Image:
                if (item.RawData == null || item.RawData.Length == 0) return;
                payload = item.RawData;
                packetType = PacketType.ClipImage;
                break;

            case ClipContentType.File:
                if (item.RawData == null || string.IsNullOrEmpty(item.FileName)) return;
                byte[] nameBytes = Encoding.UTF8.GetBytes(item.FileName);
                payload = new byte[2 + nameBytes.Length + item.RawData.Length];
                BinaryPrimitives.WriteUInt16BigEndian(payload.AsSpan(0, 2), (ushort)nameBytes.Length);
                nameBytes.CopyTo(payload, 2);
                item.RawData.CopyTo(payload, 2 + nameBytes.Length);
                packetType = PacketType.ClipFile;
                break;

            default:
                return;
        }

        var (ciphertext, nonce, tag) = CryptoEngine.Encrypt(payload, key);
        var frame = new WireFrame(packetType, nonce, ciphertext, tag);

        BroadcastFrame(frame);
    }

    public void SendConfigSync(bool privacyMode)
    {
        byte[]? key = _sessionKey;
        if (key == null) return;

        byte[] payload = Encoding.UTF8.GetBytes($"{{\"privacyMode\":{(privacyMode ? "true" : "false")}}}");
        var (ciphertext, nonce, tag) = CryptoEngine.Encrypt(payload, key);
        var frame = new WireFrame(PacketType.ConfigSync, nonce, ciphertext, tag);
        BroadcastFrame(frame);
    }
    public async Task SendStreamingFilesAsync(IEnumerable<string> paths, Action<string, string, long, long, int, int>? progressCallback = null, CancellationToken ct = default)
    {
        byte[]? key = _sessionKey;
        if (key == null || !_isConnected) return;

        var fileEntries = new List<(string FilePath, string RelativePath, long FileSize)>();
        foreach (var p in paths)
        {
            if (File.Exists(p))
            {
                var fi = new FileInfo(p);
                fileEntries.Add((p, fi.Name, fi.Length));
            }
            else if (Directory.Exists(p))
            {
                var di = new DirectoryInfo(p);
                string parentDir = di.Parent?.FullName ?? di.FullName;
                foreach (var f in Directory.EnumerateFiles(p, "*", SearchOption.AllDirectories))
                {
                    var fi = new FileInfo(f);
                    string rel = Path.GetRelativePath(parentDir, f);
                    fileEntries.Add((f, rel, fi.Length));
                }
            }
        }
        if (fileEntries.Count == 0) return;

        long totalBatchBytes = fileEntries.Sum(e => e.FileSize);
        string transferId = Guid.NewGuid().ToString();
        long overallBytesSent = 0;

        CancellationTokenSource linkedCts;
        lock (_transferLock)
        {
            _activeSendCts?.Cancel();
            _activeSendCts?.Dispose();
            _activeSendCts = new CancellationTokenSource();
            _activeSendTransferId = transferId;
            linkedCts = CancellationTokenSource.CreateLinkedTokenSource(ct, _activeSendCts.Token);
        }

        try
        {
            var effectiveCt = linkedCts.Token;
            byte[] rentedBuffer = ArrayPool<byte>.Shared.Rent(WireFrame.StreamingChunkSize);

            try
            {
                for (int i = 0; i < fileEntries.Count; i++)
                {
                    effectiveCt.ThrowIfCancellationRequested();
                    var entry = fileEntries[i];

                    var startObj = new
                    {
                        transferId,
                        fileIndex = i,
                        totalFiles = fileEntries.Count,
                        fileName = Path.GetFileName(entry.FilePath),
                        relativePath = entry.RelativePath,
                        fileSize = entry.FileSize,
                        totalBytes = totalBatchBytes
                    };
                    byte[] startJson = JsonSerializer.SerializeToUtf8Bytes(startObj);
                    var (startCipher, startNonce, startTag) = CryptoEngine.Encrypt(startJson, key);
                    var startFrame = new WireFrame(PacketType.FileStart, startNonce, startCipher, startTag);
                    BroadcastFrame(startFrame);

                    using var stream = new FileStream(entry.FilePath, FileMode.Open, FileAccess.Read, FileShare.Read);
                    using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                    long chunkIndex = 0;
                    int read;

                    while ((read = await stream.ReadAsync(rentedBuffer.AsMemory(0, WireFrame.StreamingChunkSize), effectiveCt)) > 0)
                    {
                        hasher.AppendData(rentedBuffer, 0, read);

                        byte[] rentedPayload = ArrayPool<byte>.Shared.Rent(28 + read);
                        try
                        {
                            Array.Clear(rentedPayload, 0, 16);
                            byte[] idBytes = Encoding.UTF8.GetBytes(transferId);
                            Array.Copy(idBytes, rentedPayload, Math.Min(idBytes.Length, 16));
                            BinaryPrimitives.WriteInt32BigEndian(rentedPayload.AsSpan(16, 4), i);
                            BinaryPrimitives.WriteInt64BigEndian(rentedPayload.AsSpan(20, 8), chunkIndex);
                            Array.Copy(rentedBuffer, 0, rentedPayload, 28, read);

                            var (chunkCipher, chunkNonce, chunkTag) = CryptoEngine.Encrypt(rentedPayload.AsSpan(0, 28 + read).ToArray(), key);
                            var chunkFrame = new WireFrame(PacketType.FileChunk, chunkNonce, chunkCipher, chunkTag);
                            BroadcastFrame(chunkFrame);
                        }
                        finally
                        {
                            ArrayPool<byte>.Shared.Return(rentedPayload);
                        }

                        chunkIndex++;
                        overallBytesSent += read;
                        progressCallback?.Invoke(transferId, Path.GetFileName(entry.FilePath), overallBytesSent, totalBatchBytes, i + 1, fileEntries.Count);
                    }

                    byte[] hash = hasher.GetHashAndReset();
                    string sha256 = Convert.ToHexString(hash).ToLowerInvariant();
                    var endObj = new
                    {
                        transferId,
                        fileIndex = i,
                        sha256,
                        status = "OK"
                    };
                    byte[] endJson = JsonSerializer.SerializeToUtf8Bytes(endObj);
                    var (endCipher, endNonce, endTag) = CryptoEngine.Encrypt(endJson, key);
                    var endFrame = new WireFrame(PacketType.FileEnd, endNonce, endCipher, endTag);
                    BroadcastFrame(endFrame);
                }
            }
            finally
            {
                ArrayPool<byte>.Shared.Return(rentedBuffer);
            }
        }
        catch (OperationCanceledException)
        {
            TransferCancelled?.Invoke(transferId);
            throw;
        }
        finally
        {
            lock (_transferLock)
            {
                if (_activeSendTransferId == transferId)
                {
                    _activeSendTransferId = null;
                    _activeSendCts?.Dispose();
                    _activeSendCts = null;
                }
            }
            linkedCts.Dispose();
        }
    }

    private void BroadcastFrame(WireFrame frame)
    {
        byte[] serialized = frame.Serialize();
        lock (_clientsLock)
        {
            foreach (var client in _activeClients.ToList())
            {
                try
                {
                    if (client.Connected)
                    {
                        var stream = client.GetStream();
                        stream.Write(serialized, 0, serialized.Length);
                    }
                    else
                    {
                        RemoveActiveClient(client);
                    }
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Broadcast write failed: {ex.Message}");
                    RemoveActiveClient(client);
                }
            }
        }
    }

    private async Task SendFrameAsync(TcpClient client, WireFrame frame)
    {
        byte[] serialized = frame.Serialize();
        var stream = client.GetStream();
        await stream.WriteAsync(serialized, 0, serialized.Length);
    }

    private void StartServer(int port)
    {
        try
        {
            _listener = new TcpListener(IPAddress.Any, port);
            _listener.Start();
            _ = Task.Run(() => AcceptClientsLoopAsync(_listener, _cts?.Token ?? CancellationToken.None));
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Failed to start TCP listener on port {port}: {ex.Message}");
        }
    }

    private void StartClientServer(int port)
    {
        try
        {
            _clientListener = new TcpListener(IPAddress.Any, port);
            _clientListener.Start();
            _ = Task.Run(() => AcceptClientsLoopAsync(_clientListener, _cts?.Token ?? CancellationToken.None));
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Failed to start client TCP listener on port {port}: {ex.Message}");
        }
    }
    private static void ConfigureSocketOptimizations(TcpClient client)
    {
        try
        {
            client.NoDelay = true;
            client.ReceiveBufferSize = 256 * 1024;
            client.SendBufferSize = 256 * 1024;
        }
        catch { }
    }

    private async Task AcceptClientsLoopAsync(TcpListener listener, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var client = await listener.AcceptTcpClientAsync(ct);
                ConfigureSocketOptimizations(client);
                try
                {
                    client.ReceiveTimeout = 15000;
                    client.SendTimeout = 10000;
                }
                catch { }
                _lastDataOrPongReceivedTimestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                AddActiveClient(client);
                _ = Task.Run(() => ReceiveLoopAsync(client, ct), ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Accept client failed: {ex.Message}");
            }
        }
    }

    private async Task ReceiveLoopAsync(TcpClient client, CancellationToken ct)
    {
        NetworkStream stream = client.GetStream();
        byte[] headerBuffer = new byte[WireFrame.HeaderSize];

        try
        {
            while (!ct.IsCancellationRequested && client.Connected)
            {
                bool ok = await ReadExactBytesAsync(stream, headerBuffer, 0, WireFrame.HeaderSize, ct);
                if (!ok) break;

                uint payloadLength = BinaryPrimitives.ReadUInt32BigEndian(headerBuffer.AsSpan(0, 4));
                if (payloadLength > WireFrame.MaxPayloadSize)
                {
                    break;
                }

                int totalLength = WireFrame.HeaderSize + (int)payloadLength;
                byte[] fullFrame = ArrayPool<byte>.Shared.Rent(totalLength);
                try
                {
                    Buffer.BlockCopy(headerBuffer, 0, fullFrame, 0, WireFrame.HeaderSize);
                    ok = await ReadExactBytesAsync(stream, fullFrame, WireFrame.HeaderSize, (int)payloadLength, ct);
                    if (!ok) break;

                    var frame = WireFrame.Deserialize(fullFrame.AsSpan(0, totalLength));
                    if (frame != null)
                    {
                        _lastDataOrPongReceivedTimestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                        await ProcessFrameAsync(client, frame);
                    }
                }
                finally
                {
                    ArrayPool<byte>.Shared.Return(fullFrame);
                }
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Receive error: {ex.Message}");
        }
        finally
        {
            RemoveActiveClient(client);
        }
    }

    private async Task ProcessFrameAsync(TcpClient client, WireFrame frame)
    {
        if (frame.Type == PacketType.PairInvite)
        {
            try
            {
                string json = Encoding.UTF8.GetString(frame.Ciphertext);
                using var doc = JsonDocument.Parse(json);
                int version = doc.RootElement.TryGetProperty("version", out var vEl) && vEl.TryGetInt32(out int v) ? v : 1;
                string? remotePubHex = doc.RootElement.TryGetProperty("publicKey", out var pubEl) ? pubEl.GetString() : null;
                string? remoteNonceHex = doc.RootElement.TryGetProperty("nonce", out var nonceEl) ? nonceEl.GetString() : null;

                if (version < 1 || string.IsNullOrEmpty(remotePubHex) || string.IsNullOrEmpty(remoteNonceHex))
                {
                    return;
                }

                byte[]? remotePubBytes = CryptoEngine.HexToBytes(remotePubHex);
                byte[]? remoteNonceBytes = CryptoEngine.HexToBytes(remoteNonceHex);
                if (remotePubBytes == null || remotePubBytes.Length != 32 || remoteNonceBytes == null || remoteNonceBytes.Length != 16)
                {
                    return;
                }

                string fromDeviceName = doc.RootElement.TryGetProperty("fromDeviceName", out var fromEl) ? fromEl.GetString() ?? "Unknown Device" : "Unknown Device";
                string deviceType = doc.RootElement.TryGetProperty("deviceType", out var typeEl) ? typeEl.GetString() ?? "unknown" : "unknown";
                string? model = doc.RootElement.TryGetProperty("model", out var modelEl) ? modelEl.GetString() : null;

                var remoteAddr = ((IPEndPoint)client.Client.RemoteEndPoint!).Address;
                if (remoteAddr.IsIPv4MappedToIPv6) remoteAddr = remoteAddr.MapToIPv4();
                string clientIp = remoteAddr.ToString();
                int clientPort = WireFrame.DefaultPort;
                if (doc.RootElement.TryGetProperty("port", out var portEl) && portEl.TryGetInt32(out int p))
                {
                    clientPort = p;
                }

                var (privB, pubBHex) = CryptoEngine.GenerateEphemeralKeypair();
                byte[] pubBBytes = CryptoEngine.HexToBytes(pubBHex)!;
                byte[] nonceB = CryptoEngine.GenerateNonce(16);
                string nonceBHex = Convert.ToHexString(nonceB).ToLowerInvariant();

                byte[] sharedSecret = CryptoEngine.ComputeSharedSecret(privB, remotePubHex);
                string initiatorId = doc.RootElement.TryGetProperty("id", out var idEl) ? idEl.GetString() ?? "" : "";
                string receiverId = LocalDeviceId;
                var keys = CryptoEngine.DeriveHandshakeKeys(
                    sharedSecret,
                    remotePubBytes,
                    pubBBytes,
                    remoteNonceBytes,
                    nonceB,
                    initiatorId,
                    receiverId
                );

                var senderDevice = new DiscoveredDevice(fromDeviceName, clientIp, clientPort)
                {
                    DeviceType = deviceType,
                    Model = model
                };

                _pendingReceiverState = (keys.SessionKey, keys.SasCode, senderDevice);

                var addr = ((IPEndPoint)client.Client.LocalEndPoint!).Address;
                if (addr.IsIPv4MappedToIPv6) addr = addr.MapToIPv4();
                string localIp = addr.ToString();

                _pendingReceiverAcceptPayload = JsonSerializer.Serialize(new Dictionary<string, object?>
                {
                    ["version"] = 1,
                    ["id"] = LocalDeviceId,
                    ["status"] = "accepted",
                    ["fromDeviceName"] = Environment.MachineName,
                    ["deviceType"] = "windows",
                    ["model"] = "PC",
                    ["chip"] = RuntimeInformation.ProcessArchitecture.ToString(),
                    ["publicKey"] = pubBHex,
                    ["nonce"] = nonceBHex,
                    ["host"] = localIp,
                    ["port"] = DefaultPort
                });

                string offerPayload = JsonSerializer.Serialize(new Dictionary<string, object?>
                {
                    ["version"] = 1,
                    ["id"] = LocalDeviceId,
                    ["status"] = "offered",
                    ["fromDeviceName"] = Environment.MachineName,
                    ["deviceType"] = "windows",
                    ["model"] = "PC",
                    ["chip"] = RuntimeInformation.ProcessArchitecture.ToString(),
                    ["publicKey"] = pubBHex,
                    ["nonce"] = nonceBHex,
                    ["host"] = localIp,
                    ["port"] = DefaultPort
                });
                SendPairOffer(clientIp, clientPort, offerPayload);
                PairInviteReceived?.Invoke(senderDevice, keys.SasCode);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to parse PairInvite: {ex.Message}");
            }
            return;
        }

        if (frame.Type == PacketType.PairAccept)
        {
            try
            {
                string json = Encoding.UTF8.GetString(frame.Ciphertext);
                using var doc = JsonDocument.Parse(json);
                string fromDeviceName = doc.RootElement.TryGetProperty("fromDeviceName", out var fromEl) ? fromEl.GetString() ?? "Device" : "Device";
                string? remotePubHex = doc.RootElement.TryGetProperty("publicKey", out var pubEl) ? pubEl.GetString() : null;
                string? remoteNonceHex = doc.RootElement.TryGetProperty("nonce", out var nonceEl) ? nonceEl.GetString() : null;

                string sasCode = "";
                var initiatorState = _pendingInitiatorKeypair;
                if (initiatorState != null && !string.IsNullOrEmpty(remotePubHex) && !string.IsNullOrEmpty(remoteNonceHex))
                {
                    byte[]? remotePubBytes = CryptoEngine.HexToBytes(remotePubHex);
                    byte[]? remoteNonceBytes = CryptoEngine.HexToBytes(remoteNonceHex);
                    byte[] initPubBytes = CryptoEngine.HexToBytes(initiatorState.Value.PublicKeyHex)!;

                    if (remotePubBytes != null && remotePubBytes.Length == 32 && remoteNonceBytes != null && remoteNonceBytes.Length == 16)
                    {
                        byte[] sharedSecret = CryptoEngine.ComputeSharedSecret(initiatorState.Value.PrivateKey, remotePubHex);
                        string initiatorId = LocalDeviceId;
                        string receiverId = doc.RootElement.TryGetProperty("id", out var idEl) ? idEl.GetString() ?? "" : "";
                        var keys = CryptoEngine.DeriveHandshakeKeys(
                            sharedSecret,
                            initPubBytes,
                            remotePubBytes,
                            initiatorState.Value.Nonce,
                            remoteNonceBytes,
                            initiatorId,
                            receiverId
                        );
                        _sessionKey = keys.SessionKey;
                        sasCode = keys.SasCode;
                    }
                }

                string? host = doc.RootElement.TryGetProperty("host", out var hostEl) ? hostEl.GetString() : null;
                int port = doc.RootElement.TryGetProperty("port", out var portEl) && portEl.TryGetInt32(out int p) ? p : DefaultPort;
                var remoteAccAddr = ((IPEndPoint)client.Client.RemoteEndPoint!).Address;
                if (remoteAccAddr.IsIPv4MappedToIPv6) remoteAccAddr = remoteAccAddr.MapToIPv4();
                string remoteIp = remoteAccAddr.ToString();
                string targetHost = !string.IsNullOrEmpty(host) ? host.Replace("::ffff:", "") : remoteIp;
                var target = new DiscoveredDevice(fromDeviceName, targetHost, port);
                string status = doc.RootElement.TryGetProperty("status", out var stEl) ? stEl.GetString() ?? "accepted" : "accepted";
                if (status == "offered")
                {
                    PairOfferReceived?.Invoke(target, sasCode);
                }
                else
                {
                    _pendingInitiatorKeypair = null;
                    PairAcceptReceived?.Invoke(target, sasCode);
                }
            }
            catch { }
            return;
        }

        if (frame.Type == PacketType.PairFail)
        {
            try
            {
                string json = Encoding.UTF8.GetString(frame.Ciphertext);
                string reason = "DECLINED";
                try
                {
                    using var doc = JsonDocument.Parse(json);
                    if (doc.RootElement.TryGetProperty("reason", out var rEl))
                    {
                        reason = rEl.GetString() ?? reason;
                    }
                }
                catch
                {
                    reason = json;
                }
                _pendingInitiatorKeypair = null;
                _pendingReceiverState = null;
                PairDeclinedReceived?.Invoke(reason);
            }
            catch { }
            return;
         }
        if (frame.Type == PacketType.Disconnect)
        {
            RemoveActiveClient(client);
            RemoteUnpairedReceived?.Invoke();
            return;
        }

        byte[]? key = _sessionKey;
        if (key == null) return;
        if (!CryptoEngine.TryDecrypt(frame.Ciphertext, frame.Nonce, frame.Tag, key, out byte[]? decrypted) || decrypted == null)
        {
            return;
        }
        switch (frame.Type)
        {
            case PacketType.PairRequest:
                string reqPeerName = "Android/Mac Device";
                try
                {
                    string json = Encoding.UTF8.GetString(decrypted);
                    var descriptor = JsonSerializer.Deserialize<ConnectedDeviceInfo>(json);
                    if (descriptor != null) reqPeerName = descriptor.ModelName;
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Failed to parse PairRequest descriptor: {ex.Message}");
                }

                UpdateConnectionState(true, reqPeerName);

                var (confCipher, confNonce, confTag) = CryptoEngine.Encrypt("OK"u8.ToArray(), key);
                var confFrame = new WireFrame(PacketType.PairConfirm, confNonce, confCipher, confTag);
                await SendFrameAsync(client, confFrame);

                await SendDeviceInfoAsync(client, key);
                break;

            case PacketType.PairConfirm:
                UpdateConnectionState(true, _pairedPeerName ?? "Connected Device");
                await SendDeviceInfoAsync(client, key);
                break;

            case PacketType.DeviceInfo:
                try
                {
                    string json = Encoding.UTF8.GetString(decrypted);
                    var info = JsonSerializer.Deserialize<ConnectedDeviceInfo>(json);
                    if (info != null)
                    {
                        UpdateConnectionState(true, info.ModelName);
                    }
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Failed to parse DeviceInfo: {ex.Message}");
                }
                break;

            case PacketType.ClipText:
                string text = Encoding.UTF8.GetString(decrypted);
                ClipReceived?.Invoke(new ClipItem(ClipContentType.Text, textContent: text));
                break;

            case PacketType.ClipUrl:
                string url = Encoding.UTF8.GetString(decrypted);
                ClipReceived?.Invoke(new ClipItem(ClipContentType.Url, textContent: url));
                break;

            case PacketType.ClipImage:
                ClipReceived?.Invoke(new ClipItem(ClipContentType.Image, rawData: decrypted, fileName: "Synced Image.png"));
                break;

            case PacketType.ClipFile:
                if (decrypted.Length >= 2)
                {
                    ushort nameLen = BinaryPrimitives.ReadUInt16BigEndian(decrypted.AsSpan(0, 2));
                    if (decrypted.Length >= 2 + nameLen)
                    {
                        string fileName = Encoding.UTF8.GetString(decrypted, 2, nameLen);
                        byte[] fileBytes = decrypted.AsSpan(2 + nameLen).ToArray();
                        ClipReceived?.Invoke(new ClipItem(ClipContentType.File, rawData: fileBytes, fileName: fileName));
                    }
                }
                break;

            case PacketType.Ping:
                var (pongCipher, pongNonce, pongTag) = CryptoEngine.Encrypt("PONG"u8.ToArray(), key);
                var pongFrame = new WireFrame(PacketType.Pong, pongNonce, pongCipher, pongTag);
                await SendFrameAsync(client, pongFrame);
                break;

            case PacketType.Pong:
                break;

            case PacketType.ConfigSync:
                try
                {
                    string json = Encoding.UTF8.GetString(decrypted);
                    using var doc = JsonDocument.Parse(json);
                    if (doc.RootElement.TryGetProperty("privacyMode", out var prop) &&
                        (prop.ValueKind == JsonValueKind.True || prop.ValueKind == JsonValueKind.False))
                    {
                        PrivacyModeReceived?.Invoke(prop.GetBoolean());
                    }
                }
                catch { }
                break;
            case PacketType.FileStart:
                try
                {
                    string json = Encoding.UTF8.GetString(decrypted);
                    using var doc = JsonDocument.Parse(json);
                    string transferId = doc.RootElement.GetProperty("transferId").GetString() ?? "";
                    int fileIndex = doc.RootElement.GetProperty("fileIndex").GetInt32();
                    int totalFiles = doc.RootElement.GetProperty("totalFiles").GetInt32();
                    string fileName = doc.RootElement.GetProperty("fileName").GetString() ?? "file";
                    string relativePath = doc.RootElement.TryGetProperty("relativePath", out var relProp) ? (relProp.GetString() ?? fileName) : fileName;
                    long fileSize = doc.RootElement.GetProperty("fileSize").GetInt64();
                    long totalBytes = doc.RootElement.GetProperty("totalBytes").GetInt64();

                    string safeRel = SanitizeRelativePath(relativePath, fileName);
                    string rawDest = Path.Combine(DownloadFolderPath, safeRel);
                    string canonicalDest = Path.GetFullPath(rawDest);
                    string canonicalDownload = Path.GetFullPath(DownloadFolderPath);
                    string basePrefix = canonicalDownload.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
                    if (!canonicalDest.StartsWith(basePrefix, StringComparison.OrdinalIgnoreCase) && !string.Equals(canonicalDest, canonicalDownload, StringComparison.OrdinalIgnoreCase))
                    {
                        break;
                    }
                    string fullDest = GetUniqueDestinationPath(canonicalDest);
                    if (_activeIncomingTransfer != null)
                    {
                        string oldPart = _activeIncomingTransfer.PartPath;
                        _activeIncomingTransfer.Dispose();
                        try { File.Delete(oldPart); } catch { }
                        _activeIncomingTransfer = null;
                    }
                    _activeIncomingTransfer = new IncomingStreamTransfer(transferId, fileIndex, totalFiles, Path.GetFileName(fullDest), relativePath, fileSize, totalBytes, fullDest);
                    TransferProgressUpdated?.Invoke(transferId, Path.GetFileName(fullDest), 0, totalBytes, fileIndex, totalFiles, false);
                }
                catch { }
                break;

            case PacketType.FileChunk:
                if (_activeIncomingTransfer != null && decrypted.Length >= 28)
                {
                    byte[] incomingId = decrypted.AsSpan(0, 16).ToArray();
                    byte[] expectedId = new byte[16];
                    byte[] idBytes = Encoding.UTF8.GetBytes(_activeIncomingTransfer.TransferId);
                    Array.Copy(idBytes, expectedId, Math.Min(idBytes.Length, 16));
                    int incomingFileIndex = BinaryPrimitives.ReadInt32BigEndian(decrypted.AsSpan(16, 4));
                    long incomingChunkIndex = BinaryPrimitives.ReadInt64BigEndian(decrypted.AsSpan(20, 8));
                    if (incomingId.SequenceEqual(expectedId) &&
                        incomingFileIndex == _activeIncomingTransfer.FileIndex &&
                        incomingChunkIndex == _activeIncomingTransfer.NextChunkIndex)
                    {
                        var chunkData = decrypted.AsSpan(28);
                        if (_activeIncomingTransfer.FileSize > 0 && _activeIncomingTransfer.BytesReceived + chunkData.Length > _activeIncomingTransfer.FileSize)
                        {
                            string tid = _activeIncomingTransfer.TransferId;
                            string part = _activeIncomingTransfer.PartPath;
                            _activeIncomingTransfer.Dispose();
                            try { File.Delete(part); } catch { }
                            _activeIncomingTransfer = null;
                            TransferCancelled?.Invoke(tid);
                            break;
                        }
                        _activeIncomingTransfer.Stream.Write(chunkData);
                        _activeIncomingTransfer.Hasher.AppendData(chunkData);
                        _activeIncomingTransfer.BytesReceived += chunkData.Length;
                        _activeIncomingTransfer.NextChunkIndex++;
                        TransferProgressUpdated?.Invoke(
                            _activeIncomingTransfer.TransferId,
                            _activeIncomingTransfer.FileName,
                            _activeIncomingTransfer.BytesReceived,
                            _activeIncomingTransfer.TotalBytes,
                            _activeIncomingTransfer.FileIndex,
                            _activeIncomingTransfer.TotalFiles,
                            false);
                    }
                }
                break;

            case PacketType.FileEnd:
                if (_activeIncomingTransfer != null)
                {
                    _activeIncomingTransfer.Stream.Flush();
                    byte[] finalHash = _activeIncomingTransfer.Hasher.GetHashAndReset();
                    string actualSha = Convert.ToHexString(finalHash).ToLowerInvariant();
                    string transferId = _activeIncomingTransfer.TransferId;
                    string fileName = _activeIncomingTransfer.FileName;
                    long totalBytes = _activeIncomingTransfer.TotalBytes;
                    long fileSize = _activeIncomingTransfer.FileSize;
                    long bytesReceived = _activeIncomingTransfer.BytesReceived;
                    int fileIndex = _activeIncomingTransfer.FileIndex;
                    int totalFiles = _activeIncomingTransfer.TotalFiles;
                    string destPath = _activeIncomingTransfer.DestinationPath;
                    string partPath = _activeIncomingTransfer.PartPath;

                    _activeIncomingTransfer.Dispose();
                    _activeIncomingTransfer = null;

                    string json = Encoding.UTF8.GetString(decrypted);
                    using var doc = JsonDocument.Parse(json);
                    string? sentSha = doc.RootElement.TryGetProperty("sha256", out var shaProp) ? shaProp.GetString() : null;
                    bool isSizeValid = fileSize <= 0 || bytesReceived == fileSize;
                    if (!string.IsNullOrEmpty(sentSha) && string.Equals(sentSha, actualSha, StringComparison.OrdinalIgnoreCase) && isSizeValid)
                    {
                        if (File.Exists(destPath)) File.Delete(destPath);
                        File.Move(partPath, destPath);
                        TransferProgressUpdated?.Invoke(transferId, fileName, totalBytes, totalBytes, fileIndex, totalFiles, true);
                        ClipReceived?.Invoke(new ClipItem(ClipContentType.File, fileName: fileName, rawData: null));
                    }
                    else
                    {
                        try { File.Delete(partPath); } catch { }
                        TransferCancelled?.Invoke(transferId);
                    }
                }
                break;

            case PacketType.FileCancel:
                if (_activeIncomingTransfer != null)
                {
                    string transferId = _activeIncomingTransfer.TransferId;
                    string part = _activeIncomingTransfer.PartPath;
                    _activeIncomingTransfer.Dispose();
                    try { File.Delete(part); } catch { }
                    _activeIncomingTransfer = null;
                    TransferCancelled?.Invoke(transferId);
                }
                lock (_transferLock)
                {
                    if (_activeSendCts != null && !_activeSendCts.IsCancellationRequested)
                    {
                        _activeSendCts.Cancel();
                    }
                }
                break;

        }
    }

    private async Task SendDeviceInfoAsync(TcpClient client, byte[] key)
    {
        var info = ConnectedDeviceInfo.Current();
        string json = JsonSerializer.Serialize(info);
        byte[] payload = Encoding.UTF8.GetBytes(json);
        var (cipher, nonce, tag) = CryptoEngine.Encrypt(payload, key);
        var frame = new WireFrame(PacketType.DeviceInfo, nonce, cipher, tag);
        await SendFrameAsync(client, frame);
    }

    private async Task<bool> ReadExactBytesAsync(NetworkStream stream, byte[] buffer, int offset, int count, CancellationToken ct)
    {
        int bytesRead = 0;
        while (bytesRead < count)
        {
            int chunk = await stream.ReadAsync(buffer.AsMemory(offset + bytesRead, count - bytesRead), ct);
            if (chunk == 0) return false;
            bytesRead += chunk;
        }
        return true;
    }

    private void AddActiveClient(TcpClient client)
    {
        lock (_clientsLock)
        {
            _activeClients.Add(client);
        }
    }

    private void RemoveActiveClient(TcpClient client)
    {
        lock (_clientsLock)
        {
            _activeClients.Remove(client);
            try { client.Close(); } catch { }
            if (_activeClients.Count == 0)
            {
                if (_activeIncomingTransfer != null)
                {
                    string tid = _activeIncomingTransfer.TransferId;
                    string part = _activeIncomingTransfer.PartPath;
                    _activeIncomingTransfer.Dispose();
                    try { File.Delete(part); } catch { }
                    _activeIncomingTransfer = null;
                    TransferCancelled?.Invoke(tid);
                }
                UpdateConnectionState(false, null);
            }
        }
    }

    private void UpdateConnectionState(bool isConnected, string? peerName)
    {
        if (_isConnected == isConnected && _connectedPeerName == peerName) return;
        _isConnected = isConnected;
        _connectedPeerName = peerName;
        ConnectionStateChanged?.Invoke(isConnected, peerName);
    }

    public void SendDisconnect()
    {
        List<TcpClient> clientsCopy;
        lock (_clientsLock)
        {
            clientsCopy = new List<TcpClient>(_activeClients);
            _activeClients.Clear();
        }

        var frame = new WireFrame(PacketType.Disconnect, new byte[WireFrame.NonceSize], "UNPAIR"u8.ToArray(), new byte[WireFrame.AuthTagSize]);
        byte[] serialized = frame.Serialize();

        foreach (var client in clientsCopy)
        {
            try
            {
                var stream = client.GetStream();
                stream.Write(serialized, 0, serialized.Length);
                stream.Flush();
                try { client.Client.Shutdown(SocketShutdown.Send); } catch { }
            }
            catch { }
            finally
            {
                try { client.Close(); } catch { }
            }
        }

        string? targetHost = _pairedHost;
        int targetPort = _pairedPort ?? DefaultPort;
        if (!string.IsNullOrEmpty(targetHost))
        {
            _ = Task.Run(async () =>
            {
                try
                {
                    using var oobClient = new TcpClient();
                    ConfigureSocketOptimizations(oobClient);
                    using var oobCts = new CancellationTokenSource(2000);
                    await oobClient.ConnectAsync(targetHost, targetPort, oobCts.Token).ConfigureAwait(false);
                    var oobStream = oobClient.GetStream();
                    await oobStream.WriteAsync(serialized, 0, serialized.Length, oobCts.Token).ConfigureAwait(false);
                    await oobStream.FlushAsync(oobCts.Token).ConfigureAwait(false);
                }
                catch { }
                if (targetPort != WireFrame.DefaultClientPort)
                {
                    try
                    {
                        using var oobCliClient = new TcpClient();
                        ConfigureSocketOptimizations(oobCliClient);
                        using var oobCliCts = new CancellationTokenSource(2000);
                        await oobCliClient.ConnectAsync(targetHost, WireFrame.DefaultClientPort, oobCliCts.Token).ConfigureAwait(false);
                        var oobCliStream = oobCliClient.GetStream();
                        await oobCliStream.WriteAsync(serialized, 0, serialized.Length, oobCliCts.Token).ConfigureAwait(false);
                        await oobCliStream.FlushAsync(oobCliCts.Token).ConfigureAwait(false);
                    }
                    catch { }
                }
            });
        }

        DisconnectAll();
    }

    public void DisconnectSockets()
    {
        if (_activeIncomingTransfer != null)
        {
            string part = _activeIncomingTransfer.PartPath;
            _activeIncomingTransfer.Dispose();
            try { File.Delete(part); } catch { }
            _activeIncomingTransfer = null;
        }
        lock (_clientsLock)
        {
            foreach (var client in _activeClients)
            {
                try { client.Close(); } catch { }
            }
            _activeClients.Clear();
        }
        UpdateConnectionState(false, _pairedPeerName);
    }

    public void DisconnectAll()
    {
        DisconnectSockets();
        _sessionKey = null;
        _pairedHost = null;
        _pairedPort = null;
        _pairedPeerName = null;
        _pairedPeerId = null;
        UpdateConnectionState(false, null);
    }
    private async Task AutoReconnectLoopAsync(CancellationToken ct)
    {
        int backoffMs = 3000;
        while (!ct.IsCancellationRequested)
        {
            if (_isConnected || string.IsNullOrEmpty(_pairedHost) || _sessionKey == null)
            {
                backoffMs = 3000;
                await Task.Delay(5000, ct).ConfigureAwait(false);
                continue;
            }

            try
            {
                int port = _pairedPort ?? DefaultPort;
                if (port == WireFrame.DefaultClientPort)
                {
                    port = DefaultPort;
                }
                var client = new TcpClient();
                ConfigureSocketOptimizations(client);
                client.ReceiveTimeout = 15000;
                client.SendTimeout = 10000;
                await client.ConnectAsync(_pairedHost, port, ct).ConfigureAwait(false);
                _lastDataOrPongReceivedTimestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                AddActiveClient(client);
                var desc = ConnectedDeviceInfo.Current();
                string json = JsonSerializer.Serialize(desc);
                byte[] payload = Encoding.UTF8.GetBytes(json);
                var (ciphertext, nonce, tag) = CryptoEngine.Encrypt(payload, _sessionKey);
                var frame = new WireFrame(PacketType.PairRequest, nonce, ciphertext, tag);
                await SendFrameAsync(client, frame);
                await SendDeviceInfoAsync(client, _sessionKey);
                _ = Task.Run(() => ReceiveLoopAsync(client, ct), ct);
            }
            catch
            {
                await Task.Delay(backoffMs, ct).ConfigureAwait(false);
                backoffMs = Math.Min(20000, (int)(backoffMs * 1.5));
            }
        }
    }

    private async Task HeartbeatLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            await Task.Delay(5000, ct).ConfigureAwait(false);

            if (_isConnected && _sessionKey != null)
            {
                long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                if (_lastDataOrPongReceivedTimestamp > 0 && now - _lastDataOrPongReceivedTimestamp > 16000)
                {
                    DisconnectSockets();
                    continue;
                }
                var (cipher, nonce, tag) = CryptoEngine.Encrypt("PING"u8.ToArray(), _sessionKey);
                var frame = new WireFrame(PacketType.Ping, nonce, cipher, tag);
                BroadcastFrame(frame);
            }
        }
    }
    private void OnNetworkAddressChanged(object? sender, EventArgs e)
    {
        if (_mdns != null)
        {
            _mdns.Stop();
            _mdns.Start(DefaultPort, LocalDeviceId);
        }
    }

    public void Dispose()
    {
        NetworkChange.NetworkAddressChanged -= OnNetworkAddressChanged;
        Stop();
        _mdns.Dispose();
    }
}
