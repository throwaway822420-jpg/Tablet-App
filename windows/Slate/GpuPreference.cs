using Microsoft.Win32;
using Slate.Core;

namespace Slate;

/// <summary>
/// Windows' per-app graphics preference (Settings › Display › Graphics), as stored in the registry.
/// Used only for Slate's own ffmpeg.exe, and can be changed back there.
/// </summary>
internal static class GpuPreference
{
    private const string Key = @"Software\Microsoft\DirectX\UserGpuPreferences";
    private const string PowerSaving = "GpuPreference=1;";

    /// <summary>Sets "Power saving" (the integrated GPU) for one program. Returns true if it changed anything.</summary>
    public static bool SetPowerSaving(string exePath)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(Key, writable: true);
            if (key.GetValue(exePath) is string current && current.Contains("GpuPreference=1", StringComparison.Ordinal)) return false;
            key.SetValue(exePath, PowerSaving, RegistryValueKind.String);
            return true;
        }
        catch (Exception ex) when (ex is UnauthorizedAccessException or System.Security.SecurityException or IOException)
        {
            Log.Info($"Couldn't set the graphics preference for {exePath}: {ex.Message}");
            return false;
        }
    }
}
