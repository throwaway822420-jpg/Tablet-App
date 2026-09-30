using System.Net;
using System.Net.Sockets;
using Slate.Core;
using Xunit;

namespace Slate.Core.Tests;

/// <summary>End-to-end over real loopback sockets, with the test playing the tablet.</summary>
public class TransportTests
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(10);

    private readonly RecordingInjector _inj = new();
    private readonly PenRouter _router;
    private readonly Bridge _bridge;
    private readonly RecordingTyper _typer = new();

    private sealed class RecordingTyper : ITextTyper
    {
        public List<string> Typed { get; } = new();
        public void Type(string text) { lock (Typed) Typed.Add(text); }
    }

    public TransportTests()
    {
        _router = new PenRouter(_inj, () => new RouterOptions(new PixelRect(0, 0, 1000, 500), BarrelMode.RightClick, 1.0));
        _bridge = new Bridge(_router, () => 2f, "TESTPC", _typer);
    }

    private static PenSample Touch(float x) => new(PenTool.Pen, PenFlags.InRange | PenFlags.Contact, 512, x, 0.5f, 0, 0, 0);

    private static async Task<Packet> ReadPacketAsync(NetworkStream s)
    {
        var buf = new byte[Protocol.PacketSize];
        using var cts = new CancellationTokenSource(Wait);
        await s.ReadExactlyAsync(buf, cts.Token);
        return Packet.Decode(buf)!;
    }

    private static async Task Eventually(Func<bool> condition)
    {
        var deadline = DateTime.UtcNow + Wait;
        while (!condition())
        {
            if (DateTime.UtcNow > deadline) throw new TimeoutException("condition not met");
            await Task.Delay(20);
        }
    }

    [Fact]
    public async Task Usb_HelloConfigPenAndReleaseOnSocketLoss()
    {
        var tablet = new TcpListener(IPAddress.Loopback, 0);
        tablet.Start();
        int port = ((IPEndPoint)tablet.LocalEndpoint).Port;
        using var usb = new UsbTransport(_bridge, () => null, useAdb: false, port: port);

        using (var conn = await tablet.AcceptTcpClientAsync())
        {
            var s = conn.GetStream();
            await s.WriteAsync(new Packet { Type = PacketType.Hello, Name = "TAB", SurfaceWidth = 2560, SurfaceHeight = 1600 }.Encode());

            var config = await ReadPacketAsync(s);
            Assert.Equal(PacketType.Config, config.Type);
            Assert.Equal(2f, config.Aspect);
            Assert.Equal("TESTPC", config.Name);

            await s.WriteAsync(new Packet { Type = PacketType.Ping, Seq = 1, Timestamp = 1234 }.Encode());
            var pong = await ReadPacketAsync(s);
            Assert.Equal(PacketType.Pong, pong.Type);
            Assert.Equal(1234u, pong.EchoTimestamp);

            await s.WriteAsync(new Packet { Type = PacketType.Pen, Seq = 2, Pen = Touch(0.5f) }.Encode());
            await Eventually(() => _router.IsDown);

            // Handwriting result: two chunks, typed once, acked each time it's sent.
            for (int round = 0; round < 2; round++)
            {
                await s.WriteAsync(new Packet { Type = PacketType.Text, TextId = 42, ChunkIndex = 0, ChunkCount = 2, Chunk = "E = mc² is " }.Encode());
                await s.WriteAsync(new Packet { Type = PacketType.Text, TextId = 42, ChunkIndex = 1, ChunkCount = 2, Chunk = "famous" }.Encode());
                // First round: one ack when the message completes. Resend: each chunk of a
                // completed message is acked again, so two.
                for (int acks = round == 0 ? 1 : 2; acks > 0; acks--)
                {
                    var ack = await ReadPacketAsync(s);
                    Assert.Equal(PacketType.TextAck, ack.Type);
                    Assert.Equal(42, ack.TextId);
                }
            }
            Assert.Equal(new[] { "E = mc² is famous" }, _typer.Typed);
            Assert.False(_router.IsDown); // typing lifted the pen first
            await s.WriteAsync(new Packet { Type = PacketType.Pen, Seq = 3, Pen = Touch(0.5f) }.Encode());
            await Eventually(() => _router.IsDown);

            // Mapping change is pushed to the tablet.
            _bridge.NotifyConfigChanged();
            Assert.Equal(PacketType.Config, (await ReadPacketAsync(s)).Type);
        } // tablet vanishes mid-stroke

        await Eventually(() => !_router.IsDown && !_router.IsInRange);
        Assert.Equal(PointerFlags.Up | PointerFlags.InRange, _inj.Frames[^2].Flags);
        Assert.Equal(PointerFlags.Update, _inj.Frames[^1].Flags);

        // It reconnects when the tablet comes back.
        using var again = await tablet.AcceptTcpClientAsync().WaitAsync(Wait);
        tablet.Stop();
    }

    [Fact]
    public async Task Usb_TabletAppNotRunningTimesOutWithoutHello()
    {
        var tablet = new TcpListener(IPAddress.Loopback, 0);
        tablet.Start();
        int port = ((IPEndPoint)tablet.LocalEndpoint).Port;
        var statuses = new List<LinkStatus>();
        _bridge.StatusChanged += s => { lock (statuses) statuses.Add(s); };
        using var usb = new UsbTransport(_bridge, () => null, useAdb: false, port: port);

        using var silent = await tablet.AcceptTcpClientAsync();
        await Eventually(() => { lock (statuses) return statuses.Any(s => s.Message.Contains("Open Slate")); });
        tablet.Stop();
    }

    [Fact]
    public async Task Wifi_DiscoveryPairingSequencingAndTimeout()
    {
        using var tablet = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        int discoveryPort = ((IPEndPoint)tablet.Client.LocalEndPoint!).Port;
        using var wifi = new WifiTransport(_bridge, () => 1234, dataPort: 0, discoveryPort: discoveryPort,
            broadcastTargets: () => new[] { IPAddress.Loopback });

        async Task<Packet> Receive()
        {
            using var cts = new CancellationTokenSource(Wait);
            while (true)
            {
                var r = await tablet.ReceiveAsync(cts.Token);
                var p = Packet.Decode(r.Buffer)!;
                if (p.Type != PacketType.Discover) return p;
            }
        }

        // Discovery tells us where to send.
        Packet discover;
        using (var cts = new CancellationTokenSource(Wait))
            discover = Packet.Decode((await tablet.ReceiveAsync(cts.Token)).Buffer)!;
        Assert.Equal(PacketType.Discover, discover.Type);
        Assert.Equal("TESTPC", discover.Name);
        var pc = new IPEndPoint(IPAddress.Loopback, discover.UdpPort);
        Task Send(Packet p) => tablet.SendAsync(p.Encode(), Protocol.PacketSize, pc);

        await Send(new Packet { Type = PacketType.Hello, Seq = 0, PairCode = 1111 });
        Assert.Equal(ByeReason.BadCode, (await Receive()).Reason);

        // Pen data from an unpaired tablet is ignored.
        await Send(new Packet { Type = PacketType.Pen, Seq = 1, Pen = Touch(0.1f) });

        await Send(new Packet { Type = PacketType.Hello, Seq = 10, PairCode = 1234, Name = "TAB" });
        var config = await Receive();
        Assert.Equal(PacketType.Config, config.Type);
        Assert.False(_router.IsDown);

        await Send(new Packet { Type = PacketType.Pen, Seq = 12, Pen = Touch(0.2f) });
        await Eventually(() => _router.IsDown);
        int frames = _inj.Frames.Count;

        // Late datagram (older seq) is dropped.
        await Send(new Packet { Type = PacketType.Pen, Seq = 11, Pen = Touch(0.9f) });
        await Send(new Packet { Type = PacketType.Ping, Seq = 13, Timestamp = 55 });
        Assert.Equal(55u, (await Receive()).EchoTimestamp);
        Assert.Equal(frames, _inj.Frames.Count);
        Assert.Equal(200, _inj.Frames.Last().X);

        // Going silent ends the session and lifts the pen (watchdog at 1 s, session timeout at 5 s).
        await Eventually(() => !_router.IsDown);
        var bye = await Receive();
        Assert.Equal(PacketType.Bye, bye.Type);
        Assert.Equal(ByeReason.Normal, bye.Reason);
    }

    [Fact]
    public async Task TabletMovingFromWifiToUsbIsHandedOver()
    {
        using var udp = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        using var wifi = new WifiTransport(_bridge, () => 7, dataPort: 0, discoveryPort: 9, broadcastTargets: Array.Empty<IPAddress>);
        var pc = new IPEndPoint(IPAddress.Loopback, wifi.LocalPort);
        await udp.SendAsync(new Packet { Type = PacketType.Hello, PairCode = 7 }.Encode(), Protocol.PacketSize, pc);
        using (var cts = new CancellationTokenSource(Wait))
            Assert.Equal(PacketType.Config, Packet.Decode((await udp.ReceiveAsync(cts.Token)).Buffer)!.Type);

        var tablet = new TcpListener(IPAddress.Loopback, 0);
        tablet.Start();
        using var usb = new UsbTransport(_bridge, () => null, useAdb: false, port: ((IPEndPoint)tablet.LocalEndpoint).Port);
        using var conn = await tablet.AcceptTcpClientAsync();
        var s = conn.GetStream();
        await s.WriteAsync(new Packet { Type = PacketType.Hello }.Encode());
        await Task.Delay(300); // USB HELLO lands while Wi-Fi still holds the session…
        await udp.SendAsync(new Packet { Type = PacketType.Bye, Seq = 1 }.Encode(), Protocol.PacketSize, pc); // …then Wi-Fi lets go

        Assert.Equal(PacketType.Config, (await ReadPacketAsync(s)).Type);
        tablet.Stop();
    }

    [Fact]
    public async Task Wifi_RefusesTabletWhileUsbHoldsSession()
    {
        Assert.True(_bridge.TryAcquire("usb"));
        using var tablet = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        using var wifi = new WifiTransport(_bridge, () => 1, dataPort: 0, discoveryPort: 9, broadcastTargets: Array.Empty<IPAddress>);
        await tablet.SendAsync(new Packet { Type = PacketType.Hello, PairCode = 1 }.Encode(), Protocol.PacketSize,
            new IPEndPoint(IPAddress.Loopback, wifi.LocalPort));
        using var cts = new CancellationTokenSource(Wait);
        var reply = Packet.Decode((await tablet.ReceiveAsync(cts.Token)).Buffer)!;
        Assert.Equal(ByeReason.Busy, reply.Reason);
    }
}
