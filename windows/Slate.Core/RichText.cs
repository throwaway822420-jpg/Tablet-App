using System.Text;

namespace Slate.Core;

/// <summary>One piece of a rich TEXT message: text to type, or an equation to paste.</summary>
public abstract record RichPart
{
    public sealed record Typed(string Text) : RichPart;

    /// <summary>MathML to paste as an equation; <paramref name="Fallback"/> is typed if pasting isn't possible.</summary>
    public sealed record Equation(string MathMl, string Fallback) : RichPart;
}

/// <summary>A decoded TEXT message: either parts to type/paste, or clipboard contents to set.</summary>
public sealed record RichMessage(IReadOnlyList<RichPart> Parts, string? ClipboardText, string? ClipboardMathMl)
{
    public bool IsClipboard => ClipboardText is not null;
}

/// <summary>
/// Decodes the private-use markers the tablet puts in TEXT messages (see PROTOCOL.md "Rich text"):
/// U+E000 mathml U+E004 unicode U+E001 is an equation to paste; a message starting with U+E002 is
/// clipboard contents, optionally followed by U+E003 and MathML.
/// </summary>
public static class RichText
{
    public const char MathStart = '';
    public const char MathEnd = '';
    public const char Clipboard = '';
    public const char ClipboardMathMl = '';
    public const char MathFallback = '';

    public static RichMessage Parse(string text)
    {
        if (text.Length > 0 && text[0] == Clipboard)
        {
            var body = text[1..];
            int split = body.IndexOf(ClipboardMathMl);
            return split < 0
                ? new RichMessage(Array.Empty<RichPart>(), body, null)
                : new RichMessage(Array.Empty<RichPart>(), body[..split], body[(split + 1)..]);
        }

        var parts = new List<RichPart>();
        var typed = new StringBuilder();
        int i = 0;
        while (i < text.Length)
        {
            int start = text.IndexOf(MathStart, i);
            int end = start < 0 ? -1 : text.IndexOf(MathEnd, start + 1);
            if (start < 0 || end < 0)
            {
                typed.Append(StripMarkers(text[i..]));
                break;
            }
            typed.Append(StripMarkers(text[i..start]));
            if (typed.Length > 0)
            {
                parts.Add(new RichPart.Typed(typed.ToString()));
                typed.Clear();
            }
            var inner = text[(start + 1)..end];
            int fb = inner.IndexOf(MathFallback);
            parts.Add(fb < 0 ? new RichPart.Equation(inner, "") : new RichPart.Equation(inner[..fb], inner[(fb + 1)..]));
            i = end + 1;
        }
        if (typed.Length > 0) parts.Add(new RichPart.Typed(typed.ToString()));
        return new RichMessage(parts, null, null);
    }

    private static string StripMarkers(string s) =>
        s.Replace(MathStart.ToString(), "").Replace(MathEnd.ToString(), "").Replace(MathFallback.ToString(), "");
}
