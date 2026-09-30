using Slate.Core;
using Xunit;

namespace Slate.Core.Tests;

public class RichTextTests
{
    [Fact]
    public void PlainTextIsOneTypedPart() =>
        Assert.Equal(new RichPart[] { new RichPart.Typed("hello x²") }, RichText.Parse("hello x²").Parts);

    [Fact]
    public void EquationsSplitTheText()
    {
        var m = RichText.Parse("Still <math>a</math>y = dy/dx ok <math>b</math>");
        Assert.False(m.IsClipboard);
        Assert.Equal(new RichPart[]
        {
            new RichPart.Typed("Still "),
            new RichPart.Equation("<math>a</math>", "y = dy/dx"),
            new RichPart.Typed(" ok "),
            new RichPart.Equation("<math>b</math>", ""),
        }, m.Parts);
    }

    [Fact]
    public void UnterminatedMarkersAreDroppedNotTyped() =>
        Assert.Equal(new RichPart[] { new RichPart.Typed("a b") }, RichText.Parse("a b").Parts);

    [Fact]
    public void ClipboardMessages()
    {
        var plain = RichText.Parse("1/3");
        Assert.True(plain.IsClipboard);
        Assert.Equal("1/3", plain.ClipboardText);
        Assert.Null(plain.ClipboardMathMl);

        var withMath = RichText.Parse("√2<math><msqrt><mn>2</mn></msqrt></math>");
        Assert.Equal("√2", withMath.ClipboardText);
        Assert.Equal("<math><msqrt><mn>2</mn></msqrt></math>", withMath.ClipboardMathMl);
        Assert.Empty(withMath.Parts);
    }

    [Fact]
    public void PasteIsCtrlV() => Assert.Equal(new[]
    {
        KeyStroke.Vk(KeyStroke.VkControl, false), KeyStroke.Vk(KeyStroke.VkV, false),
        KeyStroke.Vk(KeyStroke.VkV, true), KeyStroke.Vk(KeyStroke.VkControl, true),
    }, KeySequence.Paste());
}
