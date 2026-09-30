using System.Diagnostics;
using System.Globalization;
using System.IO.Compression;

namespace Slate.Core;

public enum VideoEncoder
{
    Nvenc,  // NVIDIA
    Amf,    // AMD
    Qsv,    // Intel Quick Sync
    Mf,     // Windows Media Foundation (whatever hardware encoder Windows offers)
    X264,   // software fallback
}

/// <summary>What to capture.</summary>
public abstract record CaptureSource(int Width, int Height, int Fps)
{
    /// <summary>DXGI desktop duplication of one output of one GPU: fast, GPU-resident frames.</summary>
    public sealed record Dda(int Adapter, int Output, int Width, int Height, int Fps) : CaptureSource(Width, Height, Fps);

    /// <summary>GDI capture of a desktop rectangle: slower, but works everywhere (RDP, old GPUs).</summary>
    public sealed record Gdi(int X, int Y, int Width, int Height, int Fps) : CaptureSource(Width, Height, Fps);

    /// <summary>A synthetic test pattern (tests, and checking the pipeline without a desktop).</summary>
    public sealed record Test(int Width, int Height, int Fps) : CaptureSource(Width, Height, Fps);
}

public static class FfmpegArgs
{
    /// <summary>Encoders to try, fastest first. A remembered working encoder goes to the front.</summary>
    public static IReadOnlyList<VideoEncoder> Order(VideoEncoder? preferred)
    {
        var all = new List<VideoEncoder> { VideoEncoder.Nvenc, VideoEncoder.Amf, VideoEncoder.Qsv, VideoEncoder.Mf, VideoEncoder.X264 };
        if (preferred is { } p)
        {
            all.Remove(p);
            all.Insert(0, p);
        }
        return all;
    }

    /// <summary>
    /// ffmpeg arguments for a low-latency H.264 stream on stdout: no B-frames, a keyframe every
    /// second (so a dropped frame heals quickly), constant bitrate, and an access unit delimiter
    /// before every frame so Slate can split frames without parsing slices.
    /// </summary>
    public static List<string> Build(CaptureSource src, VideoEncoder enc, int bitrate)
    {
        var a = new List<string> { "-hide_banner", "-loglevel", "warning", "-nostdin" };
        bool gpuFrames = false;
        string fps = src.Fps.ToString(CultureInfo.InvariantCulture);
        switch (src)
        {
            case CaptureSource.Dda d:
                a.AddRange(new[] { "-init_hw_device", $"d3d11va=slate:{d.Adapter}", "-filter_hw_device", "slate" });
                a.AddRange(new[] { "-f", "lavfi", "-i", $"ddagrab=output_idx={d.Output}:framerate={fps}:draw_mouse=1:dup_frames=1" });
                gpuFrames = true;
                break;
            case CaptureSource.Gdi g:
                a.AddRange(new[]
                {
                    "-f", "gdigrab", "-framerate", fps, "-draw_mouse", "1",
                    "-offset_x", g.X.ToString(CultureInfo.InvariantCulture), "-offset_y", g.Y.ToString(CultureInfo.InvariantCulture),
                    "-video_size", $"{g.Width}x{g.Height}", "-i", "desktop",
                });
                break;
            case CaptureSource.Test t:
                a.AddRange(new[] { "-re", "-f", "lavfi", "-i", $"testsrc2=size={t.Width}x{t.Height}:rate={fps}" });
                break;
        }

        // Frame format: hardware encoders take the GPU frames directly; the others need them in memory.
        string? vf = (gpuFrames, enc) switch
        {
            (true, VideoEncoder.Nvenc or VideoEncoder.Amf) => null,
            (true, VideoEncoder.Qsv) => "hwmap=derive_device=qsv,format=qsv",
            (true, _) => "hwdownload,format=bgra,format=nv12",
            (false, _) => "format=nv12",
        };
        if (vf is not null) a.AddRange(new[] { "-vf", vf });

        string b = bitrate.ToString(CultureInfo.InvariantCulture);
        string buf = Math.Max(bitrate / Math.Max(1, src.Fps) * 2, 100_000).ToString(CultureInfo.InvariantCulture);
        string gop = src.Fps.ToString(CultureInfo.InvariantCulture);
        a.AddRange(enc switch
        {
            VideoEncoder.Nvenc => new[] { "-c:v", "h264_nvenc", "-preset", "p1", "-tune", "ull", "-zerolatency", "1", "-delay", "0", "-rc", "cbr", "-forced-idr", "1" },
            VideoEncoder.Amf => new[] { "-c:v", "h264_amf", "-usage", "ultralowlatency", "-quality", "speed", "-rc", "cbr" },
            VideoEncoder.Qsv => new[] { "-c:v", "h264_qsv", "-preset", "veryfast", "-async_depth", "1" },
            VideoEncoder.Mf => new[] { "-c:v", "h264_mf", "-hw_encoding", "1", "-scenario", "display_remoting", "-rate_control", "cbr" },
            _ => new[] { "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency" },
        });
        a.AddRange(new[] { "-b:v", b, "-maxrate", b, "-bufsize", buf, "-g", gop, "-bf", "0" });
        a.AddRange(new[] { "-bsf:v", "h264_metadata=aud=insert", "-an", "-flush_packets", "1", "-f", "h264", "pipe:1" });
        return a;
    }

