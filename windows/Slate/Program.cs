using Slate.Core;

namespace Slate;

internal static class Program
{
    public static readonly string DataDir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Slate");

    public static string SettingsPath => Path.Combine(DataDir, "settings.json");
    public static string LogPath => Path.Combine(DataDir, "slate.log");

    [STAThread]
    private static int Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        Native.CheckLayouts();
        Log.ToFile(LogPath);

        if (args.Contains("--test-stroke", StringComparer.OrdinalIgnoreCase))
            return RunTestStroke();

        using var single = new Mutex(true, @"Local\Slate.PenBridge", out bool first);
        if (!first)
        {
            MessageBox.Show("Slate is already running. Look for its icon in the notification area.", "Slate");
            return 0;
        }

        SyntheticPen pen;
        try
        {
            pen = new SyntheticPen();
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Slate couldn't create a Windows pen device.\n\n{ex.Message}", "Slate", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }

        using (pen)
            Application.Run(new TrayApp(pen));
        return 0;
    }

    /// <summary><c>Slate.exe --test-stroke</c>: milestone 1, no tablet needed.</summary>
    private static int RunTestStroke()
    {
        Native.AttachConsole(Native.ATTACH_PARENT_PROCESS);
        try
        {
            var settings = SlateSettings.Load(SettingsPath);
            using var pen = new SyntheticPen();
            var target = Monitors.Target(settings);
            var router = new PenRouter(pen, () => new RouterOptions(target, BarrelMode.Disabled, 1.0));
            Console.WriteLine($"Target: {target.Width}x{target.Height} at {target.X},{target.Y} (physical pixels)");
            TestStroke.RunAsync(router, progress: Console.WriteLine).GetAwaiter().GetResult();
            return 0;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Test stroke failed: {ex.Message}");
            return 1;
        }
    }
}
