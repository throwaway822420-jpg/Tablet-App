using Slate.Core;

namespace Slate;

/// <summary>
/// Milestone 1 check: draws a horizontal line across the middle of the target, pressure ramping
/// from light to full, through the same router the tablet uses.
/// </summary>
internal static class TestStroke
{
    public static async Task RunAsync(PenRouter router, int countdownSeconds = 3, Action<string>? progress = null)
    {
        for (int i = countdownSeconds; i > 0; i--)
        {
            progress?.Invoke($"Drawing a test stroke in {i}… (switch to your drawing app)");
            await Task.Delay(1000);
        }
        progress?.Invoke("Drawing…");

        const int steps = 240;
        const float y = 0.5f;
        PenSample Sample(float x, PenFlags flags, ushort pressure) => new(PenTool.Pen, flags, pressure, x, y, 0, 0, 0);

        router.OnPen(Sample(0.2f, PenFlags.InRange, 0));
        await Task.Delay(50);
        for (int i = 0; i <= steps; i++)
        {
            float t = i / (float)steps;
            ushort pressure = (ushort)Math.Round(20 + t * (Protocol.MaxPressure - 20));
            router.OnPen(Sample(0.2f + 0.6f * t, PenFlags.InRange | PenFlags.Contact, pressure));
            await Task.Delay(8);
        }
        router.OnPen(Sample(0.8f, PenFlags.InRange, 0));
        await Task.Delay(50);
        router.OnLeave();
        progress?.Invoke("Test stroke done. It should go from thin to thick.");
    }
}
