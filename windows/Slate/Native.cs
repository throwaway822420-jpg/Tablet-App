using System.Runtime.InteropServices;

namespace Slate;

// Win32 declarations for synthetic pen injection. Layouts are for 64-bit processes (x64 / ARM64);
// Program.Main checks the sizes before anything is injected.
internal static class Native
{
    public const uint PT_TOUCH = 2;
    public const uint PT_PEN = 3;
    public const uint TOUCH_MASK_CONTACTAREA = 0x1;

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT
    {
        public int Left, Top, Right, Bottom;
    }
    public const uint POINTER_FEEDBACK_DEFAULT = 1;

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct POINTER_INFO
    {
        public uint pointerType;
        public uint pointerId;
        public uint frameId;
        public uint pointerFlags;
        public IntPtr sourceDevice;
        public IntPtr hwndTarget;
        public POINT ptPixelLocation;
        public POINT ptHimetricLocation;
        public POINT ptPixelLocationRaw;
        public POINT ptHimetricLocationRaw;
        public uint dwTime;
        public uint historyCount;
        public int InputData;
        public uint dwKeyStates;
        public ulong PerformanceCount;
        public uint ButtonChangeType;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct POINTER_PEN_INFO
    {
        public POINTER_INFO pointerInfo;
        public uint penFlags;
        public uint penMask;
        public uint pressure;
        public uint rotation;
        public int tiltX;
        public int tiltY;
    }

    /// <summary>
    /// POINTER_TYPE_INFO: a type tag followed by a union of POINTER_TOUCH_INFO (144 bytes, the larger
    /// member) and POINTER_PEN_INFO. Total 152 bytes on 64-bit.
    /// </summary>
    [StructLayout(LayoutKind.Explicit, Size = 152)]
    public struct POINTER_TYPE_INFO
    {
        [FieldOffset(0)] public uint type;
        [FieldOffset(8)] public POINTER_PEN_INFO penInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct POINTER_TOUCH_INFO
    {
        public POINTER_INFO pointerInfo;
        public uint touchFlags;
        public uint touchMask;
        public RECT rcContact;
        public RECT rcContactRaw;
        public uint orientation;
        public uint pressure;
    }

    /// <summary>POINTER_TYPE_INFO viewed as its touch member (152 bytes on 64-bit).</summary>
    [StructLayout(LayoutKind.Explicit, Size = 152)]
    public struct POINTER_TYPE_INFO_TOUCH
    {
        [FieldOffset(0)] public uint type;
        [FieldOffset(8)] public POINTER_TOUCH_INFO touchInfo;
    }

    [DllImport("user32.dll", EntryPoint = "InjectSyntheticPointerInput", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool InjectSyntheticPointerInputTouch(IntPtr device, [In] POINTER_TYPE_INFO_TOUCH[] pointerInfo, uint count);

    [StructLayout(LayoutKind.Sequential)]
    public struct CURSORPOS
    {
        public int X, Y;
    }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool GetCursorPos(out CURSORPOS pt);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern IntPtr CreateSyntheticPointerDevice(uint pointerType, uint maxCount, uint mode);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool InjectSyntheticPointerInput(IntPtr device, ref POINTER_TYPE_INFO pointerInfo, uint count);

    [DllImport("user32.dll")]
    public static extern void DestroySyntheticPointerDevice(IntPtr device);

    // SendInput, for the barrel-button right-click.

    public const uint INPUT_MOUSE = 0;
    public const uint MOUSEEVENTF_MOVE = 0x0001;
    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;
    public const uint MOUSEEVENTF_WHEEL = 0x0800;
    public const uint MOUSEEVENTF_HWHEEL = 0x1000;
    public const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
    public const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    public const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    public const uint MOUSEEVENTF_VIRTUALDESK = 0x4000;
    public const uint MOUSEEVENTF_ABSOLUTE = 0x8000;

    [StructLayout(LayoutKind.Sequential)]
    public struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    public const uint INPUT_KEYBOARD = 1;
    public const uint KEYEVENTF_KEYUP = 0x0002;
    public const uint KEYEVENTF_UNICODE = 0x0004;
    public const ushort VK_RETURN = 0x0D;
    public const ushort VK_SHIFT = 0x10;

    [StructLayout(LayoutKind.Sequential)]
    public struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit, Size = 40)]
    public struct INPUT
    {
        [FieldOffset(0)] public uint type;
        [FieldOffset(8)] public MOUSEINPUT mi;
        [FieldOffset(8)] public KEYBDINPUT ki;
    }

    [DllImport("user32.dll", SetLastError = true)]
    public static extern uint SendInput(uint count, INPUT[] inputs, int size);

    public const int SM_XVIRTUALSCREEN = 76;
    public const int SM_YVIRTUALSCREEN = 77;
    public const int SM_CXVIRTUALSCREEN = 78;
    public const int SM_CYVIRTUALSCREEN = 79;

    [DllImport("user32.dll")]
    public static extern int GetSystemMetrics(int index);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool AttachConsole(int processId);

    public const int SW_RESTORE = 9;

    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool SetForegroundWindow(IntPtr hwnd);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool ShowWindow(IntPtr hwnd, int cmd);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool IsIconic(IntPtr hwnd);

    public const int ATTACH_PARENT_PROCESS = -1;

    [StructLayout(LayoutKind.Sequential)]
    public struct GUITHREADINFO
    {
        public uint cbSize;
        public uint flags;
        public IntPtr hwndActive;
        public IntPtr hwndFocus;
        public IntPtr hwndCapture;
        public IntPtr hwndMenuOwner;
        public IntPtr hwndMoveSize;
        public IntPtr hwndCaret;
        public RECT rcCaret;
    }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool GetGUIThreadInfo(uint threadId, ref GUITHREADINFO info);

    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);

    /// <summary>True if the foreground window's thread shows a Win32 text caret.</summary>
    public static bool ForegroundHasCaret()
    {
        var fg = GetForegroundWindow();
        if (fg == IntPtr.Zero) return false;
        var info = new GUITHREADINFO { cbSize = (uint)Marshal.SizeOf<GUITHREADINFO>() };
        return GetGUIThreadInfo(GetWindowThreadProcessId(fg, out _), ref info) && info.hwndCaret != IntPtr.Zero;
    }

    public static void CheckLayouts()
    {
        if (IntPtr.Size != 8)
            throw new PlatformNotSupportedException("Slate needs a 64-bit Windows process.");
        if (Marshal.SizeOf<POINTER_INFO>() != 96 || Marshal.SizeOf<POINTER_PEN_INFO>() != 120 ||
            Marshal.SizeOf<POINTER_TYPE_INFO>() != 152 || Marshal.SizeOf<INPUT>() != 40 ||
            Marshal.SizeOf<POINTER_TOUCH_INFO>() != 144 || Marshal.SizeOf<POINTER_TYPE_INFO_TOUCH>() != 152)
            throw new InvalidOperationException("Unexpected Win32 struct layout.");
    }
}
