using Slate.Core;
using Xunit;

namespace Slate.Core.Tests;

public class ProtocolTests
{
    // Same bytes as the golden vector in protocol/PROTOCOL.md and the Android PacketTest.
    private static readonly byte[] Golden =
    {
        0x02, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x13, 0x00, 0x02, 0x00, 0x00, 0x00, 0x3F,
        0x00, 0x00, 0x80, 0x3E, 0xE2, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xE8, 0x03, 0x00, 0x00,
    };

    private static readonly Packet GoldenPacket = new()
    {
        Type = PacketType.Pen,
        Seq = 1,
        Timestamp = 1000,
        Pen = new PenSample(PenTool.Pen, PenFlags.Contact | PenFlags.InRange | PenFlags.HasTilt, 512, 0.5f, 0.25f, -30, 15, 0),
    };

    [Fact]
    public void EncodesGoldenVector() => Assert.Equal(Golden, GoldenPacket.Encode());

    [Fact]
    public void DecodesGoldenVector() => Assert.Equal(GoldenPacket, Packet.Decode(Golden));

    [Theory]
    [MemberData(nameof(AllTypes))]
    public void RoundTrips(Packet p) => Assert.Equal(p, Packet.Decode(p.Encode()));

    public static IEnumerable<object[]> AllTypes() => new[]
    {
        new Packet { Type = PacketType.Hello, Seq = 7, Timestamp = 99, SurfaceWidth = 2560, SurfaceHeight = 1600, PairCode = 42, Name = "SM-X920" },
        new Packet { Type = PacketType.Hello, PairCode = Protocol.NoPairCode, Name = "" },
        new Packet { Type = PacketType.Pen, Seq = uint.MaxValue, Pen = new PenSample(PenTool.Eraser, PenFlags.InRange | PenFlags.Barrel | PenFlags.HasRotation, 0, 1f, 0f, 90, -90, 359) },
        new Packet { Type = PacketType.Leave, Seq = 3 },
        new Packet { Type = PacketType.Config, Aspect = 16f / 9f, Name = "LAPTOP" },
        new Packet { Type = PacketType.Ping, LastRttMs = 12 },
        new Packet { Type = PacketType.Pong, EchoTimestamp = 0xDEADBEEF },
        new Packet { Type = PacketType.Discover, UdpPort = 47811, Name = "DESKTOP-ABCDEFG" },
        new Packet { Type = PacketType.Bye, Reason = ByeReason.BadCode },
    }.Select(p => new object[] { p });

    [Fact]
    public void TruncatesNamesOnCharacterBoundary()
    {
        // 13 ASCII bytes + a 2-byte character doesn't fit in 14 bytes: the character is dropped whole.
        var p = new Packet { Type = PacketType.Hello, Name = "ABCDEFGHIJKLMé" };
        Assert.Equal("ABCDEFGHIJKLM", Packet.Decode(p.Encode())!.Name);
    }

    [Fact]
    public void RejectsShortOrUnknownPackets()
    {
        Assert.Null(Packet.Decode(new byte[31]));
        var bytes = new byte[32];
        bytes[0] = 99;
        Assert.Null(Packet.Decode(bytes));
    }

    [Theory]
    [InlineData(1u, 0u, true)]
    [InlineData(0u, 0u, false)]
    [InlineData(0u, 1u, false)]
    [InlineData(0u, uint.MaxValue, true)] // wrapped
    [InlineData(uint.MaxValue, 0u, false)]
    public void SequenceComparisonWraps(uint seq, uint last, bool newer) => Assert.Equal(newer, Protocol.IsNewer(seq, last));
}

public class MappingTests
{
    [Fact]
    public void MapsCornersToEdgePixels()
    {
        var r = new PixelRect(-1920, 0, 1920, 1080);
        Assert.Equal((-1920, 0), Mapping.ToPixels(0, 0, r));
        Assert.Equal((-1, 1079), Mapping.ToPixels(1, 1, r));
        Assert.Equal((-1920, 1079), Mapping.ToPixels(-5, 7, r));
        Assert.Equal((-1920, 0), Mapping.ToPixels(float.NaN, float.NegativeInfinity, r));
    }

    [Fact]
    public void AppliesRegion()
    {
        var monitor = new PixelRect(0, 0, 2880, 1800); // 150% scaled laptop panel, physical pixels
        var r = Mapping.SubRegion(monitor, new NormalizedRegion(0.25, 0.5, 0.5, 0.5));
        Assert.Equal(new PixelRect(720, 900, 1440, 900), r);
        Assert.Equal(monitor, Mapping.SubRegion(monitor, new NormalizedRegion(0, 0, 0, 0)));
        Assert.Equal(monitor, Mapping.SubRegion(monitor, null));
    }

    [Theory]
    [InlineData(0, 1.0, 0u)]
    [InlineData(1024, 2.0, 1024u)]
    [InlineData(512, 1.0, 512u)]
    [InlineData(512, 2.0, 256u)]
    [InlineData(1, 3.0, 1u)] // any contact keeps at least pressure 1
    public void PressureCurve_(ushort raw, double gamma, uint expected) => Assert.Equal(expected, PressureCurve.Apply(raw, gamma));

    [Fact]
    public void ParsesAdbDevices()
    {
        var list = Adb.ParseDevices("* daemon started successfully\nList of devices attached\nR52T\tdevice\nX1\tunauthorized\n\n");
        Assert.Equal(new[] { new AdbDevice("R52T", "device"), new AdbDevice("X1", "unauthorized") }, list);
    }
}
