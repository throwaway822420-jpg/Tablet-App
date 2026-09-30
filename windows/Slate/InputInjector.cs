using System.ComponentModel;
using System.Runtime.InteropServices;
using Slate.Core;

namespace Slate;

/// <summary>Mouse, scroll, keyboard shortcuts and multi-touch from the tablet's navigation gestures.</summary>
internal sealed class InputInjector : IDisposable
{
    private readonly object _touchLock = new();
    private readonly TouchTracker _touch = new();
    private IntPtr _touchDevice;

    public void MouseMove(int x, int y) => SendMouse(x, y, 0);

    public void Click(int x, int y, bool right = false, int count = 1)
    {
        uint down = right ? Native.MOUSEEVENTF_RIGHTDOWN : Native.MOUSEEVENTF_LEFTDOWN;
        uint up = right ? Native.MOUSEEVENTF_RIGHTUP : Native.MOUSEEVENTF_LEFTUP;
        for (int i = 0; i < count; i++) SendMouse(x, y, down, up);
    }

    public void Button(int x, int y, bool down) =>
        SendMouse(x, y, down ? Native.MOUSEEVENTF_LEFTDOWN : Native.MOUSEEVENTF_LEFTUP);

    /// <summary>Scrolls at a point. dx/dy in wheel notches (fractions allowed; positive dy scrolls down the page).</summary>
    public void Scroll(int x, int y, double dx, double dy)
    {
        var inputs = new List<Native.INPUT> { MouseInput(x, y, Native.MOUSEEVENTF_MOVE | AbsoluteFlags, 0) };
        if (Math.Abs(dy) > 0.01) inputs.Add(MouseInput(0, 0, Native.MOUSEEVENTF_WHEEL, (int)Math.Round(-dy * 120)));
        if (Math.Abs(dx) > 0.01) inputs.Add(MouseInput(0, 0, Native.MOUSEEVENTF_HWHEEL, (int)Math.Round(dx * 120)));
        Send(inputs);
    }

    public bool Keys(string combo)
    {
        var strokes = KeyCombo.Strokes(combo);
        if (strokes is null) return false;
        Send(strokes.Select(k => new Native.INPUT
        {
            type = Native.INPUT_KEYBOARD,
            ki = new Native.KEYBDINPUT { wVk = k.VirtualKey, dwFlags = (k.Up ? Native.KEYEVENTF_KEYUP : 0) | (IsExtended(k.VirtualKey) ? Native.KEYEVENTF_EXTENDEDKEY : 0) },
        }).ToList());
        return true;
    }

    /// <summary>Real Windows touch: apps see fingers (native pinch-zoom, flick scrolling, touch keyboard…).</summary>
    public void Touch(IReadOnlyList<TouchContact> changes, PixelRect target)
    {
        lock (_touchLock)
        {
            if (_touchDevice == IntPtr.Zero)
            {
                _touchDevice = Native.CreateSyntheticPointerDevice(Native.PT_TOUCH, 10, Native.POINTER_FEEDBACK_DEFAULT);
                if (_touchDevice == IntPtr.Zero)
                {
                    Log.Info($"Couldn't create a touch device: {new Win32Exception(Marshal.GetLastWin32Error()).Message}");
                    return;
                }
            }
            InjectTouch(_touch.Update(changes, target));
        }
    }

    public void ReleaseTouch()
    {
        lock (_touchLock)
        {
            if (_touchDevice != IntPtr.Zero) InjectTouch(_touch.ReleaseAll());
        }
    }

    private void InjectTouch(List<TouchPoint> frame)
    {
        if (frame.Count == 0) return;
        var infos = frame.Select(p => new Native.POINTER_TYPE_INFO_TOUCH
        {
            type = Native.PT_TOUCH,
            touchInfo = new Native.POINTER_TOUCH_INFO
            {
                pointerInfo = new Native.POINTER_INFO
                {
                    pointerType = Native.PT_TOUCH,
                    pointerId = p.Id,
                    pointerFlags = (uint)p.Flags,
                    ptPixelLocation = new Native.POINT { X = p.X, Y = p.Y },
                },
                touchFlags = 0,
                touchMask = Native.TOUCH_MASK_CONTACTAREA,
                rcContact = new Native.RECT { Left = p.X - 4, Top = p.Y - 4, Right = p.X + 4, Bottom = p.Y + 4 },
            },
        }).ToArray();
        if (!Native.InjectSyntheticPointerInputTouch(_touchDevice, infos, (uint)infos.Length))
            Log.Info($"Touch injection failed: {new Win32Exception(Marshal.GetLastWin32Error()).Message}");
    }

    private const uint AbsoluteFlags = Native.MOUSEEVENTF_ABSOLUTE | Native.MOUSEEVENTF_VIRTUALDESK;

    private static void SendMouse(int x, int y, params uint[] buttons)
    {
        var inputs = new List<Native.INPUT> { MouseInput(x, y, Native.MOUSEEVENTF_MOVE | AbsoluteFlags, 0) };
        foreach (var b in buttons) if (b != 0) inputs.Add(MouseInput(x, y, b | Native.MOUSEEVENTF_MOVE | AbsoluteFlags, 0));
        Send(inputs);
    }

    private static Native.INPUT MouseInput(int x, int y, uint flags, int data)
    {
        int vx = Native.GetSystemMetrics(Native.SM_XVIRTUALSCREEN);
        int vy = Native.GetSystemMetrics(Native.SM_YVIRTUALSCREEN);
        int vw = Math.Max(2, Native.GetSystemMetrics(Native.SM_CXVIRTUALSCREEN));
        int vh = Math.Max(2, Native.GetSystemMetrics(Native.SM_CYVIRTUALSCREEN));
        return new Native.INPUT
        {
            type = Native.INPUT_MOUSE,
            mi = new Native.MOUSEINPUT
            {
                dx = (int)Math.Round((x - vx) * 65535.0 / (vw - 1)),
                dy = (int)Math.Round((y - vy) * 65535.0 / (vh - 1)),
                mouseData = unchecked((uint)data),
                dwFlags = flags,
            },
        };
    }

    private static void Send(List<Native.INPUT> inputs)
    {
        if (inputs.Count == 0) return;
        if (Native.SendInput((uint)inputs.Count, inputs.ToArray(), Marshal.SizeOf<Native.INPUT>()) != inputs.Count)
            Log.Info($"SendInput failed: {new Win32Exception(Marshal.GetLastWin32Error()).Message}");
    }

    private static bool IsExtended(ushort vk) => vk is >= 0x21 and <= 0x2E or 0x5B or 0x5C;

    public void Dispose()
    {
        ReleaseTouch();
        lock (_touchLock)
        {
            if (_touchDevice != IntPtr.Zero) Native.DestroySyntheticPointerDevice(_touchDevice);
            _touchDevice = IntPtr.Zero;
        }
    }
}
