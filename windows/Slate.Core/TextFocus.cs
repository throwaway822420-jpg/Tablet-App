namespace Slate.Core;

/// <summary>What UI Automation says about the focused element (plus whether its thread shows a caret).</summary>
public sealed record FocusInfo(
    string ControlType,
    bool HasValuePattern = false,
    bool ValueReadOnly = false,
    bool HasTextPattern = false,
    bool IsPassword = false,
    bool SystemCaret = false);

/// <summary>Decides whether the focused element is somewhere you type, so the tablet can offer handwriting.</summary>
public static class TextFocus
{
    public static bool IsTextInput(FocusInfo f)
    {
        if (f.IsPassword) return false; // never send handwritten passwords off to be recognised
        bool writableValue = f.HasValuePattern && !f.ValueReadOnly;
        return f.ControlType switch
        {
            // Text boxes, search boxes, address bars, chat inputs (contenteditable role=textbox).
            "Edit" => !(f.HasValuePattern && f.ValueReadOnly),
            // A whole document: Word or Notepad's page is editable; a web page you're reading isn't.
            "Document" => writableValue || (f.SystemCaret && !f.HasValuePattern),
            "ComboBox" => writableValue,
            // Custom-drawn editors: only when there's a text caret and something to type into.
            _ => f.SystemCaret && (writableValue || f.HasTextPattern),
        };
    }
}
