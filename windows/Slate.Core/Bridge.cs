namespace Slate.Core;

public enum LinkState
{
    Off,
    Searching,
    Connected,
}

public sealed record LinkStatus(string Transport, LinkState State, string Message, string? Device = null, int? RttMs = null);

/// <summary>
/// Shared hub for the transports: owns the <see cref="PenRouter"/>, lets exactly one tablet session
/// drive the pen at a time, and fans out mapping changes so sessions can resend CONFIG.
/// </summary>
public sealed class Bridge : IDisposable
{
    private readonly object _lock = new();
    private readonly Func<float> _aspect;
    private readonly Timer _watchdog;
    private object? _owner;

    public Bridge(PenRouter router, Func<float> aspect, string pcName)
    {
        Router = router;
        _aspect = aspect;
        PcName = pcName;
        _watchdog = new Timer(_ =>
        {
            if (Router.CheckWatchdog()) Log.Info("Watchdog: no pen data for 1 s, lifted the pen");
        }, null, 100, 100);
    }

    public PenRouter Router { get; }
    public string PcName { get; }

    /// <summary>Raised when the target mapping changes; active sessions resend CONFIG.</summary>
    public event Action? ConfigChanged;

    /// <summary>Raised from transport threads whenever a transport's state changes.</summary>
    public event Action<LinkStatus>? StatusChanged;

    public bool TryAcquire(object owner)
    {
        lock (_lock)
        {
            if (_owner is not null && !ReferenceEquals(_owner, owner)) return false;
            _owner = owner;
            return true;
        }
    }

    /// <summary>Gives up the session (if <paramref name="owner"/> holds it) and always lifts the pen first.</summary>
    public void Release(object owner)
    {
        lock (_lock)
        {
            if (!ReferenceEquals(_owner, owner)) return;
            Router.Release();
            _owner = null;
        }
    }

    public Packet MakeConfig() => new() { Type = PacketType.Config, Aspect = _aspect(), Name = PcName };

    public void NotifyConfigChanged() => ConfigChanged?.Invoke();

    public void Report(LinkStatus status) => StatusChanged?.Invoke(status);

    /// <summary>Handles the packets that mean the same thing on every transport.</summary>
    /// <returns>False when the tablet said BYE.</returns>
    public bool HandleSessionPacket(Packet p, Action<Packet> reply, Action<int>? onRtt = null)
    {
        switch (p.Type)
        {
            case PacketType.Pen:
                Router.OnPen(p.Pen);
                break;
            case PacketType.Leave:
                Router.OnLeave();
                break;
            case PacketType.Ping:
                reply(new Packet { Type = PacketType.Pong, EchoTimestamp = p.Timestamp });
                if (p.LastRttMs != Protocol.NoRtt) onRtt?.Invoke(p.LastRttMs);
                break;
            case PacketType.Bye:
                return false;
        }
        return true;
    }

    public void Dispose()
    {
        _watchdog.Dispose();
        Router.Release();
    }
}

/// <summary>Stamps outgoing packets with this side's sequence number and clock.</summary>
public sealed class PacketStamper
{
    private uint _seq;

    public Packet Stamp(Packet p) => p with
    {
        Seq = Interlocked.Increment(ref _seq) - 1,
        Timestamp = unchecked((uint)Environment.TickCount64),
    };
}
