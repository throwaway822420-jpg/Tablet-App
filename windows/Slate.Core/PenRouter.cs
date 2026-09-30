namespace Slate.Core;

public sealed record RouterOptions(PixelRect Target, BarrelMode Barrel, double PressureGamma);

/// <summary>
/// Turns the tablet's pen states into the pointer frame sequence Windows expects
/// (hover UPDATE → DOWN → UPDATE… → UP → hover UPDATE → leave UPDATE), and guarantees the pen is
/// never left down: <see cref="Release"/> and the watchdog always lift it and take it out of range.
/// Thread-safe; every call is serialized on one lock so frames reach Windows in order.
/// </summary>
public sealed class PenRouter
{
    public const int WatchdogMs = 1000;

    private readonly IPointerInjector _injector;
    private readonly Func<RouterOptions> _options;
    private readonly Func<long> _clockMs;
    private readonly object _lock = new();

    private bool _inRange;
    private bool _inContact;
    private bool _inverted;
    private bool _barrelHeld;
    private int _x, _y;
    private long _lastInputMs;

    public PenRouter(IPointerInjector injector, Func<RouterOptions> options, Func<long>? clockMs = null)
    {
        _injector = injector;
        _options = options;
        _clockMs = clockMs ?? (() => Environment.TickCount64);
    }

    public bool IsDown { get { lock (_lock) return _inContact; } }
    public bool IsInRange { get { lock (_lock) return _inRange; } }

    /// <summary>Raised (outside the lock) with each frame the OS rejected.</summary>
    public event Action<InjectFrame>? InjectFailed;

    public void OnPen(in PenSample s)
    {
        var opt = _options();
        List<InjectFrame>? failed = null;
        bool rightClick = false;
        int clickX, clickY;

        lock (_lock)
        {
            _lastInputMs = _clockMs();
            var (x, y) = Mapping.ToPixels(s.X, s.Y, opt.Target);

            bool contact = s.Contact;
            bool inRange = s.InRange;
            bool barrel = s.Barrel && opt.Barrel != BarrelMode.Disabled;
            bool inverted = s.Tool == PenTool.Eraser || (barrel && opt.Barrel == BarrelMode.Eraser);
            bool nativeBarrel = barrel && opt.Barrel == BarrelMode.RightClick;

            // Barrel pressed while hovering: Windows has no native gesture for that, so click.
            if (opt.Barrel == BarrelMode.RightClick && barrel && !_barrelHeld && !contact && inRange)
                rightClick = true;
            _barrelHeld = barrel;

            // Switching between tip and eraser mid-hover: take the pen out of range first so apps
            // pick up the new tool, as they would with a real pen being flipped over.
            if (_inRange && inverted != _inverted && !_inContact)
                LeaveLocked(ref failed);
            if (!_inContact) _inverted = inverted;

            var penFlags = PenInfoFlags.None;
            if (_inverted) penFlags |= PenInfoFlags.Inverted;
            if (_inverted && contact) penFlags |= PenInfoFlags.Eraser;
            if (nativeBarrel) penFlags |= PenInfoFlags.Barrel;

            var mask = PenMask.Pressure;
            if ((s.Flags & PenFlags.HasTilt) != 0) mask |= PenMask.TiltX | PenMask.TiltY;
            if ((s.Flags & PenFlags.HasRotation) != 0) mask |= PenMask.Rotation;

            uint pressure = contact ? PressureCurve.Apply(s.Pressure, opt.PressureGamma) : 0;
            var baseFrame = new InjectFrame(
                x, y, PointerFlags.None, penFlags, mask, pressure,
                Math.Clamp((int)s.TiltX, -90, 90), Math.Clamp((int)s.TiltY, -90, 90),
                (uint)(s.Rotation % 360), ButtonChange.None);
            var second = nativeBarrel ? PointerFlags.SecondButton : PointerFlags.None;

            if (contact)
            {
                if (!_inContact)
                {
                    if (!_inRange)
                        Emit(baseFrame with { Flags = PointerFlags.Update | PointerFlags.InRange | second, Pressure = 0, PenFlags = penFlags & ~PenInfoFlags.Eraser }, ref failed);
                    Emit(baseFrame with
                    {
                        Flags = PointerFlags.Down | PointerFlags.InRange | PointerFlags.InContact | PointerFlags.FirstButton | second,
                        Change = ButtonChange.FirstDown,
                    }, ref failed);
                }
                else
                {
                    Emit(baseFrame with { Flags = PointerFlags.Update | PointerFlags.InRange | PointerFlags.InContact | PointerFlags.FirstButton | second }, ref failed);
                }
                _inContact = true;
                _inRange = true;
            }
            else
            {
                if (_inContact)
                {
                    Emit(baseFrame with { Flags = PointerFlags.Up | (inRange ? PointerFlags.InRange : 0) | second, Change = ButtonChange.FirstUp }, ref failed);
                    _inContact = false;
                    _inRange = inRange;
                    if (!inRange) Emit(baseFrame with { Flags = PointerFlags.Update }, ref failed);
                    _inverted = inverted;
                }
                else if (inRange)
                {
                    Emit(baseFrame with { Flags = PointerFlags.Update | PointerFlags.InRange | second }, ref failed);
                    _inRange = true;
                }
                else if (_inRange)
                {
                    Emit(baseFrame with { Flags = PointerFlags.Update }, ref failed);
                    _inRange = false;
                }
            }

            _x = x;
            _y = y;
            clickX = x;
            clickY = y;
        }

        if (rightClick) _injector.RightClick(clickX, clickY);
        Report(failed);
    }

