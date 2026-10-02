using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Drawing.Imaging;
using System.Text;
using System.Text.Json.Nodes;
using Slate.Core;

namespace Slate;

/// <summary>
/// "Ask Claude about the screen": full-resolution screenshots for the tablet to annotate, and the
/// annotated questions sent on to Claude, either as a Claude Code session (replies stream back to
/// the tablet; the session opens in the Claude apps) or typed into the Claude Desktop app.
/// </summary>
internal sealed class AskService
{
    private readonly BulkServer _bulk;
    private readonly MirrorService _mirror;
    private readonly SlateSettings _settings;
    private readonly Action<Action> _onUi;
    private readonly SemaphoreSlim _one = new(1, 1);
    private readonly HashSet<string> _desktopSessionsSeen = new();

    public static string StudyDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Slate", "study");

    public AskService(BulkServer bulk, MirrorService mirror, SlateSettings settings, Action<Action> onUi)
    {
        _bulk = bulk;
        _mirror = mirror;
        _settings = settings;
        _onUi = onUi;
        mirror.Handlers["shot"] = (_, _) => Task.Run(SendScreenshot);
        mirror.Handlers["ask"] = (header, data) =>
        {
            var r = AskRequest.FromBlob(header, data); // a typed message comes without a Blob
            if (r is null) Status(header.Str("askId"), "error", "The question didn't arrive complete. Try again.");
            else Task.Run(() => AskAsync(r));
        };
        mirror.Handlers["ask.open"] = (o, _) => Task.Run(() => Open(o.Str("session")));
    }

    // --- Screenshots ---

