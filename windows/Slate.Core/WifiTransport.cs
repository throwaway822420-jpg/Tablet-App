using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Slate.Core;

/// <summary>
/// Wi-Fi link: broadcasts DISCOVER, pairs a tablet by 4-digit code, then takes its UDP pen stream,
/// dropping stale or duplicate packets by sequence number.
/// </summary>
public sealed class WifiTransport : IDisposable
{
    public const string Name = "Wi-Fi";
    private const int SessionTimeoutMs = 5000;

    private readonly Bridge _bridge;
    private readonly Func<int> _pairCode;
    private readonly int _discoveryPort;
    private readonly Func<IReadOnlyList<IPAddress>> _broadcastTargets;
    private readonly UdpClient _socket;
    private readonly CancellationTokenSource _cts = new();
    private readonly Task _receiveLoop;
    private readonly Timer _tick;
    private readonly object _gate = new();
    private readonly PacketStamper _stamper = new();

    private IPEndPoint? _peer;
    private string _device = "";
    private uint _lastSeq;
    private long _lastRxMs;
    private long _lastDiscoverMs;
    private string _lastMessage = "";

    public WifiTransport(
        Bridge bridge,
        Func<int> pairCode,
        int dataPort = Protocol.WifiPort,
        int discoveryPort = Protocol.DiscoveryPort,
        Func<IReadOnlyList<IPAddress>>? broadcastTargets = null)
    {
        _bridge = bridge;
        _pairCode = pairCode;
        _discoveryPort = discoveryPort;
        _broadcastTargets = broadcastTargets ?? BroadcastAddresses;
        _socket = new UdpClient(AddressFamily.InterNetwork) { EnableBroadcast = true };
        _socket.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, false);
        _socket.Client.Bind(new IPEndPoint(IPAddress.Any, dataPort));
        IgnoreUdpConnectionReset(_socket.Client);
        LocalPort = ((IPEndPoint)_socket.Client.LocalEndPoint!).Port;

