using System.Diagnostics;
using Slate.Core;

namespace Slate;

/// <summary>Settings and status window. Changes apply immediately.</summary>
internal sealed class SettingsForm : Form
{
    private readonly TrayApp _app;
    private readonly Label _usbStatus = Wrapping();
    private readonly Label _wifiStatus = Wrapping();
    private readonly ComboBox _monitor = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 320 };
    private readonly Label _regionLabel = Wrapping();
    private readonly CheckBox _aspect = new() { Text = "Keep aspect ratio (tablet shows the active area)", AutoSize = true };
    private readonly ComboBox _barrel = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 320 };
    private readonly TrackBar _pressure = new() { Minimum = 0, Maximum = 20, TickFrequency = 5, Width = 240 };
    private readonly CurvePreview _curve = new() { Size = new Size(64, 64) };
    private readonly CheckBox _usbEnabled = new() { Text = "USB (lowest latency; needs USB debugging)", AutoSize = true };
    private readonly CheckBox _wifiEnabled = new() { Text = "Wi-Fi (same network)", AutoSize = true };
    private readonly Label _code = new() { AutoSize = true, Font = new Font(FontFamily.GenericMonospace, 20f, FontStyle.Bold) };
    private readonly Label _adb = Wrapping();
    private readonly Label _hint = Wrapping();
    private bool _loading;

    private static readonly (BarrelMode Mode, string Text)[] BarrelChoices =
    {
        (BarrelMode.RightClick, "Right-click"),
        (BarrelMode.Eraser, "Eraser while held"),
        (BarrelMode.Disabled, "Do nothing"),
    };

    public SettingsForm(TrayApp app)
    {
        _app = app;
        Text = "Slate settings";
        Icon = AppIcons.Connected;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        Padding = new Padding(12);

        var root = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false };
        Controls.Add(root);

        root.Controls.Add(Group("Status", _usbStatus, _wifiStatus));

        var regionButtons = Row(
            Button("Choose region…", (_, _) => PickRegion()),
            Button("Whole display", (_, _) => { _app.Settings.Region = null; Changed(); }));
        root.Controls.Add(Group("Map the tablet to", _monitor, _regionLabel, regionButtons, _aspect));

        _barrel.Items.AddRange(BarrelChoices.Select(c => (object)c.Text).ToArray());
        var pressureRow = Row(new Label { Text = "Soft", AutoSize = true, Anchor = AnchorStyles.Left }, _pressure,
            new Label { Text = "Firm", AutoSize = true, Anchor = AnchorStyles.Left }, _curve);
        root.Controls.Add(Group("Pen",
            new Label { Text = "S Pen button:", AutoSize = true }, _barrel,
            new Label { Text = "Pressure feel:", AutoSize = true }, pressureRow));

        var codeRow = Row(new Label { Text = "Wi-Fi pairing code:", AutoSize = true, Anchor = AnchorStyles.Left }, _code,
            Button("New code", (_, _) => { _app.NewPairCode(); LoadValues(); }));
        var adbRow = Row(Button("Choose adb…", (_, _) => ChooseAdb()),
            Button("Find automatically", (_, _) => { _app.Settings.AdbPath = null; Changed(); }));
        root.Controls.Add(Group("Connections", _usbEnabled, _wifiEnabled, codeRow, _adb, adbRow));

        root.Controls.Add(Group("First time?", Wrapping(
            "USB: on the tablet, turn on Developer options (Settings › About tablet › Software information › tap Build number 7 times), " +
            "then Developer options › USB debugging. Plug in, accept the prompt, and open Slate on the tablet.\n" +
            "Wi-Fi: open Slate on the tablet, tap this PC in the list and enter the code above. Allow Slate through the Windows firewall on private networks.\n" +
            "Art apps: use the Windows Ink pen mode (Krita: Settings › Tablet › Windows 8+ Pointer Input; Photoshop: Windows Ink is on by default).")));

        root.Controls.Add(Row(
            Button("Draw test stroke", (_, _) => _app.RunTestStroke()),
            Button("Open log folder", (_, _) => Process.Start(new ProcessStartInfo(Program.DataDir) { UseShellExecute = true })),
            Button("Close", (_, _) => Close())));
        root.Controls.Add(_hint);

        _monitor.SelectedIndexChanged += (_, _) =>
        {
            if (_loading || _monitor.SelectedIndex < 0) return;
            _app.Settings.MonitorDeviceName = Screen.AllScreens[_monitor.SelectedIndex].DeviceName;
            _app.Settings.Region = null;
            Changed();
        };
        _aspect.CheckedChanged += (_, _) => { if (!_loading) { _app.Settings.PreserveAspect = _aspect.Checked; Changed(); } };
        _barrel.SelectedIndexChanged += (_, _) =>
        {
            if (_loading || _barrel.SelectedIndex < 0) return;
            _app.Settings.BarrelMode = BarrelChoices[_barrel.SelectedIndex].Mode;
            Changed();
        };
        _pressure.ValueChanged += (_, _) =>
        {
            _curve.Gamma = SliderToGamma(_pressure.Value);
            if (_loading) return;
            _app.Settings.PressureGamma = _curve.Gamma;
            Changed();
        };
        _usbEnabled.CheckedChanged += (_, _) => { if (!_loading) { _app.Settings.UsbEnabled = _usbEnabled.Checked; Changed(); } };
        _wifiEnabled.CheckedChanged += (_, _) => { if (!_loading) { _app.Settings.WifiEnabled = _wifiEnabled.Checked; Changed(); } };

        RefreshMonitors();
        LoadValues();
        UpdateStatus();
    }

    // Slider 0..20 ↔ gamma 1/3..3, linear in log space, 10 = linear response.
    private static double SliderToGamma(int v) => Math.Pow(3, (v - 10) / 10.0);
    private static int GammaToSlider(double g) => Math.Clamp((int)Math.Round(10 + 10 * Math.Log(g) / Math.Log(3)), 0, 20);

    public void RefreshMonitors()
    {
        _loading = true;
        _monitor.Items.Clear();
        var screens = Screen.AllScreens;
        for (int i = 0; i < screens.Length; i++) _monitor.Items.Add(Monitors.Describe(screens[i], i));
        var current = Monitors.Find(_app.Settings.MonitorDeviceName);
        _monitor.SelectedIndex = Array.FindIndex(screens, s => s.DeviceName == current.DeviceName);
        _loading = false;
        LoadValues();
    }

    private void LoadValues()
    {
        _loading = true;
        var s = _app.Settings;
        var t = _app.Target;
        _regionLabel.Text = s.Region is null
            ? $"Whole display ({t.Width}×{t.Height} physical pixels)"
            : $"Region: {t.Width}×{t.Height} at {t.X},{t.Y}";
        _aspect.Checked = s.PreserveAspect;
        _barrel.SelectedIndex = Array.FindIndex(BarrelChoices, c => c.Mode == s.BarrelMode);
        _pressure.Value = GammaToSlider(s.PressureGamma);
        _curve.Gamma = s.PressureGamma;
        _usbEnabled.Checked = s.UsbEnabled;
        _wifiEnabled.Checked = s.WifiEnabled;
        _code.Text = s.PairCode.ToString("D4");
        var adb = Adb.Locate(s.AdbPath);
        _adb.Text = adb is null
            ? "adb: not found. Run scripts\\fetch-platform-tools.ps1 or install Android platform-tools."
            : $"adb: {adb.Path}";
        _loading = false;
    }

    public void UpdateStatus()
    {
        _usbStatus.Text = "USB: " + _app.Describe(UsbTransport.Name, _app.Settings.UsbEnabled);
        _wifiStatus.Text = "Wi-Fi: " + _app.Describe(WifiTransport.Name, _app.Settings.WifiEnabled);
    }

    public void ShowHint(string text) => _hint.Text = text;

    private void Changed()
    {
        _app.SettingsChanged();
        LoadValues();
        UpdateStatus();
    }

    private void PickRegion()
    {
        var screen = Monitors.Find(_app.Settings.MonitorDeviceName);
        Hide();
        try
        {
            var region = RegionPicker.Pick(screen);
            if (region is not null)
            {
                _app.Settings.Region = region;
                Changed();
            }
        }
        finally
        {
            Show();
            Activate();
        }
    }

    private void ChooseAdb()
    {
        using var dlg = new OpenFileDialog { Filter = "adb.exe|adb.exe", Title = "Find adb.exe (in Android platform-tools)" };
        if (dlg.ShowDialog(this) == DialogResult.OK)
        {
            _app.Settings.AdbPath = dlg.FileName;
            Changed();
        }
    }

    private static Label Wrapping(string text = "") => new() { Text = text, AutoSize = true, MaximumSize = new Size(520, 0) };

    private static Button Button(string text, EventHandler onClick)
    {
        var b = new Button { Text = text, AutoSize = true };
        b.Click += onClick;
        return b;
    }

    private static FlowLayoutPanel Row(params Control[] controls)
    {
        var row = new FlowLayoutPanel { FlowDirection = FlowDirection.LeftToRight, AutoSize = true, WrapContents = false, Margin = new Padding(0) };
        row.Controls.AddRange(controls);
        return row;
    }

    private static GroupBox Group(string title, params Control[] controls)
    {
        var inner = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false, Dock = DockStyle.Fill };
        inner.Controls.AddRange(controls);
        var g = new GroupBox { Text = title, AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink, MinimumSize = new Size(540, 0), Padding = new Padding(8) };
        g.Controls.Add(inner);
        return g;
    }

    /// <summary>Plots output pressure against input pressure.</summary>
    private sealed class CurvePreview : Control
    {
        private double _gamma = 1;

        public CurvePreview()
        {
            DoubleBuffered = true;
            ResizeRedraw = true;
        }

        public double Gamma
        {
            get => _gamma;
            set { _gamma = value; Invalidate(); }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            g.Clear(SystemColors.Window);
            var r = ClientRectangle;
            r.Inflate(-2, -2);
            g.DrawRectangle(SystemPens.ControlDark, r);
            var pts = new PointF[33];
            for (int i = 0; i < pts.Length; i++)
            {
                double x = i / (double)(pts.Length - 1);
                double y = Math.Pow(x, _gamma);
                pts[i] = new PointF(r.Left + (float)(x * r.Width), r.Bottom - (float)(y * r.Height));
            }
            using var pen = new Pen(SystemColors.Highlight, 2f);
            g.DrawLines(pen, pts);
        }
    }
}
