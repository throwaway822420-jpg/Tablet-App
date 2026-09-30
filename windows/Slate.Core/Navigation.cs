namespace Slate.Core;

/// <summary>Parses shortcuts like "Ctrl+Shift+Z", "Alt+Tab", "Win+D", "F5" into key presses.</summary>
public static class KeyCombo
{
    private static readonly Dictionary<string, ushort> Named = new(StringComparer.OrdinalIgnoreCase)
    {
        ["ctrl"] = 0x11, ["control"] = 0x11, ["shift"] = 0x10, ["alt"] = 0x12, ["win"] = 0x5B, ["windows"] = 0x5B, ["meta"] = 0x5B,
        ["enter"] = 0x0D, ["return"] = 0x0D, ["esc"] = 0x1B, ["escape"] = 0x1B, ["tab"] = 0x09, ["space"] = 0x20,
        ["backspace"] = 0x08, ["bksp"] = 0x08, ["del"] = 0x2E, ["delete"] = 0x2E, ["ins"] = 0x2D, ["insert"] = 0x2D,
        ["home"] = 0x24, ["end"] = 0x23, ["pgup"] = 0x21, ["pageup"] = 0x21, ["pgdn"] = 0x22, ["pagedown"] = 0x22,
        ["left"] = 0x25, ["up"] = 0x26, ["right"] = 0x27, ["down"] = 0x28, ["printscreen"] = 0x2C, ["prtsc"] = 0x2C,
        ["plus"] = 0xBB, ["minus"] = 0xBD, ["comma"] = 0xBC, ["period"] = 0xBE,
    };

    private static readonly HashSet<ushort> Modifiers = new() { 0x10, 0x11, 0x12, 0x5B };

    /// <summary>Virtual-key codes in press order, or null if a part isn't recognised.</summary>
    public static List<ushort>? Parse(string combo)
    {
        var keys = new List<ushort>();
        foreach (var raw in combo.Split('+', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
        {
            if (Named.TryGetValue(raw, out var vk)) keys.Add(vk);
            else if (raw.Length == 1 && char.IsAsciiLetterOrDigit(raw[0])) keys.Add(char.ToUpperInvariant(raw[0]));
            else if (raw.Length is 2 or 3 && (raw[0] is 'F' or 'f') && int.TryParse(raw[1..], out int f) && f is >= 1 and <= 24) keys.Add((ushort)(0x70 + f - 1));
            else return null;
        }
        return keys.Count == 0 ? null : keys;
    }

    /// <summary>Modifiers down, the key(s) down and up, modifiers up in reverse.</summary>
    public static List<KeyStroke>? Strokes(string combo)
    {
        var keys = Parse(combo);
        if (keys is null) return null;
        var mods = keys.Where(Modifiers.Contains).ToList();
        var main = keys.Where(k => !Modifiers.Contains(k)).ToList();
        var s = new List<KeyStroke>();
        s.AddRange(mods.Select(m => KeyStroke.Vk(m, up: false)));
        foreach (var k in main)
        {
            s.Add(KeyStroke.Vk(k, up: false));
            s.Add(KeyStroke.Vk(k, up: true));
        }
        s.AddRange(Enumerable.Reverse(mods).Select(m => KeyStroke.Vk(m, up: true)));
        return s;
    }
}

public enum TouchPhase { Down, Move, Up }

/// <summary>One finger from the tablet, in normalized coordinates of the mirrored monitor.</summary>
public readonly record struct TouchContact(int Id, float X, float Y, TouchPhase Phase);

/// <summary>One contact in a Windows touch injection frame.</summary>
public readonly record struct TouchPoint(uint Id, int X, int Y, PointerFlags Flags);

/// <summary>
/// Windows touch injection needs every active contact in every frame, each with the right
/// DOWN/UPDATE/UP flags. This keeps the set of fingers and builds those frames.
/// </summary>
public sealed class TouchTracker
{
    private readonly Dictionary<int, (int X, int Y)> _active = new();

    public int ActiveCount => _active.Count;

    /// <summary>Applies one update and returns the frame to inject (empty if nothing to send).</summary>
    public List<TouchPoint> Update(IReadOnlyList<TouchContact> changes, PixelRect target)
    {
        var frame = new List<TouchPoint>();
        var changed = new HashSet<int>();
        foreach (var c in changes)
        {
            var (x, y) = Mapping.ToPixels(c.X, c.Y, target);
            bool known = _active.ContainsKey(c.Id);
            changed.Add(c.Id);
            switch (c.Phase)
            {
                case TouchPhase.Down or TouchPhase.Move when !known:
                    _active[c.Id] = (x, y);
                    frame.Add(new TouchPoint((uint)c.Id, x, y, PointerFlags.Down | PointerFlags.InRange | PointerFlags.InContact));
                    break;
                case TouchPhase.Down or TouchPhase.Move:
                    _active[c.Id] = (x, y);
                    frame.Add(new TouchPoint((uint)c.Id, x, y, PointerFlags.Update | PointerFlags.InRange | PointerFlags.InContact));
                    break;
                case TouchPhase.Up when known:
                    _active.Remove(c.Id);
                    frame.Add(new TouchPoint((uint)c.Id, x, y, PointerFlags.Up));
                    break;
            }
        }
        // Fingers that didn't move still have to be in the frame.
        foreach (var (id, pos) in _active)
        {
            if (!changed.Contains(id)) frame.Add(new TouchPoint((uint)id, pos.X, pos.Y, PointerFlags.Update | PointerFlags.InRange | PointerFlags.InContact));
        }
        return MarkPrimary(frame);
    }

    /// <summary>Lifts every finger (e.g. on disconnect).</summary>
    public List<TouchPoint> ReleaseAll()
    {
        var frame = _active.Select(kv => new TouchPoint((uint)kv.Key, kv.Value.X, kv.Value.Y, PointerFlags.Up)).ToList();
        _active.Clear();
        return MarkPrimary(frame);
    }

    private static List<TouchPoint> MarkPrimary(List<TouchPoint> frame)
    {
        if (frame.Count == 0) return frame;
        uint first = frame.Min(p => p.Id);
        return frame.Select(p => p.Id == first ? p with { Flags = p.Flags | PointerFlags.Primary } : p).ToList();
    }
}
