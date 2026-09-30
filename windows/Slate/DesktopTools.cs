using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Slate.Core;

namespace Slate;

/// <summary>Finding, starting and focusing the Claude Desktop app's window.</summary>
internal static class DesktopWindow
{
    public static IntPtr Find()
    {
        foreach (var p in Process.GetProcessesByName("claude").Concat(Process.GetProcessesByName("Claude")))
        {
            try
            {
                if (p.MainWindowHandle != IntPtr.Zero && p.MainWindowTitle.Contains("Claude", StringComparison.OrdinalIgnoreCase))
                    return p.MainWindowHandle;
            }
            catch (InvalidOperationException)
            {
            }
            finally
            {
                p.Dispose();
            }
        }
        return IntPtr.Zero;
    }

    public static IntPtr FindOrLaunch(TimeSpan wait)
    {
        var w = Find();
        if (w != IntPtr.Zero) return w;
        Launch();
        var until = DateTime.UtcNow + wait;
        while (DateTime.UtcNow < until)
        {
            Thread.Sleep(500);
            w = Find();
            if (w != IntPtr.Zero)
            {
                Thread.Sleep(1500); // let the app finish loading its UI
                return w;
            }
        }
        return IntPtr.Zero;
    }

    private static void Launch()
    {
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        foreach (var exe in new[]
                 {
                     Path.Combine(local, "AnthropicClaude", "claude.exe"),
                     Path.Combine(local, "Programs", "Claude", "Claude.exe"),
                 })
        {
            if (File.Exists(exe))
            {
                Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true });
                return;
            }
        }
        try
        {
            // Store (MSIX) installs register the claude:// protocol.
            Process.Start(new ProcessStartInfo("claude://") { UseShellExecute = true });
        }
        catch (Win32Exception ex)
        {
            Log.Info($"Couldn't start Claude Desktop: {ex.Message}");
        }
    }

    /// <summary>Brings a window to the front (restoring it if minimised). Returns false if Windows refused.</summary>
    public static bool Activate(IntPtr hwnd)
    {
        if (Native.IsIconic(hwnd)) Native.ShowWindow(hwnd, Native.SW_RESTORE);
        // Windows only lets the app that last got input change the foreground window; a synthetic
        // Alt press counts as input, which is the documented-in-practice way to be allowed.
        KeyInput.Send(new List<KeyStroke> { KeyStroke.Vk(0x12, false), KeyStroke.Vk(0x12, true) });
        Native.SetForegroundWindow(hwnd);
        for (int i = 0; i < 20; i++)
        {
            if (Native.GetForegroundWindow() == hwnd) return true;
            Thread.Sleep(50);
        }
        return false;
    }
}

internal static class KeyInput
{
    public static void Send(List<KeyStroke> keys)
    {
        var inputs = keys.Select(k => new Native.INPUT
        {
            type = Native.INPUT_KEYBOARD,
            ki = new Native.KEYBDINPUT
            {
                wVk = k.VirtualKey,
                wScan = k.Character,
                dwFlags = (k.IsUnicode ? Native.KEYEVENTF_UNICODE : 0) | (k.Up ? Native.KEYEVENTF_KEYUP : 0),
            },
        }).ToArray();
        if (inputs.Length > 0) Native.SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Native.INPUT>());
    }

    public static void Combo(string combo)
    {
        if (KeyCombo.Strokes(combo) is { } s) Send(s);
    }
}

internal static class ClipboardTools
{
    /// <summary>Copies whatever formats the current clipboard can give, to put back later. UI thread only.</summary>
    public static DataObject Snapshot()
    {
        var copy = new DataObject();
        try
        {
            var current = Clipboard.GetDataObject();
            if (current is null) return copy;
            foreach (var format in current.GetFormats(autoConvert: false))
            {
                try
                {
                    var value = current.GetData(format, autoConvert: false);
                    if (value is not null) copy.SetData(format, value);
                }
                catch (Exception ex) when (ex is ExternalException or OutOfMemoryException or NotSupportedException or InvalidOperationException)
                {
                    // Some formats (delay-rendered, private) can't be copied; skip them.
                }
            }
        }
        catch (ExternalException)
        {
        }
        return copy;
    }

    public static bool Restore(DataObject data)
    {
        try
        {
            Clipboard.SetDataObject(data, copy: true, retryTimes: 5, retryDelay: 50);
            return true;
        }
        catch (Exception ex) when (ex is ExternalException or ThreadStateException)
        {
            Log.Info($"Couldn't set the clipboard: {ex.Message}");
            return false;
        }
    }
}
