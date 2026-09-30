namespace Slate.Core;

/// <summary>
/// Splits an H.264 Annex-B byte stream (as ffmpeg writes it with h264_metadata=aud=insert) into
/// access units, one per frame, each starting with an access unit delimiter (NAL type 9).
/// </summary>
public sealed class AccessUnitSplitter
{
    private byte[] _buf = new byte[1 << 20];
    private int _len;
    private int _scanFrom;
    private int _auStart = -1;

    /// <summary>A complete access unit and whether it contains an IDR slice (NAL type 5).</summary>
    public readonly record struct AccessUnit(byte[] Data, bool Keyframe);

    /// <summary>Feeds bytes; returns the access units completed by them.</summary>
    public List<AccessUnit> Push(ReadOnlySpan<byte> data)
    {
        EnsureCapacity(_len + data.Length);
        data.CopyTo(_buf.AsSpan(_len));
        _len += data.Length;

        var done = new List<AccessUnit>();
        int i = Math.Max(0, _scanFrom);
        while (i + 3 < _len)
        {
            // Start code 00 00 01 (a 4-byte 00 00 00 01 is found via its last three bytes).
            if (_buf[i] == 0 && _buf[i + 1] == 0 && _buf[i + 2] == 1)
            {
                int nalType = _buf[i + 3] & 0x1F;
                int codeStart = i > 0 && _buf[i - 1] == 0 ? i - 1 : i;
                if (nalType == 9)
                {
                    if (_auStart >= 0 && codeStart > _auStart)
                    {
                        var au = _buf.AsSpan(_auStart, codeStart - _auStart).ToArray();
                        done.Add(new AccessUnit(au, ContainsNal(au, 5)));
                    }
                    _auStart = codeStart;
                }
                i += 3;
            }
            else
            {
                i++;
            }
        }
        _scanFrom = Math.Max(0, _len - 3);
        Compact();
        return done;
    }

    /// <summary>True if the Annex-B data has a NAL unit of the given type.</summary>
    public static bool ContainsNal(ReadOnlySpan<byte> au, int type)
    {
        for (int i = 0; i + 3 < au.Length; i++)
        {
            if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1 && (au[i + 3] & 0x1F) == type) return true;
        }
        return false;
    }

    private void Compact()
    {
        // Drop bytes before the current access unit (or everything if none has started yet).
        // Keep 4 bytes when no unit has started: enough for a whole 00 00 00 01 start code.
        int keepFrom = _auStart >= 0 ? _auStart : Math.Max(0, _len - 4);
        if (keepFrom == 0) return;
        Buffer.BlockCopy(_buf, keepFrom, _buf, 0, _len - keepFrom);
        _len -= keepFrom;
        _scanFrom = Math.Max(0, _scanFrom - keepFrom);
        if (_auStart >= 0) _auStart -= keepFrom;
    }

    private void EnsureCapacity(int n)
    {
        if (n <= _buf.Length) return;
        Array.Resize(ref _buf, Math.Max(n, _buf.Length * 2));
    }
}
