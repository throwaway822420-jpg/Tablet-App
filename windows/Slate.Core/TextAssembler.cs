namespace Slate.Core;

/// <summary>Types text into whatever has keyboard focus on the PC.</summary>
public interface ITextTyper
{
    void Type(string text);
}

/// <summary>
/// Reassembles TEXT messages from their chunks. Chunks may arrive out of order or more than once
/// (the tablet resends until it gets TEXT_ACK), so completed ids are remembered and never typed twice.
/// </summary>
public sealed class TextAssembler
{
    private const int RememberCompleted = 32;

    private readonly Dictionary<ushort, string?[]> _partial = new();
    private readonly Queue<ushort> _completedOrder = new();
    private readonly HashSet<ushort> _completed = new();

    /// <summary>Adds one chunk.</summary>
    /// <returns>
    /// <c>(complete, text)</c>: complete is true once every chunk of this id has arrived (also for a
    /// repeat of an id already completed, so it can be acked again); text is non-null only the
    /// first time, when it should be typed.
    /// </returns>
    public (bool Complete, string? Text) Add(Packet p)
    {
        if (_completed.Contains(p.TextId)) return (true, null);
        if (p.ChunkCount == 0 || p.ChunkIndex >= p.ChunkCount) return (false, null);

        if (!_partial.TryGetValue(p.TextId, out var chunks) || chunks.Length != p.ChunkCount)
        {
            chunks = new string?[p.ChunkCount];
            _partial[p.TextId] = chunks;
        }
        chunks[p.ChunkIndex] = p.Chunk;
        if (chunks.Any(c => c is null)) return (false, null);

        _partial.Remove(p.TextId);
        _completed.Add(p.TextId);
        _completedOrder.Enqueue(p.TextId);
        while (_completedOrder.Count > RememberCompleted) _completed.Remove(_completedOrder.Dequeue());
        return (true, string.Concat(chunks));
    }

    public void Reset()
    {
        _partial.Clear();
        _completed.Clear();
        _completedOrder.Clear();
    }
}
