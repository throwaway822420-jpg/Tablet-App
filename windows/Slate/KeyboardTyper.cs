using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using Slate.Core;

namespace Slate;

/// <summary>
/// Carries out TEXT messages from the tablet: types prose as Unicode keystrokes (any character, any
/// keyboard layout, line breaks per the "Line breaks as" setting), pastes equations as MathML so
/// Word/PowerPoint/OneNote turn them into real equations, and sets the clipboard for calculator
/// answers. The user's clipboard is restored after pasting equations.
/// </summary>
internal sealed class KeyboardTyper(Func<NewlineMode> newlines, Action<Action> onUiThread) : ITextTyper
{
    private const int PasteSettleMs = 300;

    public void Type(string text)
    {
        var message = RichText.Parse(text);
        if (message.IsClipboard)
        {
            onUiThread(() => SetClipboard(message.ClipboardText!, message.ClipboardMathMl));
            return;
        }

        DataObject? saved = null;
        bool pasted = false;
        foreach (var part in message.Parts)
        {
            switch (part)
            {
                case RichPart.Typed t:
                    Send(KeySequence.For(t.Text, newlines()));
                    break;
                case RichPart.Equation eq:
                    bool ok = false;
                    onUiThread(() =>
                    {
                        saved ??= SnapshotClipboard();
                        ok = SetClipboard(eq.MathMl, eq.MathMl);
                    });
                    if (ok)
                    {
                        Send(KeySequence.Paste());
                        Thread.Sleep(PasteSettleMs); // let the app read the clipboard before it changes again
                        pasted = true;
                    }
                    else
                    {
                        Send(KeySequence.For(eq.Fallback, newlines()));
                    }
                    break;
            }
        }
        if (pasted && saved is not null)
        {
            var restore = saved;
            onUiThread(() => TrySetDataObject(restore));
        }
    }

    private static void Send(List<KeyStroke> keys)
    {
        var inputs = keys.Select(ToInput).ToArray();
        if (inputs.Length == 0) return;
        uint sent = Native.SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Native.INPUT>());
        if (sent != inputs.Length)
            Log.Info($"SendInput typed {sent}/{inputs.Length} key events: {new Win32Exception(Marshal.GetLastWin32Error()).Message}. " +
                     "Windows blocks input to apps running as administrator unless Slate is too.");
    }

    /// <summary>Plain text, plus MathML in the formats Office reads, so pasting gives a real equation.</summary>
    private static bool SetClipboard(string text, string? mathMl)
    {
        var data = new DataObject();
        data.SetText(text, TextDataFormat.UnicodeText);
        if (!string.IsNullOrEmpty(mathMl))
        {
            var bytes = Encoding.UTF8.GetBytes(mathMl);
            data.SetData("MathML", new MemoryStream(bytes));
            data.SetData("MathML Presentation", new MemoryStream(bytes));
            data.SetData("application/mathml+xml", new MemoryStream(bytes));
        }
        return TrySetDataObject(data);
    }

    private static bool TrySetDataObject(DataObject data) => ClipboardTools.Restore(data);

    private static DataObject SnapshotClipboard() => ClipboardTools.Snapshot();

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
