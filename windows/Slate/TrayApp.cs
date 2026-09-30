using System.Net.Sockets;
using Microsoft.Win32;
using Slate.Core;
using LinkState = Slate.Core.LinkState;

namespace Slate;

/// <summary>The notification-area app: owns the pen, the router, the transports and the settings.</summary>
internal sealed class TrayApp : ApplicationContext
{
    private readonly Control _ui = new();
    private readonly PenRouter _router;
    private readonly Bridge _bridge;
    private readonly NotifyIcon _tray;
    private readonly ToolStripMenuItem _usbItem = new() { Enabled = false };
    private readonly ToolStripMenuItem _wifiItem = new() { Enabled = false };
    private readonly ToolStripMenuItem _codeItem = new() { Enabled = false };
    private readonly Dictionary<string, LinkStatus> _statuses = new();

    private volatile RouterOptions _options;
    private volatile float _aspect;
    private UsbTransport? _usb;
    private WifiTransport? _wifi;
    private SettingsForm? _form;
    private LinkState _overall = LinkState.Off;
    private bool _testRunning;

    public TrayApp(SyntheticPen pen)
    {
        Settings = SlateSettings.Load(Program.SettingsPath);
        Settings.Save(Program.SettingsPath); // keep the generated pairing code stable across runs
        _ui.CreateControl();

        _options = new RouterOptions(new PixelRect(0, 0, 1, 1), BarrelMode.Disabled, 1.0);
        _router = new PenRouter(pen, () => _options);
        _bridge = new Bridge(_router, () => _aspect, Environment.MachineName);
        _bridge.StatusChanged += s => Post(() => OnStatus(s));
        UpdateTarget();
        SystemEvents.DisplaySettingsChanged += OnDisplaySettingsChanged;

        var menu = new ContextMenuStrip();
        menu.Items.Add(_usbItem);
        menu.Items.Add(_wifiItem);
        menu.Items.Add(_codeItem);
        menu.Items.Add(new ToolStripSeparator());
        var settingsItem = new ToolStripMenuItem("Settings…", null, (_, _) => ShowSettings());
        settingsItem.Font = new Font(settingsItem.Font, FontStyle.Bold);
        menu.Items.Add(settingsItem);
        menu.Items.Add(new ToolStripMenuItem("Draw test stroke", null, (_, _) => RunTestStroke()));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Exit", null, (_, _) => Exit()));

        _tray = new NotifyIcon
        {
            Icon = AppIcons.Idle,
            Text = "Slate",
            ContextMenuStrip = menu,
            Visible = true,
        };
        _tray.DoubleClick += (_, _) => ShowSettings();

        RefreshMenu();
        ApplyTransports();
        _tray.ShowBalloonTip(3000, "Slate is running",
            $"Open Slate on the tablet. USB: plug in with USB debugging on. Wi-Fi code: {Settings.PairCode:D4}.", ToolTipIcon.Info);
    }

    public SlateSettings Settings { get; }

    public IReadOnlyCollection<LinkStatus> Statuses => _statuses.Values;

    public PixelRect Target => _options.Target;

    /// <summary>Called by the settings window after it changes <see cref="Settings"/>.</summary>
    public void SettingsChanged()
    {
        Settings.Save(Program.SettingsPath);
        UpdateTarget();
        ApplyTransports();
        RefreshMenu();
    }

    public void NewPairCode()
    {
        Settings.PairCode = SlateSettings.NewPairCode();
        Settings.Save(Program.SettingsPath);
        RefreshMenu();
    }

    private void UpdateTarget()
    {
        var target = Monitors.Target(Settings);
        _options = new RouterOptions(target, Settings.BarrelMode, Settings.PressureGamma);
        _aspect = Settings.PreserveAspect ? (float)target.Aspect : 0f;
        _bridge.NotifyConfigChanged();
    }

    private void OnDisplaySettingsChanged(object? sender, EventArgs e) => Post(() =>
    {
        _router.Release();
        UpdateTarget();
        _form?.RefreshMonitors();
    });

