using Slate.Core;

namespace Slate;

/// <summary>A dimmed full-screen overlay on one display: drag a rectangle to choose the mapped region.</summary>
internal sealed class RegionPicker : Form
{
    private Point? _start;
    private Rectangle _selection;

    private RegionPicker(Screen screen)
    {
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;
        AutoScaleMode = AutoScaleMode.None;
        Bounds = screen.Bounds;
        TopMost = true;
        ShowInTaskbar = false;
        BackColor = Color.Black;
        Opacity = 0.55;
        DoubleBuffered = true;
        Cursor = Cursors.Cross;
        KeyPreview = true;
    }

    /// <returns>The chosen region as fractions of the display, or null if cancelled.</returns>
    public static NormalizedRegion? Pick(Screen screen)
    {
        using var form = new RegionPicker(screen);
        if (form.ShowDialog() != DialogResult.OK) return null;
        var c = form.ClientSize;
        var s = form._selection;
        var region = new NormalizedRegion((double)s.X / c.Width, (double)s.Y / c.Height, (double)s.Width / c.Width, (double)s.Height / c.Height);
        return region.IsValid ? region : null;
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.KeyCode == Keys.Escape) DialogResult = DialogResult.Cancel;
        base.OnKeyDown(e);
    }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        if (e.Button == MouseButtons.Right)
        {
            DialogResult = DialogResult.Cancel;
            return;
        }
        _start = e.Location;
        _selection = new Rectangle(e.Location, Size.Empty);
        Invalidate();
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        if (_start is not Point s) return;
        var p = new Point(Math.Clamp(e.X, 0, ClientSize.Width), Math.Clamp(e.Y, 0, ClientSize.Height));
        _selection = Rectangle.FromLTRB(Math.Min(s.X, p.X), Math.Min(s.Y, p.Y), Math.Max(s.X, p.X), Math.Max(s.Y, p.Y));
        Invalidate();
    }

    protected override void OnMouseUp(MouseEventArgs e)
    {
        if (_start is null) return;
        _start = null;
        if (_selection.Width > 20 && _selection.Height > 20) DialogResult = DialogResult.OK;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        using var font = new Font(SystemFonts.MessageBoxFont?.FontFamily ?? FontFamily.GenericSansSerif, 18f);
        TextRenderer.DrawText(g, "Drag to choose the area the tablet maps to. Esc or right-click cancels.", font,
            new Rectangle(0, 40, ClientSize.Width, 60), Color.White, TextFormatFlags.HorizontalCenter);
        if (_selection.Width > 0 && _selection.Height > 0)
        {
            using var fill = new SolidBrush(Color.FromArgb(90, 90, 160));
            g.FillRectangle(fill, _selection);
            using var pen = new Pen(Color.White, 3f);
            g.DrawRectangle(pen, _selection);
            TextRenderer.DrawText(g, $"{_selection.Width} × {_selection.Height}", font, _selection, Color.White,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
        }
    }
}
