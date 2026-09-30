using System.Buffers.Binary;
using System.Text;

namespace Slate.Core;

// Implements protocol/PROTOCOL.md. Keep the two in sync.

public enum PacketType : byte
{
    Hello = 1,
    Pen = 2,
    Leave = 3,
    Config = 4,
    Ping = 5,
    Pong = 6,
    Discover = 7,
    Bye = 8,
}

public enum PenTool : byte
{
    Pen = 0,
    Eraser = 1,
}

[Flags]
public enum PenFlags : byte
{
    None = 0,
    Contact = 1 << 0,
    InRange = 1 << 1,
    Barrel = 1 << 2,
    HasRotation = 1 << 3,
    HasTilt = 1 << 4,
}

public enum ByeReason : byte
{
    Normal = 0,
    BadCode = 1,
    Busy = 2,
    VersionMismatch = 3,
}

public readonly record struct PenSample(
    PenTool Tool,
    PenFlags Flags,
    ushort Pressure,
    float X,
    float Y,
    sbyte TiltX,
    sbyte TiltY,
    ushort Rotation)
{
    public bool Contact => (Flags & PenFlags.Contact) != 0;
    public bool InRange => (Flags & (PenFlags.InRange | PenFlags.Contact)) != 0;
    public bool Barrel => (Flags & PenFlags.Barrel) != 0;
}

public static class Protocol
{
    public const int PacketSize = 32;
    public const byte Version = 1;

    public const int DiscoveryPort = 47810;
    public const int WifiPort = 47811;
    public const int UsbPort = 47812;

    public const ushort NoPairCode = 0xFFFF;
    public const ushort NoRtt = 0xFFFF;
    public const ushort MaxPressure = 1024;

    public const int HelloNameBytes = 14;
    public const int ConfigNameBytes = 14;
    public const int DiscoverNameBytes = 16;

    /// <summary>True if <paramref name="seq"/> is newer than <paramref name="last"/>, allowing for wrap-around.</summary>
    public static bool IsNewer(uint seq, uint last) => unchecked((int)(seq - last)) > 0;
}

/// <summary>One decoded packet. Only the fields that belong to <see cref="Type"/> are meaningful.</summary>
public sealed record Packet
{
    public PacketType Type { get; init; }
    public byte Version { get; init; } = Protocol.Version;
    public uint Seq { get; init; }
    public uint Timestamp { get; init; }

    // PEN
    public PenSample Pen { get; init; }

    // HELLO
    public ushort SurfaceWidth { get; init; }
    public ushort SurfaceHeight { get; init; }
    public ushort PairCode { get; init; } = Protocol.NoPairCode;

    // HELLO, CONFIG, DISCOVER
    public string Name { get; init; } = "";

    // CONFIG
    public float Aspect { get; init; }

    // PING
    public ushort LastRttMs { get; init; } = Protocol.NoRtt;

    // PONG
    public uint EchoTimestamp { get; init; }

    // DISCOVER
    public ushort UdpPort { get; init; }

    // BYE
    public ByeReason Reason { get; init; }

    public byte[] Encode()
    {
        var buf = new byte[Protocol.PacketSize];
        Encode(buf);
        return buf;
    }

