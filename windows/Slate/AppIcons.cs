using System.Drawing.Drawing2D;

namespace Slate;

/// <summary>Tray icons drawn at startup: a tablet outline with a pen, and a status dot.</summary>
internal static class AppIcons
{
    public static readonly Icon Idle = Make(Color.FromArgb(150, 150, 150));
    public static readonly Icon Searching = Make(Color.FromArgb(230, 160, 30));
    public static readonly Icon Connected = Make(Color.FromArgb(40, 180, 90));

    private static Icon Make(Color dot)
    {
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using var body = new SolidBrush(Color.FromArgb(40, 44, 52));
            using var edge = new Pen(Color.FromArgb(220, 220, 220), 2f);
            using var path = RoundedRect(new RectangleF(2, 5, 26, 20), 4);
            g.FillPath(body, path);
            g.DrawPath(edge, path);
            using var pen = new Pen(Color.White, 3f) { StartCap = LineCap.Triangle, EndCap = LineCap.Round };
            g.DrawLine(pen, 9, 19, 21, 9);
            using var dotBrush = new SolidBrush(dot);
            g.FillEllipse(dotBrush, 20, 20, 11, 11);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }

    private static GraphicsPath RoundedRect(RectangleF r, float radius)
    {
        var p = new GraphicsPath();
        float d = radius * 2;
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }
}
