using System.Diagnostics;
using System.Text;

namespace Slate.Core;

/// <summary>Runs one turn of a Claude Code session headlessly (<c>claude -p</c>) and streams its reply.</summary>
public static class ClaudeCodeRunner
{
    /// <summary>
    /// Arguments for a study turn: streamed JSON output, reads (the saved images) and web lookups
    /// allowed without prompting, anything else denied (nobody is there to approve it).
    /// </summary>
    /// <param name="model">"sonnet" / "opus", or empty for Claude Code's default model.</param>
    public static List<string> Args(bool fresh, string session, string name, string systemPromptFile, string model = "")
    {
        var a = new List<string>
        {
            "-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
            "--permission-mode", "dontAsk", "--allowedTools", "Read,WebSearch,WebFetch",
            "--append-system-prompt-file", systemPromptFile,
        };
        if (model is "sonnet" or "opus" or "haiku") a.AddRange(new[] { "--model", model });
        if (fresh) a.AddRange(new[] { "--session-id", session, "--name", name });
        else a.AddRange(new[] { "--resume", session });
        return a;
    }

    /// <summary>Result of a turn: the final event (null if Claude Code never finished) and its stderr.</summary>
    public sealed record Outcome(ClaudeCodeEvent.Finished? Finished, string StreamedText, string Errors, int ExitCode);

    public static async Task<Outcome> RunAsync(
        string cli, string workingDir, IEnumerable<string> args, string prompt, Action<ClaudeCodeEvent> onEvent, CancellationToken ct = default)
    {
        var psi = new ProcessStartInfo(cli)
        {
            WorkingDirectory = workingDir,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            StandardInputEncoding = new UTF8Encoding(false),
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var proc = Process.Start(psi) ?? throw new InvalidOperationException("Couldn't start Claude Code.");
        // The prompt goes in on stdin, which avoids command-line quoting rules for .cmd launchers.
        await proc.StandardInput.WriteAsync(prompt);
        proc.StandardInput.Close();
        var errors = proc.StandardError.ReadToEndAsync(ct);

        var text = new StringBuilder();
        ClaudeCodeEvent.Finished? done = null;
        string? line;
        while ((line = await proc.StandardOutput.ReadLineAsync(ct)) is not null)
        {
            var ev = ClaudeCodeEvent.Parse(line);
            if (ev is null) continue;
            if (ev is ClaudeCodeEvent.Text t) text.Append(t.Delta);
            if (ev is ClaudeCodeEvent.Finished f) done = f;
            onEvent(ev);
        }
        await proc.WaitForExitAsync(ct);
        return new Outcome(done, text.ToString(), (await errors).Trim(), proc.ExitCode);
    }
}
