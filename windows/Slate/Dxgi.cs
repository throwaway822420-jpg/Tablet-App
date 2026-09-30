using System.Runtime.InteropServices;

namespace Slate;

/// <summary>
/// Lists DXGI outputs so a Windows monitor (\\.\DISPLAYn) can be matched to the adapter/output
/// numbers ffmpeg's ddagrab capture needs.
/// </summary>
internal static class Dxgi
{
    public readonly record struct Output(int Adapter, int Index, string DeviceName, int X, int Y, int Width, int Height);

    public static List<Output> Outputs()
    {
        var list = new List<Output>();
        try
        {
            var iid = typeof(IDXGIFactory1).GUID;
            if (CreateDXGIFactory1(ref iid, out var factory) != 0 || factory is null) return list;
            try
            {
                for (uint a = 0; factory.EnumAdapters1(a, out var adapter) == 0; a++)
                {
                    try
                    {
                        for (uint o = 0; adapter.EnumOutputs(o, out var output) == 0; o++)
                        {
                            try
                            {
                                if (output.GetDesc(out var d) == 0 && d.AttachedToDesktop != 0)
                                {
                                    list.Add(new Output((int)a, (int)o, d.DeviceName, d.Left, d.Top, d.Right - d.Left, d.Bottom - d.Top));
                                }
                            }
                            finally
                            {
                                Marshal.ReleaseComObject(output);
                            }
                        }
                    }
                    finally
                    {
                        Marshal.ReleaseComObject(adapter);
                    }
                }
            }
            finally
            {
                Marshal.ReleaseComObject(factory);
            }
        }
        catch (Exception ex) when (ex is COMException or DllNotFoundException or EntryPointNotFoundException or InvalidCastException)
        {
            Slate.Core.Log.Info($"DXGI enumeration failed: {ex.Message}");
        }
        return list;
    }

    [DllImport("dxgi.dll")]
    private static extern int CreateDXGIFactory1(ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out IDXGIFactory1 factory);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DXGI_OUTPUT_DESC
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
        public int Left, Top, Right, Bottom;
        public int AttachedToDesktop;
        public uint Rotation;
        public IntPtr Monitor;
    }

    // Vtable order matters; methods Slate never calls are declared as placeholders.

    [ComImport, Guid("770aae78-f26f-4dba-a829-253c83d1b387"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDXGIFactory1
    {
        void SetPrivateData(); void SetPrivateDataInterface(); void GetPrivateData(); void GetParent();
        void EnumAdapters(); void MakeWindowAssociation(); void GetWindowAssociation(); void CreateSwapChain(); void CreateSoftwareAdapter();
        [PreserveSig] int EnumAdapters1(uint index, out IDXGIAdapter1 adapter);
        [PreserveSig] int IsCurrent();
    }

    [ComImport, Guid("29038f61-3839-4626-91fd-086879011a05"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDXGIAdapter1
    {
        void SetPrivateData(); void SetPrivateDataInterface(); void GetPrivateData(); void GetParent();
        [PreserveSig] int EnumOutputs(uint index, out IDXGIOutput output);
        void GetDesc(); void CheckInterfaceSupport();
        void GetDesc1();
    }

    [ComImport, Guid("ae02eedb-c735-4690-8d52-5a8dc20213aa"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDXGIOutput
    {
        void SetPrivateData(); void SetPrivateDataInterface(); void GetPrivateData(); void GetParent();
        [PreserveSig] int GetDesc(out DXGI_OUTPUT_DESC desc);
    }
}
