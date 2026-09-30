namespace Slate.Core;

public static class Log
{
    private static readonly object Gate = new();
    private static StreamWriter? _file;

    public static event Action<string>? Line;

    /// <summary>Also append log lines to a file (best effort).</summary>
    public static void ToFile(string path)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            lock (Gate) _file = new StreamWriter(path, append: false) { AutoFlush = true };
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    public static void Info(string message)
    {
        var line = $"{DateTime.Now:HH:mm:ss.fff} {message}";
        lock (Gate)
        {
            try
            {
                _file?.WriteLine(line);
            }
            catch (IOException)
            {
            }
        }
        System.Diagnostics.Debug.WriteLine(line);
        Line?.Invoke(line);
    }
}