        _bridge.ConfigChanged += OnConfigChanged;
        _receiveLoop = Task.Run(() => ReceiveLoopAsync(_cts.Token));
        _tick = new Timer(_ => Tick(), null, 0, 250);
        Status(LinkState.Searching, "Waiting for a tablet on Wi-Fi");
    }

    public int LocalPort { get; }

    private void Status(LinkState state, string message, int? rtt = null)
    {
        if (state != LinkState.Connected && message == _lastMessage) return;
        if (message != _lastMessage) Log.Info($"Wi-Fi: {message}");
        _lastMessage = message;
        _bridge.Report(new LinkStatus(Name, state, message, state == LinkState.Connected ? _device : null, rtt));
    }

    private async Task ReceiveLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            UdpReceiveResult result;
            try
            {
                result = await _socket.ReceiveAsync(ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (SocketException)
            {
                continue;
            }

            try
            {
                Handle(result.Buffer, result.RemoteEndPoint);
            }
            catch (Exception ex)
            {
                Log.Info($"Wi-Fi: error handling packet: {ex}");
            }
        }
    }

    private void Handle(byte[] data, IPEndPoint from)
    {
        var p = Packet.Decode(data);
        if (p is null) return;
        if (p.Type == PacketType.Discover) return; // our own broadcast looping back

        if (p.Version != Protocol.Version)
        {
            SendTo(new Packet { Type = PacketType.Bye, Reason = ByeReason.VersionMismatch }, from);
            return;
        }

        if (p.Type == PacketType.Hello)
        {
            HandleHello(p, from);
            return;
        }

        lock (_gate)
        {
            if (_peer is null || !_peer.Equals(from))
            {
                // A tablet that still thinks it's paired (e.g. after this app restarted): tell it once per ping.
                if (p.Type == PacketType.Ping) SendTo(new Packet { Type = PacketType.Bye, Reason = ByeReason.Normal }, from);
                return;
            }
            if (!Protocol.IsNewer(p.Seq, _lastSeq)) return; // late or duplicate datagram
            _lastSeq = p.Seq;
            _lastRxMs = Environment.TickCount64;
        }

        if (!_bridge.HandleSessionPacket(p, reply => SendTo(reply, from), rtt => Status(LinkState.Connected, $"Connected to {_device} over Wi-Fi", rtt)))
            EndSession(from, "Tablet disconnected", sendBye: false);
    }

    private void HandleHello(Packet hello, IPEndPoint from)
    {
        if (hello.PairCode != _pairCode())
        {
            SendTo(new Packet { Type = PacketType.Bye, Reason = ByeReason.BadCode }, from);
            Status(_peer is null ? LinkState.Searching : LinkState.Connected, $"Wrong pairing code from {from.Address}");
            return;
        }

        IPEndPoint? previous;
        lock (_gate)
        {
            if (from.Equals(_peer))
            {
                // HELLO retry: our CONFIG was lost.
                _lastRxMs = Environment.TickCount64;
                SendTo(_bridge.MakeConfig(), from);
                return;
            }
            if (!_bridge.TryAcquire(this))
            {
                SendTo(new Packet { Type = PacketType.Bye, Reason = ByeReason.Busy }, from);
                Status(LinkState.Searching, "A tablet is already connected over USB");
                return;
            }
            previous = _peer;
            _bridge.Router.Release(); // a different tablet takes over: never leave the old one's stroke down
            _peer = from;
            _lastSeq = hello.Seq;
            _lastRxMs = Environment.TickCount64;
            _device = hello.Name.Length > 0 ? hello.Name : from.Address.ToString();
        }

        if (previous is not null) SendTo(new Packet { Type = PacketType.Bye, Reason = ByeReason.Normal }, previous);
        SendTo(_bridge.MakeConfig(), from);
        Status(LinkState.Connected, $"Connected to {_device} over Wi-Fi");
    }

    private void EndSession(IPEndPoint peer, string why, bool sendBye)
    {
        lock (_gate)
        {
            if (!peer.Equals(_peer)) return;
            _peer = null;
            _bridge.Release(this);
        }
        if (sendBye) SendTo(new Packet { Type = PacketType.Bye, Reason = ByeReason.Normal }, peer);
        Status(LinkState.Searching, $"{why}. Waiting for a tablet on Wi-Fi");
    }

    private void Tick()
    {
        if (_cts.IsCancellationRequested) return;
        long now = Environment.TickCount64;

        IPEndPoint? stale = null;
        lock (_gate)
        {
            if (_peer is not null && now - _lastRxMs > SessionTimeoutMs) stale = _peer;
        }
        if (stale is not null) EndSession(stale, $"Lost {_device} (no data for 5 s)", sendBye: true);

        if (now - _lastDiscoverMs >= 1000)
        {
            _lastDiscoverMs = now;
            var discover = new Packet { Type = PacketType.Discover, UdpPort = (ushort)LocalPort, Name = _bridge.PcName };
            foreach (var address in _broadcastTargets())
                SendTo(discover, new IPEndPoint(address, _discoveryPort));
        }
    }

    private void OnConfigChanged()
    {
        IPEndPoint? peer;
        lock (_gate) peer = _peer;
        if (peer is not null) SendTo(_bridge.MakeConfig(), peer);
    }

    private void SendTo(Packet p, IPEndPoint to)
    {
        try
        {
            _socket.Send(_stamper.Stamp(p).Encode(), Protocol.PacketSize, to);
        }
        catch (Exception ex) when (ex is SocketException or ObjectDisposedException)
        {
        }
    }

    /// <summary>255.255.255.255 plus each up IPv4 interface's directed broadcast (Windows only sends the former on one adapter).</summary>
    public static IReadOnlyList<IPAddress> BroadcastAddresses()
    {
        var list = new List<IPAddress> { IPAddress.Broadcast };
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                foreach (var ua in nic.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask is null) continue;
                    var ip = ua.Address.GetAddressBytes();
                    var mask = ua.IPv4Mask.GetAddressBytes();
                    if (mask.All(b => b == 0)) continue;
                    var b = new byte[4];
                    for (int i = 0; i < 4; i++) b[i] = (byte)(ip[i] | ~mask[i]);
                    var addr = new IPAddress(b);
                    if (!list.Contains(addr)) list.Add(addr);
                }
            }
        }
        catch (NetworkInformationException)
        {
        }
        return list;
    }

    /// <summary>
    /// On Windows an ICMP "port unreachable" makes the next ReceiveFrom on a UDP socket throw
    /// WSAECONNRESET; turn that off so one gone tablet can't disrupt the listener.
    /// </summary>
    private static void IgnoreUdpConnectionReset(Socket socket)
    {
        if (!OperatingSystem.IsWindows()) return;
        const int SIO_UDP_CONNRESET = unchecked((int)0x9800000C);
        try { socket.IOControl(SIO_UDP_CONNRESET, new byte[] { 0 }, null); } catch (SocketException) { }
    }

    public void Dispose()
    {
        _bridge.ConfigChanged -= OnConfigChanged;
        _cts.Cancel();
        _tick.Dispose();
        IPEndPoint? peer;
        lock (_gate) peer = _peer;
        if (peer is not null) EndSession(peer, "Stopped", sendBye: true);
        _socket.Dispose();
        try { _receiveLoop.Wait(2000); } catch (AggregateException) { }
        _bridge.Release(this);
        _cts.Dispose();
    }
}