    public static string ToCommandLine(IEnumerable<string> args) =>
        string.Join(" ", args.Select(x => x.Contains(' ') || x.Contains('"') ? "\"" + x.Replace("\"", "\\\"") + "\"" : x));
}

/// <summary>Finds ffmpeg, or downloads it once (Windows essentials build from gyan.dev, ~115 MB).</summary>
public static class FfmpegInstaller
{
    public const string DownloadUrl = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";

    public static string InstallDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Slate", "ffmpeg");

    public static string? Find(string? explicitPath = null)
    {
        string exe = OperatingSystem.IsWindows() ? "ffmpeg.exe" : "ffmpeg";
        var candidates = new List<string?>
        {
            explicitPath,
            Path.Combine(AppContext.BaseDirectory, exe),
            Path.Combine(AppContext.BaseDirectory, "ffmpeg", exe),
            Path.Combine(InstallDir, exe),
        };
        candidates.AddRange((Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator)
            .Where(d => d.Length > 0).Select(d => Path.Combine(d.Trim('"'), exe)));
        return candidates.FirstOrDefault(c => !string.IsNullOrWhiteSpace(c) && File.Exists(c));
    }

    /// <summary>Downloads and unpacks ffmpeg.exe into <see cref="InstallDir"/>; reports 0–100.</summary>
    public static async Task<string> DownloadAsync(Action<int> progress, CancellationToken ct, string url = DownloadUrl)
    {
        Directory.CreateDirectory(InstallDir);
        string zipPath = Path.Combine(InstallDir, "download.zip");
        using (var http = new HttpClient { Timeout = TimeSpan.FromMinutes(30) })
        using (var resp = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct))
        {
            resp.EnsureSuccessStatusCode();
            long total = resp.Content.Headers.ContentLength ?? 0;
            await using var src = await resp.Content.ReadAsStreamAsync(ct);
            await using var dst = File.Create(zipPath);
            var buf = new byte[1 << 16];
            long done = 0;
            int lastPct = -1;
            int n;
            while ((n = await src.ReadAsync(buf, ct)) > 0)
            {
                await dst.WriteAsync(buf.AsMemory(0, n), ct);
                done += n;
                int pct = total > 0 ? (int)(done * 100 / total) : 0;
                if (pct != lastPct) progress(lastPct = pct);
            }
        }

        string exe = OperatingSystem.IsWindows() ? "ffmpeg.exe" : "ffmpeg";
        string target = Path.Combine(InstallDir, exe);
        using (var zip = ZipFile.OpenRead(zipPath))
        {
            var entry = zip.Entries.FirstOrDefault(e => e.FullName.EndsWith("/bin/" + exe, StringComparison.OrdinalIgnoreCase))
                ?? throw new InvalidDataException("The ffmpeg download didn't contain " + exe);
            string tmp = target + ".part";
            entry.ExtractToFile(tmp, overwrite: true);
            File.Move(tmp, target, overwrite: true);
        }
        File.Delete(zipPath);
        return target;
    }
}

/// <summary>
/// Runs ffmpeg and turns its stdout into access units. <see cref="StartAsync"/> tries encoders in
/// order until one produces a keyframe, so the fastest one that works on this PC is used.
/// </summary>
public sealed class ScreenStreamer : IDisposable
{
    private readonly string _ffmpeg;
    private readonly Action<byte[], bool, long> _onFrame;
    private Process? _proc;
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private readonly Queue<string> _stderr = new();
    private volatile bool _stopping;

