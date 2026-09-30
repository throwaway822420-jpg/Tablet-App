namespace Slate.Core;

// Values mirror the Win32 constants in WinUser.h so the native layer can copy them straight
// into POINTER_INFO / POINTER_PEN_INFO.

[Flags]
public enum PointerFlags : uint
{
    None = 0x00000000,
    New = 0x00000001,
    InRange = 0x00000002,
    InContact = 0x00000004,
    FirstButton = 0x00000010,
    SecondButton = 0x00000020,
    Primary = 0x00002000,
    Confidence = 0x00004000,
    Canceled = 0x00008000,
    Down = 0x00010000,
    Update = 0x00020000,
    Up = 0x00040000,
}

[Flags]
public enum PenInfoFlags : uint
{
    None = 0,
    Barrel = 0x1,
    Inverted = 0x2,
    Eraser = 0x4,
}

[Flags]
public enum PenMask : uint
{
    None = 0,
    Pressure = 0x1,
    Rotation = 0x2,
    TiltX = 0x4,
    TiltY = 0x8,
}

public enum ButtonChange : uint
{
    None = 0,
    FirstDown = 1,
    FirstUp = 2,
    SecondDown = 3,
    SecondUp = 4,
}

/// <summary>One call's worth of data for InjectSyntheticPointerInput with a PT_PEN device.</summary>
public readonly record struct InjectFrame(
    int X,
    int Y,
    PointerFlags Flags,
    PenInfoFlags PenFlags,
    PenMask Mask,
    uint Pressure,
    int TiltX,
    int TiltY,
    uint Rotation,
    ButtonChange Change);

public interface IPointerInjector
{
    /// <returns>False if the OS rejected the frame.</returns>
    bool Inject(in InjectFrame frame);

    /// <summary>Right-click at a physical-pixel screen position (used for the barrel button while hovering).</summary>
    void RightClick(int x, int y);
}

public readonly record struct PixelRect(int X, int Y, int Width, int Height)
{
    public int Right => X + Width;
    public int Bottom => Y + Height;
    public double Aspect => Height > 0 ? (double)Width / Height : 0;
}

public enum BarrelMode
{
    /// <summary>Sends the native barrel flag while drawing (Windows turns barrel + tap into a right-click) and right-clicks when pressed while hovering.</summary>
    RightClick,

    /// <summary>The pen acts as an eraser while the button is held.</summary>
    Eraser,

    /// <summary>The button is ignored.</summary>
    Disabled,
}

public static class Mapping
{
    /// <summary>Maps normalized 0..1 coordinates onto a physical-pixel rectangle.</summary>
    public static (int X, int Y) ToPixels(float nx, float ny, PixelRect target)
    {
        double cx = Math.Clamp(float.IsFinite(nx) ? nx : 0f, 0f, 1f);
        double cy = Math.Clamp(float.IsFinite(ny) ? ny : 0f, 0f, 1f);
        int x = target.X + (int)Math.Round(cx * Math.Max(0, target.Width - 1));
        int y = target.Y + (int)Math.Round(cy * Math.Max(0, target.Height - 1));
        return (x, y);
    }

    /// <summary>Applies a normalized sub-region (0..1 fractions) to a monitor rectangle.</summary>
    public static PixelRect SubRegion(PixelRect monitor, NormalizedRegion? region)
    {
        if (region is not { } r || !r.IsValid) return monitor;
        int x = monitor.X + (int)Math.Round(r.X * monitor.Width);
        int y = monitor.Y + (int)Math.Round(r.Y * monitor.Height);
        int w = Math.Max(1, (int)Math.Round(r.Width * monitor.Width));
        int h = Math.Max(1, (int)Math.Round(r.Height * monitor.Height));
        return new PixelRect(x, y, w, h);
    }
}

public readonly record struct NormalizedRegion(double X, double Y, double Width, double Height)
{
    public bool IsValid =>
        Width > 0.01 && Height > 0.01 && X >= 0 && Y >= 0 && X + Width <= 1.0001 && Y + Height <= 1.0001;
}

public static class PressureCurve
{
    /// <summary>
    /// Maps raw 0..1024 pressure through <c>p^gamma</c>. Gamma below 1 is a soft pen (light touch = more ink),
    /// above 1 is a firm pen.
    /// </summary>
    public static uint Apply(ushort raw, double gamma)
    {
        if (raw == 0) return 0;
        double p = Math.Clamp(raw / (double)Protocol.MaxPressure, 0, 1);
        if (gamma > 0 && Math.Abs(gamma - 1) > 1e-6) p = Math.Pow(p, gamma);
        return (uint)Math.Clamp(Math.Round(p * Protocol.MaxPressure), 1, Protocol.MaxPressure);
    }
}
