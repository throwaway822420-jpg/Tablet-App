using Slate.Core;

namespace Slate;

internal static class Monitors
{
    // The process is per-monitor DPI aware (PerMonitorV2), so Screen.Bounds is in physical pixels,
    // the same space InjectSyntheticPointerInput uses.

    public static Screen Find(string? deviceName) =>
        Screen.AllScreens.FirstOrDefault(s => s.DeviceName == deviceName) ?? Screen.PrimaryScreen ?? Screen.AllScreens[0];

    public static PixelRect Bounds(Screen screen) =>
        new(screen.Bounds.X, screen.Bounds.Y, screen.Bounds.Width, screen.Bounds.Height);

    public static PixelRect Target(SlateSettings settings) =>
        Mapping.SubRegion(Bounds(Find(settings.MonitorDeviceName)), settings.Region);

    public static string Describe(Screen s, int index) =>
        $"Display {index + 1}{(s.Primary ? " (main)" : "")}: {s.Bounds.Width}×{s.Bounds.Height}";
}
