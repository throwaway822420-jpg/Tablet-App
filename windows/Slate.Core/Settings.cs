using System.Text.Json;
using System.Text.Json.Serialization;

namespace Slate.Core;

public sealed class SlateSettings
{
    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter() },
    };

    /// <summary>Windows device name of the target monitor (e.g. <c>\\.\DISPLAY1</c>); null = primary.</summary>
    public string? MonitorDeviceName { get; set; }

    /// <summary>Part of the monitor to map onto, as fractions of its size; null = whole monitor.</summary>
    public NormalizedRegion? Region { get; set; }

    public bool PreserveAspect { get; set; } = true;
    public BarrelMode BarrelMode { get; set; } = BarrelMode.RightClick;

    /// <summary>Exponent applied to pressure; &lt;1 soft, 1 linear, &gt;1 firm.</summary>
    public double PressureGamma { get; set; } = 1.0;

    public bool UsbEnabled { get; set; } = true;
    public bool WifiEnabled { get; set; } = true;

    /// <summary>4-digit code the tablet must send to pair over Wi-Fi.</summary>
    public int PairCode { get; set; } = NewPairCode();

    /// <summary>Explicit path to adb; null = search next to Slate, PATH and the Android SDK.</summary>
    public string? AdbPath { get; set; }

    public static int NewPairCode() => Random.Shared.Next(0, 10000);

    public static SlateSettings Load(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                var s = JsonSerializer.Deserialize<SlateSettings>(File.ReadAllText(path), Json) ?? new();
                if (s.PairCode is < 0 or > 9999) s.PairCode = NewPairCode();
                if (s.PressureGamma is <= 0 or > 10) s.PressureGamma = 1.0;
                return s;
            }
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            Log.Info($"Couldn't read settings, using defaults: {ex.Message}");
        }
        return new SlateSettings();
    }

    public void Save(string path)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllText(path, JsonSerializer.Serialize(this, Json));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            Log.Info($"Couldn't save settings: {ex.Message}");
        }
    }
}