    private void ApplyTransports()
    {
        if (Settings.UsbEnabled && _usb is null)
        {
            _usb = new UsbTransport(_bridge, () => Settings.AdbPath);
        }
        else if (!Settings.UsbEnabled && _usb is not null)
        {
            var old = _usb;
            _usb = null;
            Task.Run(old.Dispose);
            OnStatus(new LinkStatus(UsbTransport.Name, LinkState.Off, "Off"));
        }

        if (Settings.WifiEnabled && _wifi is null)
        {
            try
            {
                _wifi = new WifiTransport(_bridge, () => Settings.PairCode);
            }
            catch (SocketException ex)
            {
                OnStatus(new LinkStatus(WifiTransport.Name, LinkState.Off, $"Can't listen on UDP {Protocol.WifiPort}: {ex.Message}"));
            }
        }
        else if (!Settings.WifiEnabled && _wifi is not null)
        {
            var old = _wifi;
            _wifi = null;
            Task.Run(old.Dispose);
            OnStatus(new LinkStatus(WifiTransport.Name, LinkState.Off, "Off"));
        }
    }

    private void OnStatus(LinkStatus s)
    {
        _statuses[s.Transport] = s;

        var connected = _statuses.Values.FirstOrDefault(x => x.State == LinkState.Connected);
        var overall = connected is not null ? LinkState.Connected
            : _statuses.Values.Any(x => x.State == LinkState.Searching) ? LinkState.Searching
            : LinkState.Off;

        _tray.Icon = overall switch
        {
            LinkState.Connected => AppIcons.Connected,
            LinkState.Searching => AppIcons.Searching,
            _ => AppIcons.Idle,
        };
        var tip = connected is not null
            ? $"Slate: {connected.Message}{(connected.RttMs is int rtt ? $" ({rtt} ms)" : "")}"
            : "Slate: waiting for tablet";
        _tray.Text = tip.Length > 127 ? tip[..127] : tip;

        if (overall != _overall && (overall == LinkState.Connected || _overall == LinkState.Connected))
            _tray.ShowBalloonTip(2000, "Slate", connected?.Message ?? "Tablet disconnected", ToolTipIcon.None);
        _overall = overall;

        RefreshMenu();
        _form?.UpdateStatus();
    }

    private void RefreshMenu()
    {
        _usbItem.Text = "USB: " + Describe(UsbTransport.Name, Settings.UsbEnabled);
        _wifiItem.Text = "Wi-Fi: " + Describe(WifiTransport.Name, Settings.WifiEnabled);
        _codeItem.Text = $"Wi-Fi pairing code: {Settings.PairCode:D4}";
        _codeItem.Visible = Settings.WifiEnabled;
    }

    public string Describe(string transport, bool enabled)
    {
        if (!enabled) return "off";
        if (!_statuses.TryGetValue(transport, out var s)) return "starting…";
        return s.RttMs is int rtt ? $"{s.Message} · {rtt} ms round trip" : s.Message;
    }

    public void ShowSettings()
    {
        if (_form is null || _form.IsDisposed)
        {
            _form = new SettingsForm(this);
            _form.FormClosed += (_, _) => _form = null;
        }
        _form.Show();
        _form.Activate();
    }

    public async void RunTestStroke()
    {
        if (_testRunning) return;
        _testRunning = true;
        try
        {
            await TestStroke.RunAsync(_router, 3, msg => _form?.ShowHint(msg));
        }
        finally
        {
            _testRunning = false;
        }
    }

    private void Post(Action action)
    {
        if (_ui.IsDisposed) return;
        try
        {
            _ui.BeginInvoke(action);
        }
        catch (InvalidOperationException)
        {
            // Shutting down.
        }
    }

    private void Exit()
    {
        SystemEvents.DisplaySettingsChanged -= OnDisplaySettingsChanged;
        _form?.Close();
        _tray.Visible = false;
        var usb = _usb;
        var wifi = _wifi;
        _usb = null;
        _wifi = null;
        Task.WaitAll(Task.Run(() => usb?.Dispose()), Task.Run(() => wifi?.Dispose()));
        _bridge.Dispose();
        _tray.Dispose();
        _ui.Dispose();
        ExitThread();
    }
}
