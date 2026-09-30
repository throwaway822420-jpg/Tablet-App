using System.ComponentModel;
using System.Runtime.InteropServices;
using Slate.Core;

namespace Slate;

/// <summary>A Windows synthetic pen device (Windows 10 1809+). Apps see it as a real Windows Ink pen.</summary>
internal sealed class SyntheticPen : IPointerInjector, IDisposable
{
    private readonly object _lock = new();
    private IntPtr _device;
    private long _failures;

    public SyntheticPen()
    {
        _device = Native.CreateSyntheticPointerDevice(Native.PT_PEN, 1, Native.POINTER_FEEDBACK_DEFAULT);
        if (_device == IntPtr.Zero)
            throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateSyntheticPointerDevice failed (needs Windows 10 1809 or later)");
    }

    public bool Inject(in InjectFrame f)
    {
        var info = new Native.POINTER_TYPE_INFO
        {
            type = Native.PT_PEN,
            penInfo = new Native.POINTER_PEN_INFO
            {
                pointerInfo = new Native.POINTER_INFO
                {
                    pointerType = Native.PT_PEN,
                    pointerFlags = (uint)f.Flags,
                    ptPixelLocation = new Native.POINT { X = f.X, Y = f.Y },
                    ButtonChangeType = (uint)f.Change,
                },
                penFlags = (uint)f.PenFlags,
                penMask = (uint)f.Mask,
                pressure = f.Pressure,
                rotation = f.Rotation,
                tiltX = f.TiltX,
                tiltY = f.TiltY,
            },
        };

        lock (_lock)
        {
            if (_device == IntPtr.Zero) return false;
            if (Native.InjectSyntheticPointerInput(_device, ref info, 1)) return true;
        }

        int error = Marshal.GetLastWin32Error();
        long n = Interlocked.Increment(ref _failures);
        if (n <= 5 || n % 500 == 0)
            Log.Info($"InjectSyntheticPointerInput failed ({n} total): {new Win32Exception(error).Message} [flags={f.Flags}, at {f.X},{f.Y}]");
        return false;
    }

    public void RightClick(int x, int y)
    {
        int vx = Native.GetSystemMetrics(Native.SM_XVIRTUALSCREEN);
        int vy = Native.GetSystemMetrics(Native.SM_YVIRTUALSCREEN);
        int vw = Math.Max(2, Native.GetSystemMetrics(Native.SM_CXVIRTUALSCREEN));
        int vh = Math.Max(2, Native.GetSystemMetrics(Native.SM_CYVIRTUALSCREEN));
        int ax = (int)Math.Round((x - vx) * 65535.0 / (vw - 1));
        int ay = (int)Math.Round((y - vy) * 65535.0 / (vh - 1));
        const uint move = Native.MOUSEEVENTF_MOVE | Native.MOUSEEVENTF_ABSOLUTE | Native.MOUSEEVENTF_VIRTUALDESK;

        var inputs = new[]
        {
            new Native.INPUT { type = Native.INPUT_MOUSE, mi = new Native.MOUSEINPUT { dx = ax, dy = ay, dwFlags = move | Native.MOUSEEVENTF_RIGHTDOWN } },
            new Native.INPUT { type = Native.INPUT_MOUSE, mi = new Native.MOUSEINPUT { dx = ax, dy = ay, dwFlags = move | Native.MOUSEEVENTF_RIGHTUP } },
        };
        if (Native.SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Native.INPUT>()) != inputs.Length)
            Log.Info($"SendInput right-click failed: {new Win32Exception(Marshal.GetLastWin32Error()).Message}");
    }

    public void Dispose()
    {
        lock (_lock)
        {
            if (_device == IntPtr.Zero) return;
            Native.DestroySyntheticPointerDevice(_device);
            _device = IntPtr.Zero;
        }
    }
}
