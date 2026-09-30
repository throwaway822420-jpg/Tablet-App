using Slate.Core;
using Xunit;

namespace Slate.Core.Tests;

public sealed class RecordingInjector : IPointerInjector
{
    private readonly List<InjectFrame> _frames = new();
    public List<(int X, int Y)> RightClicks { get; } = new();

    public List<InjectFrame> Frames { get { lock (_frames) return _frames.ToList(); } }

    public bool Inject(in InjectFrame frame)
    {
        lock (_frames) _frames.Add(frame);
        return true;
    }

    public void RightClick(int x, int y) => RightClicks.Add((x, y));

    public void Clear() { lock (_frames) _frames.Clear(); }
}

public class PenRouterTests
{
    private const PointerFlags Hover = PointerFlags.Update | PointerFlags.InRange;
    private const PointerFlags Down = PointerFlags.Down | PointerFlags.InRange | PointerFlags.InContact | PointerFlags.FirstButton;
    private const PointerFlags Drag = PointerFlags.Update | PointerFlags.InRange | PointerFlags.InContact | PointerFlags.FirstButton;
    private const PointerFlags UpHover = PointerFlags.Up | PointerFlags.InRange;
    private const PointerFlags Gone = PointerFlags.Update;

    private readonly RecordingInjector _inj = new();
    private long _now;
    private BarrelMode _barrel = BarrelMode.RightClick;
    private readonly PenRouter _router;

    public PenRouterTests()
    {
        _router = new PenRouter(_inj, () => new RouterOptions(new PixelRect(0, 0, 1001, 501), _barrel, 1.0), () => _now);
    }

    private static PenSample Hovering(float x = 0.5f, float y = 0.5f, bool barrel = false, PenTool tool = PenTool.Pen) =>
        new(tool, PenFlags.InRange | (barrel ? PenFlags.Barrel : 0), 0, x, y, 0, 0, 0);

    private static PenSample Touching(ushort pressure, float x = 0.5f, float y = 0.5f, PenTool tool = PenTool.Pen, bool barrel = false) =>
        new(tool, PenFlags.InRange | PenFlags.Contact | PenFlags.HasTilt | (barrel ? PenFlags.Barrel : 0), pressure, x, y, 10, -20, 0);

    private PointerFlags[] Flags() => _inj.Frames.Select(f => f.Flags).ToArray();

    [Fact]
    public void FullStrokeProducesWindowsPenSequence()
    {
        _router.OnPen(Hovering(0, 0));
        _router.OnPen(Touching(100, 0.5f, 0.5f));
        _router.OnPen(Touching(900, 1f, 1f));
        _router.OnPen(Hovering(1f, 1f));
        _router.OnLeave();

        Assert.Equal(new[] { Hover, Down, Drag, UpHover, Gone }, Flags());
        var f = _inj.Frames;
        Assert.Equal((0, 0), (f[0].X, f[0].Y));
        Assert.Equal((500, 250), (f[1].X, f[1].Y));
        Assert.Equal((1000, 500), (f[2].X, f[2].Y));
        Assert.Equal(new uint[] { 0, 100, 900, 0, 0 }, f.Select(x => x.Pressure).ToArray());
        Assert.Equal(ButtonChange.FirstDown, f[1].Change);
        Assert.Equal(ButtonChange.FirstUp, f[3].Change);
        Assert.Equal(PenMask.Pressure | PenMask.TiltX | PenMask.TiltY, f[1].Mask);
        Assert.Equal((10, -20), (f[1].TiltX, f[1].TiltY));
        Assert.Equal(PenMask.Pressure, f[0].Mask); // hover sample didn't claim tilt support
    }

    [Fact]
    public void TouchWithoutPriorHoverEntersRangeFirst()
    {
        _router.OnPen(Touching(300));
        Assert.Equal(new[] { Hover, Down }, Flags());
    }

