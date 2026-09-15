using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using RapiDrop.Core.Models;
using Makaretu.Dns;

namespace RapiDrop.Core.Network;

public sealed class MdnsDiscovery : IDisposable
{
    public const string ServerServiceType = WireFrame.ServiceType;
    public const string ClientServiceType = WireFrame.ClientServiceType;
    public const int DefaultPort = WireFrame.DefaultPort;

    public event Action<IReadOnlyList<DiscoveredDevice>>? DevicesUpdated;

    private readonly MulticastService _mdns;
    private readonly ServiceDiscovery _sd;
    private readonly ConcurrentDictionary<string, DiscoveredDevice> _devices = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, IPAddress> _ipCache = new(StringComparer.OrdinalIgnoreCase);
    private ServiceProfile? _serverProfile;
    private ServiceProfile? _clientProfile;
    private CancellationTokenSource? _queryCts;
    private Task? _queryTask;
    private string _localDeviceId = string.Empty;
    private bool _isStarted;
    private readonly object _lock = new();
    public MdnsDiscovery()
    {
        _mdns = new MulticastService(nics => nics.Where(nic =>
            nic.OperationalStatus == OperationalStatus.Up &&
            nic.SupportsMulticast &&
            nic.NetworkInterfaceType != NetworkInterfaceType.Loopback));
        _sd = new ServiceDiscovery(_mdns);

        _mdns.AnswerReceived += OnAnswerReceived;
        _sd.ServiceInstanceDiscovered += OnServiceInstanceDiscovered;
        _sd.ServiceInstanceShutdown += OnServiceInstanceShutdown;
    }

