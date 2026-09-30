using System.Diagnostics;

namespace Slate.Core;

public sealed record AdbDevice(string Serial, string State)
{
    public bool IsReady => State == "device";
}

/// <summary>Thin wrapper around the adb command-line tool.</summary>
public sealed class Adb
{
    private Adb(string path) => Path = path;

    public string Path { get; }

    /// <summary>
    /// Finds adb: the explicit path if given, then a <c>platform-tools</c> folder (or adb) next to Slate,
    /// then PATH, then the default Android SDK locations.
    /// </summary>
    public static Adb? Locate(string? explicitPath)
    {
        foreach (var candidate in Candidates(explicitPath))
        {
            if (!string.IsNullOrWhiteSpace(candidate) && File.Exists(candidate)) return new Adb(candidate);
        }
        return null;
    }

    private static IEnumerable<string?> Candidates(string? explicitPath)
    {
        string exe = OperatingSystem.IsWindows() ? "adb.exe" : "adb";
        yield return explicitPath;
        yield return System.IO.Path.Combine(AppContext.BaseDirectory, "platform-tools", exe);
        yield return System.IO.Path.Combine(AppContext.BaseDirectory, exe);
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(System.IO.Path.PathSeparator))
        {
            if (dir.Length > 0) yield return System.IO.Path.Combine(dir.Trim('"'), exe);
        }
        foreach (var sdkVar in new[] { "ANDROID_HOME", "ANDROID_SDK_ROOT" })
        {
            var sdk = Environment.GetEnvironmentVariable(sdkVar);
            if (!string.IsNullOrEmpty(sdk)) yield return System.IO.Path.Combine(sdk, "platform-tools", exe);
        }
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (local.Length > 0) yield return System.IO.Path.Combine(local, "Android", "Sdk", "platform-tools", exe);
    }

    public async Task<(int ExitCode, string Output)> RunAsync(string arguments, CancellationToken ct, int timeoutMs = 8000)
    {
        var psi = new ProcessStartInfo(Path, arguments)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        using var proc = Process.Start(psi) ?? throw new InvalidOperationException("adb didn't start");
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(timeoutMs);
        var stdout = proc.StandardOutput.ReadToEndAsync(timeout.Token);
        var stderr = proc.StandardError.ReadToEndAsync(timeout.Token);
        try
        {
            await proc.WaitForExitAsync(timeout.Token);
            return (proc.ExitCode, (await stdout) + (await stderr));
        }
        catch (OperationCanceledException)
        {
            try { proc.Kill(entireProcessTree: true); } catch (InvalidOperationException) { }
            if (ct.IsCancellationRequested) throw;
            return (-1, "adb timed out");
        }
    }

    public async Task<IReadOnlyList<AdbDevice>> DevicesAsync(CancellationToken ct)
    {
        var (code, output) = await RunAsync("devices", ct);
        return code == 0 ? ParseDevices(output) : Array.Empty<AdbDevice>();
    }

    public static IReadOnlyList<AdbDevice> ParseDevices(string output)
    {
        var list = new List<AdbDevice>();
        foreach (var raw in output.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0 || line.StartsWith("List of devices") || line.StartsWith('*')) continue;
            var parts = line.Split(new[] { '\t', ' ' }, 2, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 2) list.Add(new AdbDevice(parts[0], parts[1].Trim()));
        }
        return list;
    }

    /// <summary><c>adb -s serial forward tcp:port tcp:port</c>, so 127.0.0.1:port on the PC reaches the tablet.</summary>
    public async Task<bool> ForwardAsync(string serial, int port, CancellationToken ct)
    {
        var (code, output) = await RunAsync($"-s {serial} forward tcp:{port} tcp:{port}", ct);
        if (code != 0) Log.Info($"adb forward failed: {output.Trim()}");
        return code == 0;
    }

    public async Task RemoveForwardAsync(int port, CancellationToken ct)
    {
        await RunAsync($"forward --remove tcp:{port}", ct, 3000);
    }
}
