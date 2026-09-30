using System.Text.Json.Nodes;
using Slate.Core;

namespace Slate;

/// <summary>
/// Screen mirroring and navigation over the bulk channel: streams a monitor as H.264 video,
/// carries out the tablet's mouse / scroll / shortcut / touch messages, and reports the cursor so
/// the tablet can follow it when zoomed in.
/// </summary>
internal sealed class MirrorService : IDisposable
{
    private readonly BulkServer _bulk;
    private readonly SlateSettings _settings;
    private readonly Action _saveSettings;
    private readonly InputInjector _input = new();
    private readonly SemaphoreSlim _startLock = new(1, 1);
    private readonly System.Threading.Timer _cursorTimer;
    private ScreenStreamer? _streamer;
    private CancellationTokenSource? _startCts;
    private volatile Screen? _screen;
    private bool _usb;
    private (int X, int Y) _lastCursor = (int.MinValue, 0);

    /// <summary>Extra JSON handlers (e.g. asking Claude), by message type.</summary>
    /// <summary>Extra handlers by message type (e.g. asking Claude); the byte array is a Blob's payload.</summary>
    public Dictionary<string, Action<JsonObject, byte[]?>> Handlers { get; } = new();

    /// <summary>Raised when mirroring starts or stops, so the pen can be mapped onto the mirrored monitor.</summary>
    public event Action? MirrorChanged;

    /// <summary>Raised with a short status line for the tray.</summary>
    public event Action<string>? StatusChanged;

    public MirrorService(BulkServer bulk, SlateSettings settings, Action saveSettings)
    {
        _bulk = bulk;
        _settings = settings;
        _saveSettings = saveSettings;
        _bulk.Connected += OnConnected;
        _bulk.Disconnected += OnDisconnected;
        _bulk.FrameReceived += OnFrame;
        _cursorTimer = new System.Threading.Timer(_ => SendCursor(), null, Timeout.Infinite, Timeout.Infinite);
    }

    /// <summary>The monitor being mirrored, or null.</summary>
    public Screen? MirroredScreen => _screen;

    public PixelRect? MirroredBounds => _screen is { } s ? Monitors.Bounds(s) : null;

    private void OnConnected(string device)
    {
        var monitors = new JsonArray();
        var screens = Screen.AllScreens;
        for (int i = 0; i < screens.Length; i++)
        {
            monitors.Add(new JsonObject
            {
                ["id"] = screens[i].DeviceName,
                ["name"] = Monitors.Describe(screens[i], i),
                ["w"] = screens[i].Bounds.Width,
                ["h"] = screens[i].Bounds.Height,
                ["primary"] = screens[i].Primary,
            });
        }
        _bulk.Send(new JsonObject
        {
            ["t"] = "welcome",
            ["pc"] = Environment.MachineName,
            ["monitors"] = monitors,
            ["current"] = Monitors.Find(_settings.MonitorDeviceName).DeviceName,
        });
    }

    private void OnDisconnected() => StopStream("Tablet disconnected");

    private void OnFrame(BulkFrame f)
    {
        var o = f.AsJson();
        if (o is null)
        {
            if (f.AsBlob() is { } blob && Handlers.TryGetValue(blob.Header.Str("t"), out var bh)) bh(blob.Header, blob.Data.ToArray());
            return;
        }
        string t = o.Str("t");
        switch (t)
        {
            case "stream.start":
                _usb = o.Bool("usb");
                _ = StartStreamAsync(o.Str("monitor"), o.Int("fps", 60), o.Int("bitrate", 0));
                break;
            case "stream.stop":
                StopStream("Stopped by tablet");
                break;
            case "stream.keyframe":
                // ffmpeg can't be asked for a keyframe; with one every second the decoder recovers on its own.
                break;
            case "mouse":
                Mouse(o);
                break;
            case "scroll":
                if (Point(o) is { } sp) _input.Scroll(sp.X, sp.Y, o.Num("dx"), o.Num("dy"));
                break;
            case "keys":
                if (!_input.Keys(o.Str("combo"))) Log.Info($"Unknown shortcut '{o.Str("combo")}'");
                break;
            case "touch":
                Touch(o);
                break;
            default:
                if (Handlers.TryGetValue(t, out var h)) h(o, null);
                break;
        }
    }

    private (int X, int Y)? Point(JsonObject o)
    {
        var bounds = MirroredBounds ?? Monitors.Bounds(Monitors.Find(_settings.MonitorDeviceName));
        return Mapping.ToPixels((float)o.Num("x"), (float)o.Num("y"), bounds);
    }

    private void Mouse(JsonObject o)
    {
        if (Point(o) is not { } p) return;
        switch (o.Str("action"))
        {
            case "move": _input.MouseMove(p.X, p.Y); break;
            case "down": _input.Button(p.X, p.Y, down: true); break;
            case "up": _input.Button(p.X, p.Y, down: false); break;
            case "click": _input.Click(p.X, p.Y); break;
            case "dblclick": _input.Click(p.X, p.Y, count: 2); break;
            case "rightclick": _input.Click(p.X, p.Y, right: true); break;
        }
    }