    public void Encode(Span<byte> b)
    {
        if (b.Length < Protocol.PacketSize) throw new ArgumentException("buffer too small", nameof(b));
        b = b[..Protocol.PacketSize];
        b.Clear();
        b[0] = (byte)Type;
        b[1] = Version;
        BinaryPrimitives.WriteUInt32LittleEndian(b[4..], Seq);
        BinaryPrimitives.WriteUInt32LittleEndian(b[28..], Timestamp);

        switch (Type)
        {
            case PacketType.Hello:
                BinaryPrimitives.WriteUInt16LittleEndian(b[8..], SurfaceWidth);
                BinaryPrimitives.WriteUInt16LittleEndian(b[10..], SurfaceHeight);
                BinaryPrimitives.WriteUInt16LittleEndian(b[12..], PairCode);
                WriteName(b.Slice(14, Protocol.HelloNameBytes), Name);
                break;
            case PacketType.Pen:
                b[8] = (byte)Pen.Tool;
                b[9] = (byte)Pen.Flags;
                BinaryPrimitives.WriteUInt16LittleEndian(b[10..], Pen.Pressure);
                BinaryPrimitives.WriteSingleLittleEndian(b[12..], Pen.X);
                BinaryPrimitives.WriteSingleLittleEndian(b[16..], Pen.Y);
                b[20] = unchecked((byte)Pen.TiltX);
                b[21] = unchecked((byte)Pen.TiltY);
                BinaryPrimitives.WriteUInt16LittleEndian(b[22..], Pen.Rotation);
                break;
            case PacketType.Config:
                BinaryPrimitives.WriteSingleLittleEndian(b[8..], Aspect);
                WriteName(b.Slice(14, Protocol.ConfigNameBytes), Name);
                break;
            case PacketType.Ping:
                BinaryPrimitives.WriteUInt16LittleEndian(b[8..], LastRttMs);
                break;
            case PacketType.Pong:
                BinaryPrimitives.WriteUInt32LittleEndian(b[8..], EchoTimestamp);
                break;
            case PacketType.Discover:
                BinaryPrimitives.WriteUInt16LittleEndian(b[8..], UdpPort);
                WriteName(b.Slice(12, Protocol.DiscoverNameBytes), Name);
                break;
            case PacketType.Bye:
                b[8] = (byte)Reason;
                break;
        }
    }

    /// <summary>Decodes a packet, or returns null if the buffer is short or the type is unknown.</summary>
    public static Packet? Decode(ReadOnlySpan<byte> b)
    {
        if (b.Length < Protocol.PacketSize) return null;
        var type = (PacketType)b[0];
        if (!Enum.IsDefined(type)) return null;

        var p = new Packet
        {
            Type = type,
            Version = b[1],
            Seq = BinaryPrimitives.ReadUInt32LittleEndian(b[4..]),
            Timestamp = BinaryPrimitives.ReadUInt32LittleEndian(b[28..]),
        };

        return type switch
        {
            PacketType.Hello => p with
            {
                SurfaceWidth = BinaryPrimitives.ReadUInt16LittleEndian(b[8..]),
                SurfaceHeight = BinaryPrimitives.ReadUInt16LittleEndian(b[10..]),
                PairCode = BinaryPrimitives.ReadUInt16LittleEndian(b[12..]),
                Name = ReadName(b.Slice(14, Protocol.HelloNameBytes)),
            },
            PacketType.Pen => p with
            {
                Pen = new PenSample(
                    (PenTool)b[8],
                    (PenFlags)b[9],
                    BinaryPrimitives.ReadUInt16LittleEndian(b[10..]),
                    BinaryPrimitives.ReadSingleLittleEndian(b[12..]),
                    BinaryPrimitives.ReadSingleLittleEndian(b[16..]),
                    unchecked((sbyte)b[20]),
                    unchecked((sbyte)b[21]),
                    BinaryPrimitives.ReadUInt16LittleEndian(b[22..])),
            },
            PacketType.Config => p with
            {
                Aspect = BinaryPrimitives.ReadSingleLittleEndian(b[8..]),
                Name = ReadName(b.Slice(14, Protocol.ConfigNameBytes)),
            },
            PacketType.Ping => p with { LastRttMs = BinaryPrimitives.ReadUInt16LittleEndian(b[8..]) },
            PacketType.Pong => p with { EchoTimestamp = BinaryPrimitives.ReadUInt32LittleEndian(b[8..]) },
            PacketType.Discover => p with
            {
                UdpPort = BinaryPrimitives.ReadUInt16LittleEndian(b[8..]),
                Name = ReadName(b.Slice(12, Protocol.DiscoverNameBytes)),
            },
            PacketType.Bye => p with { Reason = (ByeReason)b[8] },
            _ => p,
        };
    }

    /// <summary>Writes UTF-8, truncated on a character boundary, NUL-padded.</summary>
    private static void WriteName(Span<byte> dest, string name)
    {
        dest.Clear();
        var encoder = Encoding.UTF8.GetEncoder();
        encoder.Convert(name.AsSpan(), dest, flush: true, out _, out _, out _);
    }

    private static string ReadName(ReadOnlySpan<byte> src)
    {
        int end = src.IndexOf((byte)0);
        if (end < 0) end = src.Length;
        return Encoding.UTF8.GetString(src[..end]);
    }
}