    public ScreenStreamer(string ffmpegPath, Action<byte[], bool, long> onFrame)
    {
        _ffmpeg = ffmpegPath;
        _onFrame = onFrame;
    }

    public VideoEncoder? Encoder { get; private set; }
    public CaptureSource? Source { get; private set; }

    /// <summary>Raised when ffmpeg stops unexpectedly, with its last error lines.</summary>
    public event Action<string>? Failed;

    /// <summary>Tries each source (first working one wins) with each encoder in order.</summary>
    /// <returns>A description of the failure, or null on success.</returns>
    public async Task<string?> StartAsync(IEnumerable<CaptureSource> sources, IReadOnlyList<VideoEncoder> encoders, int bitrate, CancellationToken ct)
    {
        Stop();
        string lastError = "No capture source.";
        foreach (var src in sources)
        {
            foreach (var enc in encoders)
            {
                ct.ThrowIfCancellationRequested();
                var err = await TryStartAsync(src, enc, bitrate, ct);
                if (err is null)
                {
                    Encoder = enc;
                    Source = src;
                    Log.Info($"Screen stream: {src} with {enc} at {bitrate / 1_000_000.0:0.#} Mb/s");
                    return null;
                }
                Log.Info($"Screen stream: {enc} on {src.GetType().Name} didn't start: {err}");
                lastError = err;
            }
        }
        return lastError;
    }

    private async Task<string?> TryStartAsync(CaptureSource src, VideoEncoder enc, int bitrate, CancellationToken ct)
    {
        var psi = new ProcessStartInfo(_ffmpeg)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        foreach (var arg in FfmpegArgs.Build(src, enc, bitrate)) psi.ArgumentList.Add(arg);
        var proc = Process.Start(psi);
        if (proc is null) return "ffmpeg didn't start";
        lock (_stderr) _stderr.Clear();
        _stopping = false;

        var firstKey = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _ = Task.Run(() => ReadErrors(proc));
        _ = Task.Run(() => Pump(proc, firstKey));

        var winner = await Task.WhenAny(firstKey.Task, Task.Delay(6000, ct));
        if (winner == firstKey.Task && firstKey.Task.Result)
        {
            _proc = proc;
            return null;
        }
        _stopping = true;
        Kill(proc);
        lock (_stderr) return _stderr.Count > 0 ? string.Join(" | ", _stderr) : "no video within 6 s";
    }

    private void Pump(Process proc, TaskCompletionSource<bool> firstKey)
    {
        var splitter = new AccessUnitSplitter();
        var buf = new byte[1 << 16];
        var stdout = proc.StandardOutput.BaseStream;
        bool started = false;
        try
        {
            int n;
            while ((n = stdout.Read(buf, 0, buf.Length)) > 0)
            {
                foreach (var au in splitter.Push(buf.AsSpan(0, n)))
                {
                    if (!started)
                    {
                        if (!au.Keyframe) continue;
                        started = true;
                        firstKey.TrySetResult(true);
                    }
                    if (ReferenceEquals(_proc, proc) || !_stopping) _onFrame(au.Data, au.Keyframe, _clock.ElapsedTicks * 1_000_000 / Stopwatch.Frequency);
                }
            }
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException)
        {
        }
        firstKey.TrySetResult(false);
        if (started && ReferenceEquals(_proc, proc) && !_stopping)
        {
            string why;
            lock (_stderr) why = string.Join(" | ", _stderr);
            Failed?.Invoke(why.Length > 0 ? why : "ffmpeg stopped");
        }
    }

    private void ReadErrors(Process proc)
    {
        try
        {
            string? line;
            while ((line = proc.StandardError.ReadLine()) is not null)
            {
                lock (_stderr)
                {
                    _stderr.Enqueue(line);
                    while (_stderr.Count > 6) _stderr.Dequeue();
                }
            }
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException or InvalidOperationException)
        {
        }
    }

    public void Stop()
    {
        _stopping = true;
        var p = _proc;
        _proc = null;
        if (p is not null) Kill(p);
    }

    private static void Kill(Process p)
    {
        try
        {
            if (!p.HasExited) p.Kill(entireProcessTree: true);
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
        }
        p.Dispose();
    }

    public void Dispose() => Stop();
}