    private void SendScreenshot()
    {
        try
        {
            var bounds = _mirror.MirroredBounds ?? Monitors.Bounds(Monitors.Find(_settings.MonitorDeviceName));
            using var bmp = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format24bppRgb);
            using (var g = Graphics.FromImage(bmp)) g.CopyFromScreen(bounds.X, bounds.Y, 0, 0, new Size(bounds.Width, bounds.Height));
            var jpeg = Jpeg(bmp, 92);
            _bulk.Send(BulkFrame.Blob(new JsonObject { ["t"] = "shot", ["w"] = bounds.Width, ["h"] = bounds.Height }, jpeg));
        }
        catch (Exception ex) when (ex is ExternalException or ArgumentException or Win32Exception)
        {
            Log.Info($"Screenshot failed: {ex.Message}");
            _bulk.Send(new JsonObject { ["t"] = "shot.error", ["message"] = ex.Message });
        }
    }

    private static byte[] Jpeg(Bitmap bmp, long quality)
    {
        var codec = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
        using var p = new EncoderParameters(1);
        p.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, quality);
        using var ms = new MemoryStream();
        bmp.Save(ms, codec, p);
        return ms.ToArray();
    }

    // --- Asking ---

    private void Status(string askId, string state, string message, JsonObject? extra = null)
    {
        var o = new JsonObject { ["t"] = "ask.status", ["askId"] = askId, ["state"] = state, ["message"] = message };
        if (extra is not null) foreach (var (k, v) in extra) o[k] = v?.DeepClone();
        _bulk.Send(o);
    }

    private async Task AskAsync(AskRequest r)
    {
        await _one.WaitAsync(); // one question at a time, in order
        try
        {
            if (_settings.AskBackend == AskBackend.ClaudeDesktop) AskDesktop(r);
            else await AskClaudeCodeAsync(r);
        }
        catch (Exception ex)
        {
            Log.Info($"Ask failed: {ex}");
            Status(r.AskId, "error", ex.Message);
        }
        finally
        {
            _one.Release();
        }
    }

    private List<string> SaveImages(AskRequest r, string dir)
    {
        Directory.CreateDirectory(dir);
        int n = Directory.GetFiles(dir, "q*-*").Select(f => Path.GetFileName(f).Split('-')[0][1..]).Select(s => int.TryParse(s, out var i) ? i : 0).DefaultIfEmpty(0).Max() + 1;
        var paths = new List<string>();
        foreach (var (name, data) in r.Images)
        {
            var path = Path.Combine(dir, $"q{n}-{name}");
            File.WriteAllBytes(path, data);
            paths.Add(path);
        }
        return paths;
    }

    // Claude Code: `claude -p` with stream-json output; the session ID ties follow-ups together.

    public string? FindClaudeCli()
    {
        var candidates = new List<string?> { _settings.ClaudeCliPath };
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator).Where(d => d.Length > 0))
        {
            candidates.Add(Path.Combine(dir.Trim('"'), "claude.exe"));
            candidates.Add(Path.Combine(dir.Trim('"'), "claude.cmd"));
        }
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        candidates.Add(Path.Combine(home, ".local", "bin", "claude.exe"));
        candidates.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "npm", "claude.cmd"));
        return candidates.FirstOrDefault(c => !string.IsNullOrWhiteSpace(c) && File.Exists(c));
    }

    private async Task AskClaudeCodeAsync(AskRequest r)
    {
        var cli = FindClaudeCli();
        if (cli is null)
        {
            Status(r.AskId, "error", "Claude Code isn't installed on the PC. Install it (claude.com/claude-code), run `claude` once to sign in, then try again. " +
                                     "Or switch Slate on the PC to \"Claude Desktop app\" in Settings.");
            return;
        }

        bool fresh = r.NewSession || !Guid.TryParse(r.SessionId, out _);
        string session = fresh ? Guid.NewGuid().ToString() : r.SessionId;
        string dir = Path.Combine(StudyDir, session);
        var paths = SaveImages(r, dir);
        string title = r.Title.Length > 0 ? r.Title : $"Study {DateTime.Now:d MMM HH:mm}";
        File.WriteAllText(Path.Combine(dir, "title.txt"), title);
        string systemFile = Path.Combine(StudyDir, "slate-study-prompt.md");
        Directory.CreateDirectory(StudyDir);
        File.WriteAllText(systemFile, StudyPrompt.System);

        Status(r.AskId, "thinking", r.Images.Count > 0 ? "Claude is reading your screen…" : "Claude is thinking…", new JsonObject { ["session"] = session });
        var outcome = await ClaudeCodeRunner.RunAsync(cli, dir, ClaudeCodeRunner.Args(fresh, session, "Slate: " + title, systemFile),
            StudyPrompt.ForClaudeCode(r, paths), ev =>
            {
                switch (ev)
                {
                    case ClaudeCodeEvent.Text t:
                        _bulk.Send(new JsonObject { ["t"] = "ask.delta", ["askId"] = r.AskId, ["text"] = t.Delta });
                        break;
                    case ClaudeCodeEvent.Tool tool:
                        Status(r.AskId, "thinking", tool.Name == "Read" ? "Looking at your screen…" : $"Using {tool.Name}…");
                        break;
                }
            });
        var done = outcome.Finished;
        string err = outcome.Errors;
        var text = outcome.StreamedText;
        int exitCode = outcome.ExitCode;

        if (done is null || done.IsError)
        {
            string why = done?.Result is { Length: > 0 } res ? res : err.Length > 0 ? err : $"Claude Code exited with code {exitCode}.";
            if (why.Contains("login", StringComparison.OrdinalIgnoreCase) || why.Contains("auth", StringComparison.OrdinalIgnoreCase))
                why += " — run `claude` on the PC once and sign in.";
            Status(r.AskId, "error", why, new JsonObject { ["session"] = session });
            return;
        }
        _bulk.Send(new JsonObject
        {
            ["t"] = "ask.done",
            ["askId"] = r.AskId,
            ["session"] = done.SessionId.Length > 0 ? done.SessionId : session,
            ["backend"] = "code",
            ["title"] = title,
            ["markdown"] = done.Result.Length > 0 ? done.Result : text,
            ["cost"] = done.CostUsd,
        });
    }

    // Claude Desktop: paste the images and type the question into the app's chat box.

    private void AskDesktop(AskRequest r)
    {
        bool fresh = r.NewSession || !r.SessionId.StartsWith("desktop-", StringComparison.Ordinal);
        string session = fresh ? "desktop-" + Guid.NewGuid() : r.SessionId;
        bool firstEver = _desktopSessionsSeen.Count == 0;

        Status(r.AskId, "thinking", "Opening Claude Desktop…", new JsonObject { ["session"] = session });
        var window = DesktopWindow.FindOrLaunch(TimeSpan.FromSeconds(20));
        if (window == IntPtr.Zero)
        {
            Status(r.AskId, "error", "Couldn't find or start the Claude Desktop app on the PC.");
            return;
        }

        var previous = Native.GetForegroundWindow();
        if (!DesktopWindow.Activate(window))
        {
            Status(r.AskId, "error", "Windows didn't let Slate bring Claude Desktop to the front. Click the PC once and try again.");
            return;
        }
        DataObject? saved = null;
        _onUi(() => saved = ClipboardTools.Snapshot());
        try
        {
            if (fresh)
            {
                KeyInput.Combo(_settings.DesktopNewChatShortcut);
                Thread.Sleep(900);
            }
            foreach (var (_, data) in r.Images)
            {
                _onUi(() =>
                {
                    using var ms = new MemoryStream(data);
                    using var img = Image.FromStream(ms);
                    Clipboard.SetImage(img);
                });
                KeyInput.Send(KeySequence.Paste());
                Thread.Sleep(700); // let the app read and attach the image
            }
            KeyInput.Send(KeySequence.For(StudyPrompt.ForDesktop(r), NewlineMode.ShiftEnter));
            Thread.Sleep(150);
            KeyInput.Combo("Enter");
            Thread.Sleep(300);
        }
        finally
        {
            if (saved is not null) _onUi(() => ClipboardTools.Restore(saved));
            // Pop up the first time so the user sees where answers go; afterwards step back out of the way.
            if (!firstEver && previous != IntPtr.Zero) DesktopWindow.Activate(previous);
        }
        _desktopSessionsSeen.Add(session);
        _bulk.Send(new JsonObject
        {
            ["t"] = "ask.done",
            ["askId"] = r.AskId,
            ["session"] = session,
            ["backend"] = "desktop",
            ["title"] = r.Title.Length > 0 ? r.Title : $"Study {DateTime.Now:d MMM HH:mm}",
            ["markdown"] = "_Sent to the Claude Desktop app on the PC. The answer is there (and in the Claude app on any device); tap **Open in Claude**, or switch to Screen mode to read it._",
        });
    }

    // --- Open a session in the Claude apps ---

    private void Open(string session)
    {
        if (session.StartsWith("desktop-", StringComparison.Ordinal))
        {
            var w = DesktopWindow.FindOrLaunch(TimeSpan.FromSeconds(20));
            if (w != IntPtr.Zero) DesktopWindow.Activate(w);
            return;
        }
        var cli = FindClaudeCli();
        if (cli is null || !Guid.TryParse(session, out _)) return;
        string dir = Path.Combine(StudyDir, session);
        Directory.CreateDirectory(dir);
        string title = File.Exists(Path.Combine(dir, "title.txt")) ? File.ReadAllText(Path.Combine(dir, "title.txt")) : "Slate study";
        // An interactive session with Remote Control: it appears at claude.ai/code and in the Claude
        // mobile app, and Claude Desktop can pick it up with /resume.
        string cmd = $"\"{cli}\" --resume {session} --remote-control \"Slate: {title.Replace("\"", "'")}\"";
        Process.Start(new ProcessStartInfo("cmd.exe", $"/k title Slate study && {cmd}") { WorkingDirectory = dir, UseShellExecute = true });
    }
}
