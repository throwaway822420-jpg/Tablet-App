using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text.Json.Nodes;
using Slate.Core;
using Xunit;

namespace Slate.Core.Tests;

public class BulkTests
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(15);

    /// <summary>A Linux/Windows ffmpeg for pipeline tests, if one is available.</summary>
    internal static string? TestFfmpeg()
    {
        var env = Environment.GetEnvironmentVariable("SLATE_TEST_FFMPEG");
        if (!string.IsNullOrEmpty(env) && File.Exists(env)) return env;
        return FfmpegInstaller.Find();
    }

    private static async Task<BulkFrame> Next(Stream s, Func<BulkFrame, bool>? match = null)
    {
        var deadline = DateTime.UtcNow + Wait;
        while (DateTime.UtcNow < deadline)
        {
            var f = await Task.Run(() => BulkFrame.ReadFrom(s)).WaitAsync(Wait);
            if (match is null || match(f)) return f;
        }
        throw new TimeoutException();
    }

    [Fact]
    public void FramesRoundTrip()
    {
        var ms = new MemoryStream();
        BulkFrame.Json(new JsonObject { ["t"] = "keys", ["combo"] = "Ctrl+Z" }).WriteTo(ms);
        BulkFrame.Video(new byte[] { 0, 0, 0, 1, 9, 0xF0 }, keyframe: true, 1234).WriteTo(ms);
        BulkFrame.Blob(new JsonObject { ["t"] = "shot", ["w"] = 2 }, new byte[] { 1, 2, 3 }).WriteTo(ms);
        ms.Position = 0;
        Assert.Equal("Ctrl+Z", BulkFrame.ReadFrom(ms).AsJson()!.Str("combo"));
        var v = BulkFrame.ReadFrom(ms);
        Assert.Equal(BulkKind.Video, v.Kind);
        Assert.Equal(1, v.Body[0]);
        Assert.Equal(15, v.Body.Length);
        var blob = BulkFrame.ReadFrom(ms).AsBlob()!.Value;
        Assert.Equal(2, blob.Header.Int("w"));
        Assert.Equal(new byte[] { 1, 2, 3 }, blob.Data.ToArray());
        Assert.Throws<EndOfStreamException>(() => BulkFrame.ReadFrom(ms));
    }

    [Fact]
    public void SplitsAccessUnitsAcrossArbitraryChunks()
    {
        // AUD, SPS, PPS, IDR | AUD, P | AUD, P — with 3- and 4-byte start codes.
        byte[] stream =
        {
            0, 0, 0, 1, 9, 0x10, 0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 1, 0x65, 4, 4, 4,
            0, 0, 0, 1, 9, 0x30, 0, 0, 1, 0x41, 5, 5,
            0, 0, 0, 1, 9, 0x30, 0, 0, 1, 0x41, 6,
            0, 0, 0, 1, 9, 0x30,
        };
        for (int chunk = 1; chunk <= stream.Length; chunk++)
        {
            var s = new AccessUnitSplitter();
            var aus = new List<AccessUnitSplitter.AccessUnit>();
            for (int i = 0; i < stream.Length; i += chunk) aus.AddRange(s.Push(stream.AsSpan(i, Math.Min(chunk, stream.Length - i))));
            Assert.Equal(3, aus.Count);
            Assert.True(aus[0].Keyframe);
            Assert.False(aus[1].Keyframe);
            Assert.True(aus[0].Data.Length == 25, $"chunk {chunk}: first AU {aus[0].Data.Length} bytes: {string.Join(",", aus[0].Data)}");
            Assert.Equal(new byte[] { 0, 0, 0, 1, 9, 0x30, 0, 0, 1, 0x41, 5, 5 }, aus[1].Data);
        }
    }

    [Fact]
    public async Task UsbClientNeedsNoCodeAndGetsVideo()
    {
        using var server = new BulkServer(() => 1234, port: 0, bind: IPAddress.Loopback);
        var connected = new TaskCompletionSource<string>();
        var received = new TaskCompletionSource<JsonObject>();
        server.Connected += d => connected.TrySetResult(d);
        server.FrameReceived += f => { if (f.AsJson() is { } o) received.TrySetResult(o); };

        using var tcp = new TcpClient();
        await tcp.ConnectAsync(IPAddress.Loopback, server.Port);
        var s = tcp.GetStream();
        BulkFrame.Hello(-1, "TAB").WriteTo(s);
        Assert.Equal("TAB", await connected.Task.WaitAsync(Wait));

        BulkFrame.Json(new JsonObject { ["t"] = "stream.start" }).WriteTo(s);
        Assert.Equal("stream.start", (await received.Task.WaitAsync(Wait)).Str("t"));

        Assert.True(server.SendVideo(new byte[] { 0, 0, 1, 9, 0, 0, 1, 0x65 }, true, 42));
        var v = await Next(s);
        Assert.Equal(BulkKind.Video, v.Kind);
    }

    [Fact]
    public async Task WifiClientNeedsTheCode()
    {
        using var server = new BulkServer(() => 1234, port: 0, bind: IPAddress.Loopback, trusted: _ => false);
        using (var bad = new TcpClient())
        {
            await bad.ConnectAsync(IPAddress.Loopback, server.Port);
            BulkFrame.Hello(1111, "X").WriteTo(bad.GetStream());
            Assert.Equal("denied", (await Next(bad.GetStream())).AsJson()!.Str("t"));
        }
        Assert.False(server.IsConnected);

        var connected = new TaskCompletionSource<string>();
        server.Connected += d => connected.TrySetResult(d);
        using var good = new TcpClient();
        await good.ConnectAsync(IPAddress.Loopback, server.Port);
        BulkFrame.Hello(1234, "TAB").WriteTo(good.GetStream());
        Assert.Equal("TAB", await connected.Task.WaitAsync(Wait));
    }

    [Fact]
    public void BuildsLowLatencyArgs()
    {
        var args = FfmpegArgs.Build(new CaptureSource.Dda(1, 0, 2880, 1800, 60), VideoEncoder.Nvenc, 20_000_000);
        var line = FfmpegArgs.ToCommandLine(args);
        Assert.Contains("-init_hw_device d3d11va=slate:1 -filter_hw_device slate", line);
        Assert.Contains("ddagrab=output_idx=0:framerate=60:draw_mouse=1:dup_frames=1", line);
        Assert.DoesNotContain("hwdownload", line); // NVENC takes the GPU frames directly
        Assert.Contains("-bf 0", line);
        Assert.Contains("-g 60", line);
        Assert.EndsWith("-bsf:v h264_metadata=aud=insert -an -flush_packets 1 -f h264 pipe:1", line);

        var x264 = FfmpegArgs.ToCommandLine(FfmpegArgs.Build(new CaptureSource.Dda(0, 1, 1920, 1080, 60), VideoEncoder.X264, 12_000_000));
        Assert.Contains("-vf hwdownload,format=bgra,format=nv12", x264);
        Assert.Contains("-tune zerolatency", x264);

        var gdi = FfmpegArgs.ToCommandLine(FfmpegArgs.Build(new CaptureSource.Gdi(-1920, 0, 1920, 1080, 30), VideoEncoder.Mf, 8_000_000));
        Assert.Contains("-offset_x -1920 -offset_y 0 -video_size 1920x1080 -i desktop", gdi);

        Assert.Equal(new[] { VideoEncoder.X264, VideoEncoder.Nvenc, VideoEncoder.Amf, VideoEncoder.Qsv, VideoEncoder.Mf }, FfmpegArgs.Order(VideoEncoder.X264));
    }

    [Fact]
    public void FitsTheOutputToTheTablet()
    {
        Assert.Equal((2560, 1600), FfmpegArgs.FitSize(2880, 1800, 2560, 1600));
        Assert.Equal((2560, 1600), FfmpegArgs.FitSize(2880, 1800, 1600, 2560)); // a portrait limit still fits
        Assert.Equal((1728, 1080), FfmpegArgs.FitSize(2880, 1800, 1920, 1080)); // keeps 16:10, height-limited
        Assert.Equal((1920, 1080), FfmpegArgs.FitSize(3840, 2160, 1920, 1080));
        Assert.Null(FfmpegArgs.FitSize(1920, 1080, 2560, 1600));                 // never scales up
        Assert.Null(FfmpegArgs.FitSize(1920, 1080, 0, 0));
        var odd = FfmpegArgs.FitSize(1366, 768, 1000, 1000)!.Value;
        Assert.True(odd.W % 2 == 0 && odd.H % 2 == 0);
    }

    [Fact]
    public void ScalesOnTheGpuOrInMemory()
    {
        var dda = new CaptureSource.Dda(0, 0, 2880, 1800, 60);
        var gpu = FfmpegArgs.ToCommandLine(FfmpegArgs.Build(dda, VideoEncoder.Nvenc, 20_000_000, (1920, 1200)));
        Assert.Contains("-vf scale_d3d11=width=1920:height=1200", gpu);
        var gpuQsv = FfmpegArgs.ToCommandLine(FfmpegArgs.Build(dda, VideoEncoder.Qsv, 20_000_000, (1920, 1200)));
        Assert.Contains("-vf scale_d3d11=width=1920:height=1200,hwmap=derive_device=qsv,format=qsv", gpuQsv);
        var mem = FfmpegArgs.ToCommandLine(FfmpegArgs.Build(dda, VideoEncoder.Nvenc, 20_000_000, (1920, 1200), gpuScale: false));
        Assert.Contains("-vf hwdownload,format=bgra,scale=1920:1200:flags=bilinear,format=nv12", mem);
        var gdi = FfmpegArgs.ToCommandLine(FfmpegArgs.Build(new CaptureSource.Gdi(0, 0, 2880, 1800, 30), VideoEncoder.X264, 8_000_000, (1920, 1200)));
        Assert.Contains("-vf scale=1920:1200:flags=bilinear,format=nv12", gdi);
    }

    [Fact]
    public async Task StreamerScalesDown()
    {
        var ffmpeg = TestFfmpeg();
        if (ffmpeg is null) return;
        int frames = 0;
        using var streamer = new ScreenStreamer(ffmpeg, (_, _, _) => Interlocked.Increment(ref frames));
        var err = await streamer.StartAsync(new CaptureSource[] { new CaptureSource.Test(1600, 1000, 30) },
            new[] { VideoEncoder.X264 }, 4_000_000, CancellationToken.None, maxSize: (800, 800));
        Assert.Null(err);
        Assert.Equal((800, 500), streamer.OutputSize);
        await Task.Delay(1000);
        streamer.Stop();
        Assert.True(frames >= 15, $"only {frames} frames");
    }

    [Fact]
    public async Task StreamerFallsBackToAWorkingEncoderAndStartsWithAKeyframe()
    {
        var ffmpeg = TestFfmpeg();
        if (ffmpeg is null) return; // no ffmpeg on this machine (the pipeline test runs where one exists)

        var frames = new List<(int Len, bool Key)>();
        using var streamer = new ScreenStreamer(ffmpeg, (au, key, _) => { lock (frames) frames.Add((au.Length, key)); });
        var sw = Stopwatch.StartNew();
        // NVENC isn't available here, so it must fail and fall back to x264.
        var err = await streamer.StartAsync(new CaptureSource[] { new CaptureSource.Test(1280, 800, 30) },
            new[] { VideoEncoder.Nvenc, VideoEncoder.X264 }, 4_000_000, CancellationToken.None);
        Assert.Null(err);
        Assert.Equal(VideoEncoder.X264, streamer.Encoder);
        await Task.Delay(2500);
        streamer.Stop();
        lock (frames)
        {
            Assert.True(frames.Count >= 40, $"only {frames.Count} frames");
            Assert.True(frames[0].Key);
            Assert.True(frames.Count(f => f.Key) >= 2); // keyframe every second
        }
    }
}
