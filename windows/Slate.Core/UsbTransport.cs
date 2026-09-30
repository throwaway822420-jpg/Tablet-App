using System.Net.Sockets;

namespace Slate.Core;

/// <summary>
/// USB link: keeps an <c>adb forward</c> to the tablet and connects to the Slate app's TCP listener
/// through it, reconnecting forever until disposed.
/// </summary>
public sealed class UsbTransport : IDisposable
{
    public const string Name = "USB";
    private const int HelloTimeoutMs = 3000;
    private const int IdleTimeoutMs = 5000;

    private readonly Bridge _bridge;
    private readonly Func<string?> _adbPath;
    private readonly bool _useAdb;
    private readonly string _host;
    private readonly int _port;
    private readonly CancellationTokenSource _cts = new();
    private readonly Task _loop;
    private string _lastMessage = "";

    /// <param name="useAdb">False connects straight to host:port (tests, or a tablet reachable over TCP).</param>
    public UsbTransport(Bridge bridge, Func<string?> adbPath, bool useAdb = true, string host = "127.0.0.1", int port = Protocol.UsbPort)
    {
        _bridge = bridge;
        _adbPath = adbPath;
        _useAdb = useAdb;
        _host = host;
        _port = port;
        _loop = Task.Run(() => RunAsync(_cts.Token));
    }

    private void Status(LinkState state, string message, string? device = null, int? rtt = null)
    {
        if (state != LinkState.Connected && message == _lastMessage) return;
        if (message != _lastMessage) Log.Info($"USB: {message}");
        _lastMessage = message;
        _bridge.Report(new LinkStatus(Name, state, message, device, rtt));
    }

    private async Task RunAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (_useAdb && !await PrepareAdbAsync(ct))
                {
                    await Task.Delay(2000, ct);
                    continue;
                }

                using var client = new TcpClient { NoDelay = true };
                using (var connectCts = CancellationTokenSource.CreateLinkedTokenSource(ct))
                {
                    connectCts.CancelAfter(1500);
                    await client.ConnectAsync(_host, _port, connectCts.Token);
                }
                await Task.Run(() => RunSession(client, ct), ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex) when (ex is SocketException or IOException or OperationCanceledException or ObjectDisposedException)
            {
                // Nothing listening yet; retry below.
            }
            catch (Exception ex)
            {
                Log.Info($"USB: unexpected error: {ex}");
            }

            try { await Task.Delay(1000, ct); } catch (OperationCanceledException) { break; }
        }
    }

    private async Task<bool> PrepareAdbAsync(CancellationToken ct)
    {
        var adb = Adb.Locate(_adbPath());
        if (adb is null)
        {
            Status(LinkState.Searching, "adb not found. Put Android platform-tools next to Slate.exe or on PATH.");
            return false;
        }

        IReadOnlyList<AdbDevice> devices;
        try
        {
            devices = await adb.DevicesAsync(ct);
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            Status(LinkState.Searching, $"Couldn't run adb: {ex.Message}");
            return false;
        }

        var ready = devices.FirstOrDefault(d => d.IsReady);
        if (ready is null)
        {
            if (devices.Any(d => d.State == "unauthorized"))
                Status(LinkState.Searching, "Tablet found but not authorized. Accept the USB debugging prompt on the tablet.");
            else if (devices.Count > 0)
                Status(LinkState.Searching, $"Tablet is '{devices[0].State}'. Try reconnecting the cable.");
            else
                Status(LinkState.Searching, "No tablet on USB. Plug it in and turn on USB debugging.");
            return false;
        }

        if (!await adb.ForwardAsync(ready.Serial, _port, ct))
        {
            Status(LinkState.Searching, "adb forward failed; see log.");
            return false;
        }
        // The bulk channel (screen mirroring, Claude) runs the other way: the tablet connects to the PC.
        await adb.ReverseAsync(ready.Serial, BulkFrame.Port, ct);
        return true;
    }

    private void RunSession(TcpClient client, CancellationToken ct)
    {
        using var closeOnCancel = ct.Register(client.Close);
        var stream = client.GetStream();
        var buf = new byte[Protocol.PacketSize];
        var stamper = new PacketStamper();
        var writeLock = new object();

        void Send(Packet p)
        {
            var bytes = stamper.Stamp(p).Encode();
            lock (writeLock) stream.Write(bytes);
        }

        // adb accepts the local connection even when nothing listens on the tablet; a missing HELLO
        // (or an immediate close) means the Slate app isn't running there.
        client.ReceiveTimeout = HelloTimeoutMs;
        Packet? hello;
        try
        {
            stream.ReadExactly(buf);
            hello = Packet.Decode(buf);
        }
        catch (Exception ex) when (ex is IOException or EndOfStreamException)
        {
            Status(LinkState.Searching, "Tablet connected. Open Slate on the tablet.");
            return;
        }

        if (hello is null || hello.Type != PacketType.Hello) return;
        if (hello.Version != Protocol.Version)
        {
            Send(new Packet { Type = PacketType.Bye, Reason = ByeReason.VersionMismatch });
            Status(LinkState.Searching, $"Tablet app speaks protocol v{hello.Version}, this PC app v{Protocol.Version}. Update both.");
            return;
        }
        // A tablet moving from Wi-Fi to USB sends BYE on Wi-Fi first; give that a moment to land.
        bool acquired = _bridge.TryAcquire(this);
        for (int i = 0; !acquired && i < 10 && !ct.IsCancellationRequested; i++)
        {
            Thread.Sleep(100);
            acquired = _bridge.TryAcquire(this);
        }
        if (!acquired)
        {
            Send(new Packet { Type = PacketType.Bye, Reason = ByeReason.Busy });
            Status(LinkState.Searching, "Another tablet is connected over Wi-Fi.");
            return;
        }

        void OnConfigChanged()
        {
            try { Send(_bridge.MakeConfig()); } catch (Exception ex) when (ex is IOException or ObjectDisposedException) { }
        }

        string device = hello.Name.Length > 0 ? hello.Name : "tablet";
        _bridge.ConfigChanged += OnConfigChanged;
        try
        {
            Send(_bridge.MakeConfig());
            Status(LinkState.Connected, $"Connected to {device} over USB", device);
            client.ReceiveTimeout = IdleTimeoutMs;
            while (!ct.IsCancellationRequested)
            {
                stream.ReadExactly(buf);
                var p = Packet.Decode(buf);
                if (p is null) continue;
                if (!_bridge.HandleSessionPacket(p, Send, rtt => Status(LinkState.Connected, $"Connected to {device} over USB", device, rtt)))
                    break;
            }
        }
        catch (Exception ex) when (ex is IOException or EndOfStreamException or ObjectDisposedException or SocketException)
        {
        }
        finally
        {
            _bridge.ConfigChanged -= OnConfigChanged;
            _bridge.Release(this);
            _lastMessage = "";
            Status(LinkState.Searching, $"Disconnected from {device}. Reconnecting…");
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _loop.Wait(3000); } catch (AggregateException) { }
        _bridge.Release(this);
        if (_useAdb && Adb.Locate(_adbPath()) is { } adb)
        {
            try { adb.RemoveForwardAsync(_port, CancellationToken.None).Wait(3000); } catch (Exception) { }
        }
        _cts.Dispose();
    }
}