    /// <summary>The pen left hover range on the tablet.</summary>
    public void OnLeave()
    {
        List<InjectFrame>? failed = null;
        lock (_lock)
        {
            _lastInputMs = _clockMs();
            LeaveLocked(ref failed);
        }
        Report(failed);
    }

    /// <summary>Lift the pen and take it out of range. Safe to call at any time, e.g. on socket loss.</summary>
    public void Release()
    {
        List<InjectFrame>? failed = null;
        lock (_lock) LeaveLocked(ref failed);
        Report(failed);
    }

    /// <summary>Call periodically. Releases the pen if it's in range but the tablet has gone quiet.</summary>
    /// <returns>True if the watchdog fired.</returns>
    public bool CheckWatchdog()
    {
        List<InjectFrame>? failed = null;
        bool fired = false;
        lock (_lock)
        {
            if ((_inRange || _inContact) && _clockMs() - _lastInputMs > WatchdogMs)
            {
                LeaveLocked(ref failed);
                fired = true;
            }
        }
        Report(failed);
        return fired;
    }

    private void LeaveLocked(ref List<InjectFrame>? failed)
    {
        var penFlags = _inverted ? PenInfoFlags.Inverted : PenInfoFlags.None;
        var frame = new InjectFrame(_x, _y, PointerFlags.None, penFlags, PenMask.Pressure, 0, 0, 0, 0, ButtonChange.None);
        if (_inContact)
        {
            Emit(frame with { Flags = PointerFlags.Up | PointerFlags.InRange, Change = ButtonChange.FirstUp }, ref failed);
            _inContact = false;
            _inRange = true;
        }
        if (_inRange)
        {
            Emit(frame with { Flags = PointerFlags.Update }, ref failed);
            _inRange = false;
        }
        _barrelHeld = false;
    }

    private void Emit(in InjectFrame frame, ref List<InjectFrame>? failed)
    {
        if (!_injector.Inject(frame)) (failed ??= new()).Add(frame);
    }

    private void Report(List<InjectFrame>? failed)
    {
        if (failed is null) return;
        foreach (var f in failed) InjectFailed?.Invoke(f);
    }
}
