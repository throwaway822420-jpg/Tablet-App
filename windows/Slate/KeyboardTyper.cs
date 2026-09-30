using System.ComponentModel;
using System.Runtime.InteropServices;
using Slate.Core;

namespace Slate;

/// <summary>
/// Types text into the focused window as Unicode keystrokes, so any character (², √, π…) works
/// regardless of keyboard layout. Newlines are sent as Enter.
/// </summary>
internal sealed class KeyboardTyper : ITextTyper
{
    public void Type(string text)
    {
        var inputs = new List<Native.INPUT>(text.Length * 2);
        foreach (char c in text.Replace("\r\n", "\n"))
        {
            if (c == '\r') continue;
            if (c == '\n')
            {
                inputs.Add(Key(Native.VK_RETURN, '\0', 0));
                inputs.Add(Key(Native.VK_RETURN, '\0', Native.KEYEVENTF_KEYUP));
                continue;
            }
            // Surrogate pairs go through as two UTF-16 units; Windows recombines them.
            inputs.Add(Key(0, c, Native.KEYEVENTF_UNICODE));
            inputs.Add(Key(0, c, Native.KEYEVENTF_UNICODE | Native.KEYEVENTF_KEYUP));
        }
        if (inputs.Count == 0) return;

        uint sent = Native.SendInput((uint)inputs.Count, inputs.ToArray(), Marshal.SizeOf<Native.INPUT>());
        if (sent != inputs.Count)
            Log.Info($"SendInput typed {sent}/{inputs.Count} key events: {new Win32Exception(Marshal.GetLastWin32Error()).Message}. " +
                     "Windows blocks input to apps running as administrator unless Slate is too.");
    }

    private static Native.INPUT Key(ushort vk, char scan, uint flags) => new()
    {
        type = Native.INPUT_KEYBOARD,
        ki = new Native.KEYBDINPUT { wVk = vk, wScan = scan, dwFlags = flags },
    };
}