    private void Touch(JsonObject o)
    {
        if (o["contacts"] is not JsonArray arr) return;
        var bounds = MirroredBounds ?? Monitors.Bounds(Monitors.Find(_settings.MonitorDeviceName));
        var contacts = arr.OfType<JsonObject>().Select(c => new TouchContact(
            c.Int("id"), (float)c.Num("x"), (float)c.Num("y"),
            c.Str("phase") switch { "down" => TouchPhase.Down, "up" => TouchPhase.Up, _ => TouchPhase.Move })).ToList();
        _input.Touch(contacts, bounds);
    }

    private async Task StartStreamAsync(string monitorId, int fps, int bitrate)
    {
        _startCts?.Cancel();
        var cts = _startCts = new CancellationTokenSource();
        await _startLock.WaitAsync();
        try
        {
            if (cts.IsCancellationRequested) return;
            StopStreamCore();

            var ffmpeg = FfmpegInstaller.Find(_settings.FfmpegPath);
            if (ffmpeg is null)
            {
                SendState("setup", "Downloading the video encoder (one time, about 115 MB)…");
                try
                {
                    ffmpeg = await FfmpegInstaller.DownloadAsync(pct =>
                        SendState("setup", $"Downloading the video encoder (one time): {pct}%"), cts.Token);
                }
                catch (Exception ex) when (ex is HttpRequestException or IOException or InvalidDataException or TaskCanceledException)
                {
                    SendState("error", $"Couldn't download the video encoder: {ex.Message}");
                    return;
                }
            }

            var screen = Screen.AllScreens.FirstOrDefault(s => s.DeviceName == monitorId) ?? Monitors.Find(_settings.MonitorDeviceName);
            var b = screen.Bounds;
            fps = Math.Clamp(fps, 15, 120);
            if (bitrate <= 0) bitrate = _usb ? 30_000_000 : 15_000_000;

            var sources = new List<CaptureSource>();
            var dxgi = Dxgi.Outputs().FirstOrDefault(d => string.Equals(d.DeviceName, screen.DeviceName, StringComparison.OrdinalIgnoreCase));
            if (dxgi.DeviceName is not null) sources.Add(new CaptureSource.Dda(dxgi.Adapter, dxgi.Index, dxgi.Width, dxgi.Height, fps));
            sources.Add(new CaptureSource.Gdi(b.X, b.Y, b.Width & ~1, b.Height & ~1, Math.Min(fps, 30)));

            SendState("starting", $"Starting the screen stream…");
            var streamer = new ScreenStreamer(ffmpeg, (au, key, pts) => _bulk.SendVideo(au, key, pts));
            streamer.Failed += why =>
            {
                Log.Info($"Screen stream stopped: {why}");
                if (ReferenceEquals(_streamer, streamer)) _ = StartStreamAsync(screen.DeviceName, fps, bitrate); // restart once more
            };
            var err = await streamer.StartAsync(sources, FfmpegArgs.Order(_settings.PreferredEncoder), bitrate, cts.Token);
            if (err is not null)
            {
                streamer.Dispose();
                SendState("error", $"Couldn't start the screen stream: {err}");
                return;
            }

            _streamer = streamer;
            _screen = screen;
            if (_settings.PreferredEncoder != streamer.Encoder)
            {
                _settings.PreferredEncoder = streamer.Encoder;
                _saveSettings();
            }
            var src = streamer.Source!;
            _bulk.Send(new JsonObject
            {
                ["t"] = "stream",
                ["state"] = "running",
                ["w"] = src.Width,
                ["h"] = src.Height,
                ["fps"] = src.Fps,
                ["encoder"] = streamer.Encoder.ToString(),
                ["monitor"] = screen.DeviceName,
                ["message"] = $"{src.Width}×{src.Height} · {src.Fps} fps · {streamer.Encoder}",
            });
            _cursorTimer.Change(0, 33);
            StatusChanged?.Invoke($"Mirroring {screen.DeviceName} ({streamer.Encoder})");
            MirrorChanged?.Invoke();
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            _startLock.Release();
        }
    }

    private void SendState(string state, string message)
    {
        _bulk.Send(new JsonObject { ["t"] = "stream", ["state"] = state, ["message"] = message });
        StatusChanged?.Invoke(message);
    }

    private void StopStream(string why)
    {
        _startCts?.Cancel();
        bool was = _streamer is not null;
        StopStreamCore();
        _input.ReleaseTouch();
        if (was)
        {
            if (_bulk.IsConnected) SendState("stopped", why);
            StatusChanged?.Invoke("");
        }
    }

    private void StopStreamCore()
    {
        _cursorTimer.Change(Timeout.Infinite, Timeout.Infinite);
        var s = _streamer;
        _streamer = null;
        s?.Dispose();
        if (_screen is not null)
        {
            _screen = null;
            MirrorChanged?.Invoke();
        }
    }

    private void SendCursor()
    {
        var bounds = MirroredBounds;
        if (bounds is not { } r || !Native.GetCursorPos(out var p)) return;
        if ((p.X, p.Y) == _lastCursor) return;
        _lastCursor = (p.X, p.Y);
        _bulk.Send(new JsonObject
        {
            ["t"] = "cursor",
            ["x"] = Math.Round((p.X - r.X) / (double)Math.Max(1, r.Width - 1), 5),
            ["y"] = Math.Round((p.Y - r.Y) / (double)Math.Max(1, r.Height - 1), 5),
        });
    }

    public void Dispose()
    {
        StopStream("PC app closed");
        _cursorTimer.Dispose();
        _input.Dispose();
    }
}