    [Fact]
    public void ReleaseWhileDownLiftsThenLeaves()
    {
        _router.OnPen(Touching(300));
        _inj.Clear();
        _router.Release();
        Assert.Equal(new[] { UpHover, Gone }, Flags());
        Assert.False(_router.IsDown);
        Assert.False(_router.IsInRange);

        _inj.Clear();
        _router.Release();
        Assert.Empty(_inj.Frames); // idempotent
    }

    [Fact]
    public void LeaveWhileDownLiftsThenLeaves()
    {
        _router.OnPen(Touching(300));
        _inj.Clear();
        _router.OnLeave();
        Assert.Equal(new[] { UpHover, Gone }, Flags());
    }

    [Fact]
    public void LiftStraightOutOfRange()
    {
        _router.OnPen(Touching(300));
        _inj.Clear();
        _router.OnPen(new PenSample(PenTool.Pen, PenFlags.None, 0, 0.5f, 0.5f, 0, 0, 0));
        Assert.Equal(new[] { PointerFlags.Up, Gone }, Flags());
    }

    [Fact]
    public void WatchdogLiftsPenAfterSilence()
    {
        _router.OnPen(Touching(300));
        _now += PenRouter.WatchdogMs;
        Assert.False(_router.CheckWatchdog());
        _now += 1;
        Assert.True(_router.CheckWatchdog());
        Assert.False(_router.IsDown);
        Assert.False(_router.CheckWatchdog());
    }

    [Fact]
    public void EraserTipIsInvertedAndErasesOnContact()
    {
        _router.OnPen(Hovering(tool: PenTool.Eraser));
        _router.OnPen(Touching(500, tool: PenTool.Eraser));
        var f = _inj.Frames;
        Assert.Equal(PenInfoFlags.Inverted, f[0].PenFlags);
        Assert.Equal(PenInfoFlags.Inverted | PenInfoFlags.Eraser, f[1].PenFlags);
    }

    [Fact]
    public void SwitchingToolWhileHoveringLeavesRangeFirst()
    {
        _router.OnPen(Hovering());
        _router.OnPen(Hovering(tool: PenTool.Eraser));
        Assert.Equal(new[] { Hover, Gone, Hover }, Flags());
        Assert.Equal(PenInfoFlags.Inverted, _inj.Frames[2].PenFlags);
    }

    [Fact]
    public void BarrelWhileHoveringRightClicksOnce()
    {
        _router.OnPen(Hovering(0.2f, 0.2f));
        _router.OnPen(Hovering(0.2f, 0.2f, barrel: true));
        _router.OnPen(Hovering(0.2f, 0.2f, barrel: true));
        _router.OnPen(Hovering(0.2f, 0.2f));
        Assert.Equal(new[] { (200, 100) }, _inj.RightClicks);
    }

    [Fact]
    public void BarrelWhileDrawingSetsNativeBarrel()
    {
        _router.OnPen(Touching(500, barrel: true));
        var f = _inj.Frames.Last();
        Assert.True(f.PenFlags.HasFlag(PenInfoFlags.Barrel));
        Assert.True(f.Flags.HasFlag(PointerFlags.SecondButton));
        Assert.Empty(_inj.RightClicks);
    }

    [Fact]
    public void BarrelInEraserModeErases()
    {
        _barrel = BarrelMode.Eraser;
        _router.OnPen(Touching(500, barrel: true));
        Assert.Equal(PenInfoFlags.Inverted | PenInfoFlags.Eraser, _inj.Frames.Last().PenFlags);
        Assert.Empty(_inj.RightClicks);
    }

    [Fact]
    public void DisabledBarrelIsIgnored()
    {
        _barrel = BarrelMode.Disabled;
        _router.OnPen(Hovering(barrel: true));
        _router.OnPen(Touching(500, barrel: true));
        Assert.All(_inj.Frames, f => Assert.Equal(PenInfoFlags.None, f.PenFlags));
        Assert.Empty(_inj.RightClicks);
    }
}
