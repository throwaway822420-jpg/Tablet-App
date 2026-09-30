using Slate.Core;
using Xunit;

namespace Slate.Core.Tests;

public class TextFocusTests
{
    [Theory]
    [InlineData("Edit", false, false, false, false, false, true)]      // plain text box
    [InlineData("Edit", true, false, true, false, false, true)]        // text box with value
    [InlineData("Edit", true, true, false, false, false, false)]       // read-only text box
    [InlineData("Edit", true, false, false, true, false, false)]       // password
    [InlineData("Document", true, false, true, false, false, true)]    // editable document
    [InlineData("Document", true, true, true, false, false, false)]    // web page being read
    [InlineData("Document", false, false, true, false, true, true)]    // Word/Notepad body with caret
    [InlineData("Document", false, false, true, false, false, false)]  // document, no caret
    [InlineData("ComboBox", true, false, false, false, false, true)]   // editable combo
    [InlineData("ComboBox", true, true, false, false, false, false)]   // drop-down list
    [InlineData("Button", false, false, false, false, false, false)]
    [InlineData("Button", false, false, false, false, true, false)]    // caret left over, but a button
    [InlineData("Pane", false, false, true, false, true, true)]        // custom editor with caret
    [InlineData("List", false, false, false, false, false, false)]
    public void Classifies(string type, bool value, bool ro, bool text, bool pwd, bool caret, bool expected) =>
        Assert.Equal(expected, TextFocus.IsTextInput(new FocusInfo(type, value, ro, text, pwd, caret)));
}
