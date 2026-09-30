namespace Slate.Core;

public enum NewlineMode
{
    /// <summary>A new line without sending: chat apps (Claude, ChatGPT, Slack, Discord, Teams) treat plain Enter as Send.</summary>
    ShiftEnter,

    /// <summary>A real Enter keypress.</summary>
    Enter,

    /// <summary>Line breaks become spaces.</summary>
    Space,
}

/// <summary>One key event: a Unicode character, or a virtual key (Enter, Shift) going down or up.</summary>
public readonly record struct KeyStroke(ushort VirtualKey, char Character, bool Up)
{
    public const ushort VkReturn = 0x0D;
    public const ushort VkShift = 0x10;

    public bool IsUnicode => VirtualKey == 0;

    public static KeyStroke Char(char c, bool up) => new(0, c, up);
    public static KeyStroke Vk(ushort vk, bool up) => new(vk, '\0', up);
}

/// <summary>Turns text into the key events that type it, handling line breaks per <see cref="NewlineMode"/>.</summary>
public static class KeySequence
{
    public static List<KeyStroke> For(string text, NewlineMode newlines)
    {
        var keys = new List<KeyStroke>(text.Length * 2);
        foreach (char c in text.Replace("\r\n", "\n"))
        {
            if (c == '\r') continue;
            if (c == '\n')
            {
                switch (newlines)
                {
                    case NewlineMode.ShiftEnter:
                        keys.Add(KeyStroke.Vk(KeyStroke.VkShift, up: false));
                        keys.Add(KeyStroke.Vk(KeyStroke.VkReturn, up: false));
                        keys.Add(KeyStroke.Vk(KeyStroke.VkReturn, up: true));
                        keys.Add(KeyStroke.Vk(KeyStroke.VkShift, up: true));
                        break;
                    case NewlineMode.Enter:
                        keys.Add(KeyStroke.Vk(KeyStroke.VkReturn, up: false));
                        keys.Add(KeyStroke.Vk(KeyStroke.VkReturn, up: true));
                        break;
                    case NewlineMode.Space:
                        keys.Add(KeyStroke.Char(' ', up: false));
                        keys.Add(KeyStroke.Char(' ', up: true));
                        break;
                }
                continue;
            }
            // Surrogate pairs go through as two UTF-16 units; Windows recombines them.
            keys.Add(KeyStroke.Char(c, up: false));
            keys.Add(KeyStroke.Char(c, up: true));
        }
        return keys;
    }
}