    public void Start(int port = DefaultPort, string deviceId = "")
    {
        lock (_lock)
        {
            if (_isStarted) return;
            _isStarted = true;
            _localDeviceId = deviceId;

            try
            {
                _mdns.Start();

                string machineName = Environment.MachineName;
                string instanceName = machineName;
                _serverProfile = new ServiceProfile(instanceName, ServerServiceType, (ushort)port);
                if (!string.IsNullOrEmpty(deviceId))
                {
                    _serverProfile.AddProperty("id", deviceId);
                }
                _serverProfile.AddProperty("device", "windows");
                _serverProfile.AddProperty("version", "1");
                _serverProfile.AddProperty("port", port.ToString());
                _serverProfile.AddProperty("model", "PC");
                _serverProfile.AddProperty("chip", Environment.GetEnvironmentVariable("PROCESSOR_ARCHITECTURE") ?? "x64");
                _sd.Advertise(_serverProfile);

                _clientProfile = new ServiceProfile(instanceName, ClientServiceType, (ushort)WireFrame.DefaultClientPort);
                if (!string.IsNullOrEmpty(deviceId))
                {
                    _clientProfile.AddProperty("id", deviceId);
                }
                _clientProfile.AddProperty("device", "windows");
                _clientProfile.AddProperty("version", "1");
                _clientProfile.AddProperty("port", WireFrame.DefaultClientPort.ToString());
                _clientProfile.AddProperty("model", "PC");
                _clientProfile.AddProperty("chip", Environment.GetEnvironmentVariable("PROCESSOR_ARCHITECTURE") ?? "x64");
                _sd.Advertise(_clientProfile);

                _queryCts = new CancellationTokenSource();
                _queryTask = Task.Run(() => PeriodicQueryLoopAsync(_queryCts.Token));

                QueryAll();
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"mDNS start failed: {ex.Message}");
            }
        }
    }

    public void Stop()
    {
        lock (_lock)
        {
            if (!_isStarted) return;
            _isStarted = false;

            try
            {
                lock (_notifyLock)
                {
                    _notifyDebounceCts?.Cancel();
                    _notifyDebounceCts?.Dispose();
                    _notifyDebounceCts = null;
                }
                _queryCts?.Cancel();
                _queryCts?.Dispose();
                _queryCts = null;
                if (_serverProfile != null) _sd.Unadvertise(_serverProfile);
                if (_clientProfile != null) _sd.Unadvertise(_clientProfile);
                _mdns.Stop();
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"mDNS stop failed: {ex.Message}");
            }

            _devices.Clear();
            _ipCache.Clear();
            NotifyDevicesUpdated();
        }
    }

    public void QueryAll()
    {
        try
        {
            _sd.QueryServiceInstances(ServerServiceType);
            _sd.QueryServiceInstances(ClientServiceType);
            _mdns.SendQuery(ServerServiceType + ".local");
            _mdns.SendQuery(ClientServiceType + ".local");
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"mDNS QueryAll error: {ex.Message}");
        }
    }

    private async Task PeriodicQueryLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(3500, ct);
                QueryAll();

                var cutoff = DateTime.UtcNow.AddSeconds(-60);
                bool removedAny = false;
                foreach (var kvp in _devices)
                {
                    if (kvp.Value.LastSeen < cutoff)
                    {
                        if (_devices.TryRemove(kvp.Key, out _))
                        {
                            removedAny = true;
                        }
                    }
                }

                if (removedAny)
                {
                    NotifyDevicesUpdated();
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"mDNS periodic loop error: {ex.Message}");
            }
        }
    }

    private void OnServiceInstanceDiscovered(object? sender, ServiceInstanceDiscoveryEventArgs e)
    {
        try
        {
            _mdns.SendQuery(e.ServiceInstanceName);
            var records = e.Message.Answers.Concat(e.Message.AdditionalRecords);
            ProcessRecords(records, e.RemoteEndPoint);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Error in ServiceInstanceDiscovered: {ex.Message}");
        }
    }

    private void OnAnswerReceived(object? sender, MessageEventArgs e)
    {
        try
        {
            var records = e.Message.Answers.Concat(e.Message.AdditionalRecords);
            ProcessRecords(records, e.RemoteEndPoint);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Error in AnswerReceived: {ex.Message}");
        }
    }

    private void OnServiceInstanceShutdown(object? sender, ServiceInstanceShutdownEventArgs e)
    {
        try
        {
            string instanceName = e.ServiceInstanceName.Labels[0];
            if (_devices.TryRemove(instanceName, out _))
            {
                NotifyDevicesUpdated();
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Error in ServiceInstanceShutdown: {ex.Message}");
        }
    }

    private void ProcessRecords(IEnumerable<ResourceRecord> records, IPEndPoint remote)
    {
        var list = records.ToList();
        bool updatedAny = false;
        var touchedInstances = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var a in list.OfType<AddressRecord>())
        {
            if (a.Address.AddressFamily == AddressFamily.InterNetwork)
            {
                string hostKey = a.Name.ToString().TrimEnd('.');
                _ipCache[hostKey] = a.Address;
            }
        }

        foreach (var ptr in list.OfType<PTRRecord>())
        {
            string sName = ptr.DomainName.ToString().TrimEnd('.');
            if (sName.Contains("_clipsync", StringComparison.OrdinalIgnoreCase))
            {
                string instanceName = ptr.DomainName.Labels[0];
                touchedInstances.Add(instanceName);
                var dev = _devices.GetOrAdd(instanceName, n => new DiscoveredDevice(n));
                dev.LastSeen = DateTime.UtcNow;
                if (sName.Contains("_clipsync-cli", StringComparison.OrdinalIgnoreCase) && dev.DeviceType == "unknown")
                {
                    dev.DeviceType = "android";
                }
                else if (sName.Contains("_clipsync", StringComparison.OrdinalIgnoreCase) && dev.DeviceType == "unknown")
                {
                    dev.DeviceType = "mac";
                }

                try
                {
                    _mdns.SendQuery(ptr.DomainName);
                }
                catch { }

                updatedAny = true;
            }
        }

        foreach (var srv in list.OfType<SRVRecord>())
        {
            string sName = srv.Name.ToString().TrimEnd('.');
            if (sName.Contains("_clipsync", StringComparison.OrdinalIgnoreCase))
            {
                string instanceName = srv.Name.Labels[0];
                touchedInstances.Add(instanceName);
                var dev = _devices.GetOrAdd(instanceName, n => new DiscoveredDevice(n));
                dev.Port = srv.Port;

                string target = srv.Target.ToString().TrimEnd('.');
                if (_ipCache.TryGetValue(target, out var ip))
                {
                    dev.Host = ip.ToString();
                }
                else if (remote.Address.AddressFamily == AddressFamily.InterNetwork)
                {
                    dev.Host = remote.Address.ToString();
                }
                else
                {
                    try
                    {
                        _mdns.SendQuery(srv.Target);
                    }
                    catch { }
                }

                updatedAny = true;
            }
        }

        foreach (var txt in list.OfType<TXTRecord>())
        {
            string sName = txt.Name.ToString().TrimEnd('.');
            if (sName.Contains("_clipsync", StringComparison.OrdinalIgnoreCase) ||
                sName.Contains("_device-info", StringComparison.OrdinalIgnoreCase))
            {
                string instanceName = txt.Name.Labels[0];
                touchedInstances.Add(instanceName);
                var dev = _devices.GetOrAdd(instanceName, n => new DiscoveredDevice(n));
                dev.LastSeen = DateTime.UtcNow;
                foreach (var str in txt.Strings)
                {
                    var parts = str.Split('=', 2);
                    if (parts.Length == 2)
                    {
                        if (parts[0] == "id") dev.Id = parts[1];
                        if (parts[0] == "device") dev.DeviceType = parts[1].ToLowerInvariant();
                        if (parts[0] == "model") dev.Model = parts[1];
                        if (parts[0] == "chip") dev.Chip = parts[1];
                        if (parts[0] == "ip" && !string.IsNullOrEmpty(parts[1])) dev.Host = parts[1];
                        if (parts[0] == "port" && int.TryParse(parts[1], out int p) && p > 0) dev.Port = p;
                    }
                }
                updatedAny = true;
            }
        }
        if (remote.Address.AddressFamily == AddressFamily.InterNetwork)
        {
            foreach (var instanceName in touchedInstances)
            {
                if (_devices.TryGetValue(instanceName, out var dev) && string.IsNullOrEmpty(dev.Host))
                {
                    dev.Host = remote.Address.ToString();
                    updatedAny = true;
                }
            }
        }

        foreach (var dev in _devices.Values)
        {
            if (dev.DeviceType == "unknown")
            {
                if (dev.Name.Contains("mac", StringComparison.OrdinalIgnoreCase))
                {
                    dev.DeviceType = "mac";
                }
                else if (dev.Name.Contains("android", StringComparison.OrdinalIgnoreCase) ||
                         dev.Name.Contains("pixel", StringComparison.OrdinalIgnoreCase) ||
                         dev.Name.Contains("galaxy", StringComparison.OrdinalIgnoreCase))
                {
                    dev.DeviceType = "android";
                }
            }
        }

        if (updatedAny)
        {
            NotifyDevicesUpdated();
        }
    }
    private CancellationTokenSource? _notifyDebounceCts;
    private readonly object _notifyLock = new();

    private void NotifyDevicesUpdated()
    {
        lock (_notifyLock)
        {
            _notifyDebounceCts?.Cancel();
            _notifyDebounceCts?.Dispose();
            _notifyDebounceCts = new CancellationTokenSource();
            var token = _notifyDebounceCts.Token;

            Task.Delay(150, token).ContinueWith(t =>
            {
                if (t.IsCanceled) return;
                DispatchDevicesUpdated();
            }, TaskScheduler.Default);
        }
    }

    private void DispatchDevicesUpdated()
    {
        var localSubnets = GetLocalSubnets();

        var validDevices = _devices.Values
            .Where(d => !string.IsNullOrEmpty(d.Host) && d.Port > 0 && !IsSelf(d.Name, d.Host, d.Id))
            .GroupBy(d => !string.IsNullOrEmpty(d.Id) ? d.Id : d.Name.Trim(), StringComparer.OrdinalIgnoreCase)
            .Select(g => g.OrderByDescending(d => IsOnSubnet(d.Host, localSubnets))
                          .ThenByDescending(d => d.LastSeen)
                          .First())
            .OrderBy(d => d.Name, StringComparer.CurrentCultureIgnoreCase)
            .ToList();

        DevicesUpdated?.Invoke(validDevices);
    }
    private bool IsSelf(string instanceName, string host, string id = "")
    {
        if (string.Equals(host, "127.0.0.1", StringComparison.OrdinalIgnoreCase) ||
            string.Equals(host, "::1", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        if (!string.IsNullOrEmpty(id) && !string.IsNullOrEmpty(_localDeviceId))
        {
            return string.Equals(id, _localDeviceId, StringComparison.OrdinalIgnoreCase);
        }

        string localMachine = Environment.MachineName;
        if (!string.IsNullOrEmpty(localMachine) &&
            instanceName.Contains(localMachine, StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }
        try
        {
            var localIps = NetworkInterface.GetAllNetworkInterfaces()
                .Where(nic => nic.OperationalStatus == OperationalStatus.Up &&
                              nic.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                .SelectMany(nic => nic.GetIPProperties().UnicastAddresses)
                .Where(u => u.Address.AddressFamily == AddressFamily.InterNetwork)
                .Select(u => u.Address.ToString())
                .ToHashSet();

            if (localIps.Contains(host))
            {
                return true;
            }
        }
        catch { }

        return false;
    }

    private static List<(IPAddress Address, IPAddress Mask)> GetLocalSubnets()
    {
        var list = new List<(IPAddress, IPAddress)>();
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up ||
                    nic.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                    continue;

                foreach (var u in nic.GetIPProperties().UnicastAddresses)
                {
                    if (u.Address.AddressFamily == AddressFamily.InterNetwork && u.IPv4Mask != null)
                    {
                        list.Add((u.Address, u.IPv4Mask));
                    }
                }
            }
        }
        catch { }
        return list;
    }

    private static bool IsOnSubnet(string host, List<(IPAddress Address, IPAddress Mask)> subnets)
    {
        if (!IPAddress.TryParse(host, out var ip) || ip.AddressFamily != AddressFamily.InterNetwork)
            return false;

        byte[] ipBytes = ip.GetAddressBytes();
        foreach (var (localIp, mask) in subnets)
        {
            byte[] localBytes = localIp.GetAddressBytes();
            byte[] maskBytes = mask.GetAddressBytes();
            bool match = true;
            for (int i = 0; i < 4; i++)
            {
                if ((ipBytes[i] & maskBytes[i]) != (localBytes[i] & maskBytes[i]))
                {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    public void Dispose()
    {
        Stop();
        lock (_notifyLock)
        {
            _notifyDebounceCts?.Cancel();
            _notifyDebounceCts?.Dispose();
            _notifyDebounceCts = null;
        }
        _sd.Dispose();
        _mdns.Dispose();
    }
}
