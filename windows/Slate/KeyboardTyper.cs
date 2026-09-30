using System.ComponentModel;
using System.Runtime.InteropServices;
using Slate.Core;

namespace Slate;

/// <summary>
/// Types text into the focused window as Unicode keystrokes, so any character (², √, π…) works
/// regardless of keyboard layout. Line breaks follow the "Line breaks as" setting.
/// </summary>
internal sealed class KeyboardTyper(Func<NewlineMode> newlines) : ITextTyper
{
    public void Type(string text)
    {
        var inputs = KeySequence.For(text, newlines()).Select(ToInput).ToArray();
        if (inputs.Length == 0) return;

        uint sent = Native.SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Native.INPUT>());
        if (sent != inputs.Length)
            Log.Info($"SendInput typed {sent}/{inputs.Length} key events: {new Win32Exception(Marshal.GetLastWin32Error()).Message}. " +
                     "Windows blocks input to apps running as administrator unless Slate is too.");
    }

    private static Native.INPUT ToInput(KeyStroke k) => new()
    {
        type = Native.INPUT_KEYBOARD,
        ki = new Native.KEYBDINPUT
        {
            wVk = k.VirtualKey,
            wScan = k.Character,
            dwFlags = (k.IsUnicode ? Native.KEYEVENTF_UNICODE : 0) | (k.Up ? Native.KEYEVENTF_KEYUP : 0),
        },
    };
}
