using Slate.Core;
using Xunit;

namespace Slate.Core.Tests;

public class NavigationTests
{
    [Fact]
    public void ParsesCombos()
    {
        Assert.Equal(new ushort[] { 0x11, 0x10, 'Z' }, KeyCombo.Parse("Ctrl+Shift+Z"));
        Assert.Equal(new ushort[] { 0x12, 0x09 }, KeyCombo.Parse("alt + tab"));
        Assert.Equal(new ushort[] { 0x5B, 'D' }, KeyCombo.Parse("Win+d"));
        Assert.Equal(new ushort[] { 0x74 }, KeyCombo.Parse("F5"));
        Assert.Equal(new ushort[] { 0x1B }, KeyCombo.Parse("Esc"));
        Assert.Null(KeyCombo.Parse("Ctrl+Banana"));
        Assert.Null(KeyCombo.Parse(""));
    }

    [Fact]
    public void ModifiersWrapTheKey()
    {
        var s = KeyCombo.Strokes("Ctrl+Shift+Z")!;
        Assert.Equal(new[]
        {
            KeyStroke.Vk(0x11, false), KeyStroke.Vk(0x10, false), KeyStroke.Vk('Z', false), KeyStroke.Vk('Z', true),
            KeyStroke.Vk(0x10, true), KeyStroke.Vk(0x11, true),
        }, s);
        Assert.Equal(new[] { KeyStroke.Vk(0x5B, false), KeyStroke.Vk(0x5B, true) }, KeyCombo.Strokes("Win")); // Start menu
    }

    [Fact]
    public void TouchFramesCarryEveryFinger()
    {
        var t = new TouchTracker();
        var screen = new PixelRect(0, 0, 1001, 1001);
        var f1 = t.Update(new[] { new TouchContact(3, 0.1f, 0.1f, TouchPhase.Down) }, screen);
        Assert.Equal(new[] { new TouchPoint(3, 100, 100, PointerFlags.Down | PointerFlags.InRange | PointerFlags.InContact | PointerFlags.Primary) }, f1);

        var f2 = t.Update(new[] { new TouchContact(5, 0.5f, 0.5f, TouchPhase.Down) }, screen);
        Assert.Equal(2, f2.Count); // the resting finger is repeated as an update
        Assert.Contains(f2, p => p.Id == 5 && p.Flags.HasFlag(PointerFlags.Down) && !p.Flags.HasFlag(PointerFlags.Primary));
        Assert.Contains(f2, p => p.Id == 3 && p.Flags.HasFlag(PointerFlags.Update) && p.Flags.HasFlag(PointerFlags.Primary));

        var f3 = t.Update(new[] { new TouchContact(3, 0.2f, 0.2f, TouchPhase.Up) }, screen);
        Assert.Contains(f3, p => p.Id == 3 && p.Flags.HasFlag(PointerFlags.Up));
        Assert.Equal(1, t.ActiveCount);

        var f4 = t.ReleaseAll();
        Assert.Equal(new[] { new TouchPoint(5, 500, 500, PointerFlags.Up | PointerFlags.Primary) }, f4);
        Assert.Empty(t.Update(new[] { new TouchContact(9, 0, 0, TouchPhase.Up) }, screen)); // unknown finger lifting: nothing
    }
}
