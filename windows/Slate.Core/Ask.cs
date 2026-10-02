using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Slate.Core;

public enum AskBackend
{
    /// <summary>A Claude Code session on the PC (replies come back to the tablet; open it in the Claude apps).</summary>
    ClaudeCode,

    /// <summary>Types the question into the Claude Desktop app (a normal chat; replies stay in the app).</summary>
    ClaudeDesktop,
}

/// <summary>
/// A question from the tablet: an annotated screenshot (or a screenshot of an answer for a follow-up)
/// plus an intent, or a typed chat message ("chat", with <see cref="Text"/> and usually no images).
/// </summary>
public sealed record AskRequest(
    string AskId,
    string SessionId,
    bool NewSession,
    string Intent,
    string Title,
    IReadOnlyList<(string Name, byte[] Data)> Images,
    string Text = "",
    string Model = "")
{
    /// <summary>
    /// Parses an "ask" message: header {askId, session, new, intent, title, text?, images:[{name, size}]}
    /// with the images back to back in a Blob (or a plain JSON message when there are none).
    /// </summary>
    public static AskRequest? FromBlob(JsonObject header, byte[]? data)
    {
        data ??= Array.Empty<byte>();
        var images = new List<(string, byte[])>();
        int offset = 0;
        if (header["images"] is JsonArray arr)
        {
            foreach (var img in arr.OfType<JsonObject>())
            {
                int size = img.Int("size");
                if (size <= 0 || offset + size > data.Length) return null;
                string name = SafeName(img.Str("name", $"image{images.Count}.jpg"));
                images.Add((name, data.AsSpan(offset, size).ToArray()));
                offset += size;
            }
        }
        string id = header.Str("askId");
        string text = header.Str("text").Trim();
        if (id.Length == 0 || (images.Count == 0 && text.Length == 0)) return null;
        return new AskRequest(id, header.Str("session"), header.Bool("new"), header.Str("intent", "ask"), header.Str("title"), images, text, header.Str("model"));
    }

    /// <summary>Only simple image file names: the tablet never chooses where files go or what type they are.</summary>
    private static string SafeName(string name)
    {
        var last = name.Split('/', '\\').Last();
        var clean = new string(last.Where(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_' or '.').ToArray());
        bool image = clean.EndsWith(".jpg", StringComparison.OrdinalIgnoreCase) || clean.EndsWith(".jpeg", StringComparison.OrdinalIgnoreCase)
            || clean.EndsWith(".png", StringComparison.OrdinalIgnoreCase);
        return !image || clean.Length > 60 || clean.StartsWith('.') ? "image.jpg" : clean;
    }
}

/// <summary>Wording for each study intent, and the standing instructions for study sessions.</summary>
public static class StudyPrompt
{
    public static readonly IReadOnlyDictionary<string, string> Intents = new Dictionary<string, string>
    {
        ["ask"] = "Answer the question I've written on it.",
        ["explain"] = "Explain what I've circled or highlighted: what it means and why, clearly enough that I understand it.",
        ["steps"] = "Work through what I've marked step by step, explaining the reasoning at each step.",
        ["hint"] = "Give me a hint only: nudge me towards the next step without giving the answer away.",
        ["check"] = "Check my working on what I've marked: say what's right, and point out the first mistake and why without redoing everything for me.",
        ["quiz"] = "Quiz me on this: ask me 3 short questions of increasing difficulty to test my understanding, and don't give the answers yet.",
        ["followup"] = "This is a follow-up to our conversation: a screenshot of your last answer with my pen annotations and my handwritten question on it. Read it and reply.",
        ["chat"] = "",
    };

    public const string System = """
        You are helping a student who is studying on a tablet. Each question arrives as a screenshot of
        what they're working on, with their pen annotations drawn on top: circles, underlines or
        highlights mark what they mean, and their handwritten question is usually written on the image.
        Sometimes a zoomed crop of the circled area is included too. Read the handwriting as their words.

        Answer as a patient, precise tutor. Be concise and lead with the point. Format for a Markdown
        viewer: headings only when useful, $…$ and $$…$$ for maths (LaTeX), and ```mermaid blocks when a
        diagram genuinely helps. Don't describe the screenshot back to them unless it helps the answer.
        Respect the intent they chose: a hint means a hint, not the answer.
        """;

    /// <summary>The prompt for Claude Code, pointing it at the saved image files.</summary>
    public static string ForClaudeCode(AskRequest r, IEnumerable<string> savedPaths)
    {
        var sb = new StringBuilder();
        string intent = Intent(r);
        if (intent.Length > 0) sb.AppendLine(intent).AppendLine();
        if (r.Text.Length > 0) sb.AppendLine(r.Text).AppendLine();
        var paths = savedPaths.ToList();
        if (paths.Count == 0) return sb.ToString().TrimEnd() + Environment.NewLine;
        sb.AppendLine(r.Intent == "followup" ? "My question is handwritten on this screenshot of your answer — read it with the Read tool:" : "Read these images with the Read tool:");
        foreach (var (p, n) in paths.Zip(r.Images.Select(x => x.Name)))
        {
            string what = n.Contains("crop", StringComparison.OrdinalIgnoreCase) ? "zoomed crop of what I circled"
                : r.Intent == "followup" ? "your previous answer with my annotations" : "my screen with my annotations";
            sb.AppendLine($"- {p} ({what})");
        }
        return sb.ToString();
    }

    /// <summary>The text typed into Claude Desktop after the pasted images.</summary>
    public static string ForDesktop(AskRequest r)
    {
        var parts = new List<string>();
        if (Intent(r) is { Length: > 0 } i) parts.Add(i);
        if (r.Text.Length > 0) parts.Add(r.Text);
        if (r.Images.Count > 0 && r.Intent is not ("followup" or "chat")) parts.Add("(The image is my screen with my pen annotations; my question is handwritten on it.)");
        return string.Join(" ", parts);
    }

    /// <summary>The intent's wording; none for a typed chat message, which speaks for itself.</summary>
    private static string Intent(AskRequest r) =>
        r.Intent == "chat" ? "" : Intents.TryGetValue(r.Intent, out var i) ? i : Intents["ask"];
}

/// <summary>What a line of Claude Code's <c>--output-format stream-json</c> output means for Slate.</summary>
public abstract record ClaudeCodeEvent
{
    public sealed record Started(string SessionId) : ClaudeCodeEvent;
    public sealed record Text(string Delta) : ClaudeCodeEvent;
    public sealed record Tool(string Name) : ClaudeCodeEvent;
    public sealed record Finished(string Result, string SessionId, double CostUsd, bool IsError) : ClaudeCodeEvent;

    /// <summary>Parses one stream-json line; null for lines Slate doesn't need.</summary>
    public static ClaudeCodeEvent? Parse(string line)
    {
        if (string.IsNullOrWhiteSpace(line) || line[0] != '{') return null;
        JsonObject? o;
        try
        {
            o = JsonNode.Parse(line) as JsonObject;
        }
        catch (JsonException)
        {
            return null;
        }
        if (o is null) return null;
        switch (o.Str("type"))
        {
            case "system" when o.Str("subtype") == "init":
                return new Started(o.Str("session_id"));
            case "stream_event" when o["parent_tool_use_id"] is null || o["parent_tool_use_id"]!.GetValueKind() == JsonValueKind.Null:
                if (o["event"] is JsonObject ev && ev.Str("type") == "content_block_delta" && ev["delta"] is JsonObject d && d.Str("type") == "text_delta")
                    return new Text(d.Str("text"));
                if (o["event"] is JsonObject start && start.Str("type") == "content_block_start" && start["content_block"] is JsonObject cb && cb.Str("type") == "tool_use")
                    return new Tool(cb.Str("name"));
                return null;
            case "result":
                return new Finished(o.Str("result"), o.Str("session_id"), o.Num("total_cost_usd"), o.Bool("is_error") || o.Str("subtype") != "success");
            default:
                return null;
        }
    }
}
