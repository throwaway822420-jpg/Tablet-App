using System.Buffers.Binary;
using System.Text;
using System.Text.Json.Nodes;

namespace Slate.Core;

// The bulk channel (PROTOCOL.md "Bulk channel"): a TCP stream on port 47813 for everything too big
// for 32-byte packets: video, screenshots, control messages, Claude replies.
// Frame: u32 length (of what follows) | u8 kind | body.

public enum BulkKind : byte
{
    /// <summary>Tablet → PC, first frame: JSON {"code": pairing code or -1, "device": name}.</summary>
    Hello = 1,

    /// <summary>Either way: a JSON object with a "t" (type) field.</summary>
    Json = 2,

    /// <summary>PC → tablet: u8 flags (bit0 keyframe) | i64 pts µs | one H.264 Annex-B access unit.</summary>
    Video = 3,

    /// <summary>Either way: u32 json length | JSON header | binary payload (images).</summary>
    Blob = 4,
}

public sealed record BulkFrame(BulkKind Kind, byte[] Body)
{
    public const int Port = 47813;
    public const int MaxBody = 64 * 1024 * 1024;

    public static BulkFrame Json(JsonObject o) => new(BulkKind.Json, Encoding.UTF8.GetBytes(o.ToJsonString()));

    public static BulkFrame Hello(int code, string device) =>
        new(BulkKind.Hello, Encoding.UTF8.GetBytes(new JsonObject { ["code"] = code, ["device"] = device }.ToJsonString()));

    public static BulkFrame Video(ReadOnlySpan<byte> accessUnit, bool keyframe, long ptsMicros)
    {
        var body = new byte[9 + accessUnit.Length];
        body[0] = (byte)(keyframe ? 1 : 0);
        BinaryPrimitives.WriteInt64LittleEndian(body.AsSpan(1), ptsMicros);
        accessUnit.CopyTo(body.AsSpan(9));
        return new BulkFrame(BulkKind.Video, body);
    }

    public static BulkFrame Blob(JsonObject header, ReadOnlySpan<byte> data)
    {
        var h = Encoding.UTF8.GetBytes(header.ToJsonString());
        var body = new byte[4 + h.Length + data.Length];
        BinaryPrimitives.WriteUInt32LittleEndian(body, (uint)h.Length);
        h.CopyTo(body.AsSpan(4));
        data.CopyTo(body.AsSpan(4 + h.Length));
        return new BulkFrame(BulkKind.Blob, body);
    }

    public JsonObject? AsJson()
    {
        if (Kind is not (BulkKind.Json or BulkKind.Hello)) return null;
        try
        {
            return JsonNode.Parse(Body) as JsonObject;
        }
        catch (System.Text.Json.JsonException)
        {
            return null;
        }
    }

    public (JsonObject Header, ReadOnlyMemory<byte> Data)? AsBlob()
    {
        if (Kind != BulkKind.Blob || Body.Length < 4) return null;
        int hl = (int)BinaryPrimitives.ReadUInt32LittleEndian(Body);
        if (hl > Body.Length - 4) return null;
        try
        {
            if (JsonNode.Parse(Body.AsSpan(4, hl)) is not JsonObject h) return null;
            return (h, Body.AsMemory(4 + hl));
        }
        catch (System.Text.Json.JsonException)
        {
            return null;
        }
    }

    public void WriteTo(Stream s)
    {
        Span<byte> head = stackalloc byte[5];
        BinaryPrimitives.WriteUInt32LittleEndian(head, (uint)(Body.Length + 1));
        head[4] = (byte)Kind;
        s.Write(head);
        s.Write(Body);
    }

    /// <summary>Reads one frame; throws EndOfStreamException when the stream ends.</summary>
    public static BulkFrame ReadFrom(Stream s)
    {
        Span<byte> head = stackalloc byte[5];
        s.ReadExactly(head);
        int len = (int)BinaryPrimitives.ReadUInt32LittleEndian(head);
        if (len < 1 || len - 1 > MaxBody) throw new InvalidDataException($"bad bulk frame length {len}");
        var body = new byte[len - 1];
        s.ReadExactly(body);
        return new BulkFrame((BulkKind)head[4], body);
    }
}

/// <summary>Small helpers for reading JSON control messages.</summary>
public static class Json
{
    public static string Str(this JsonObject o, string key, string fallback = "") =>
        o.TryGetPropertyValue(key, out var v) && v is JsonValue jv && jv.TryGetValue<string>(out var s) ? s : fallback;

    public static double Num(this JsonObject o, string key, double fallback = 0) =>
        o.TryGetPropertyValue(key, out var v) && v is JsonValue jv && jv.TryGetValue<double>(out var d) ? d : fallback;

    public static int Int(this JsonObject o, string key, int fallback = 0) =>
        o.TryGetPropertyValue(key, out var v) && v is JsonValue jv && jv.TryGetValue<int>(out var i) ? i : (int)o.Num(key, fallback);

    public static bool Bool(this JsonObject o, string key, bool fallback = false) =>
        o.TryGetPropertyValue(key, out var v) && v is JsonValue jv && jv.TryGetValue<bool>(out var b) ? b : fallback;
}
