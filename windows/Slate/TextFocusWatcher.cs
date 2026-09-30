using System.Windows.Automation;
using Slate.Core;

namespace Slate;

/// <summary>
/// While the screen is mirrored, watches keyboard focus with UI Automation and reports when it
/// moves into or out of a text field, so the tablet can bring up the handwriting pad.
/// </summary>
internal sealed class TextFocusWatcher : IDisposable
{
    private readonly Action<bool> _changed;
    private readonly object _lock = new();
    private readonly System.Threading.Timer _debounce;
    private AutomationFocusChangedEventHandler? _handler;
    private bool? _last;
    private volatile bool _force;
    private volatile bool _moved;

    /// <param name="changed">Called (on a background thread) with true when a text field gets focus, false when it loses it.</param>
    public TextFocusWatcher(Action<bool> changed)
    {
        _changed = changed;
        _debounce = new System.Threading.Timer(_ => Check(), null, Timeout.Infinite, Timeout.Infinite);
    }

    public void Start()
    {
        lock (_lock)
        {
            if (_handler is not null) return;
            _handler = (_, _) => { _moved = true; _debounce.Change(150, Timeout.Infinite); }; // focus often hops a few times per click
            _last = null;
            // Subscribing from a UI thread can deadlock against our own windows, so do it off-thread.
            var h = _handler;
            Task.Run(() =>
            {
                try { Automation.AddAutomationFocusChangedEventHandler(h); }
                catch (Exception ex) { Log.Info($"UI Automation unavailable: {ex.Message}"); }
            });
        }
    }

    /// <summary>After a click from the tablet: report a text field even if focus was already in it (e.g. tapping it again).</summary>
    public void Poke()
    {
        if (_handler is null) return;
        _force = true;
        _debounce.Change(200, Timeout.Infinite);
    }

    public void Stop()
    {
        AutomationFocusChangedEventHandler? h;
        lock (_lock)
        {
            h = _handler;
            _handler = null;
        }
        _debounce.Change(Timeout.Infinite, Timeout.Infinite);
        if (h is not null) Task.Run(() => { try { Automation.RemoveAutomationFocusChangedEventHandler(h); } catch { } });
    }

    private void Check()
    {
        if (_handler is null) return;
        bool moved = _moved;
        _moved = false;
        bool editable;
        try
        {
            editable = AutomationElement.FocusedElement is { } e && TextFocus.IsTextInput(Describe(e));
        }
        catch (Exception ex) when (ex is ElementNotAvailableException or InvalidOperationException or System.Runtime.InteropServices.COMException)
        {
            return; // the element went away mid-query; the next focus event will tell
        }
        bool force = _force;
        _force = false;
        // "In a text field" goes out on every focus move (a different field is news too) and after a
        // tap; "not in one" only when that changes.
        if (!editable && _last == false) return;
        if (editable && _last == true && !force && !moved) return;
        _last = editable;
        _changed(editable);
    }

    private static FocusInfo Describe(AutomationElement e)
    {
        var c = e.Current;
        bool hasValue = e.TryGetCurrentPattern(ValuePattern.Pattern, out var vp);
        return new FocusInfo(
            ControlType: c.ControlType.ProgrammaticName.Replace("ControlType.", ""),
            HasValuePattern: hasValue,
            ValueReadOnly: hasValue && ((ValuePattern)vp).Current.IsReadOnly,
            HasTextPattern: e.TryGetCurrentPattern(TextPattern.Pattern, out _),
            IsPassword: c.IsPassword,
            SystemCaret: Native.ForegroundHasCaret());
    }

    public void Dispose()
    {
        Stop();
        _debounce.Dispose();
    }
}
