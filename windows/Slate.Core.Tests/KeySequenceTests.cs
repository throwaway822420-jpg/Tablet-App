using Slate.Core;
using Xunit;

namespace Slate.Core.Tests;

public class KeySequenceTests
{
    private static KeyStroke C(char c, bool up) => KeyStroke.Char(c, up);
    private static KeyStroke V(ushort vk, bool up) => KeyStroke.Vk(vk, up);

    [Fact]
    public void ShiftEnterWrapsReturnInShift()
    {
        Assert.Equal(new[]
        {
            C('a', false), C('a', true),
            V(KeyStroke.VkShift, false), V(KeyStroke.VkReturn, false), V(KeyStroke.VkReturn, true), V(KeyStroke.VkShift, true),
            C('b', false), C('b', true),
        }, KeySequence.For("a\nb", NewlineMode.ShiftEnter));
    }

    [Fact]
    public void EnterIsAPlainReturn() =>
        Assert.Equal(new[] { C('a', false), C('a', true), V(KeyStroke.VkReturn, false), V(KeyStroke.VkReturn, true), C('b', false), C('b', true) },
            KeySequence.For("a\r\nb", NewlineMode.Enter));

    [Fact]
    public void SpaceReplacesLineBreaks() =>
        Assert.Equal(new[] { C('a', false), C('a', true), C(' ', false), C(' ', true), C('b', false), C('b', true) },
            KeySequence.For("a\nb", NewlineMode.Space));

    [Fact]
    public void UnicodeAndSurrogatesPassThrough()
    {
        var keys = KeySequence.For("x²😀", NewlineMode.ShiftEnter);
        Assert.Equal(8, keys.Count); // x, ², and the emoji's two UTF-16 units, each down + up
        Assert.All(keys, k => Assert.True(k.IsUnicode));
        Assert.Equal('²', keys[2].Character);
    }

    [Fact]
    public void SettingDefaultsToShiftEnter() => Assert.Equal(NewlineMode.ShiftEnter, new SlateSettings().NewlineMode);
}
