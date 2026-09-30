using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text.Json.Nodes;

namespace Slate.Core;

/// <summary>
/// The PC end of the bulk channel. Listens on TCP 47813; over USB the tablet arrives from loopback
/// through <c>adb reverse</c>, over Wi-Fi it connects directly and must present the pairing code.
/// One tablet at a time; a new authenticated connection replaces the old one.
/// </summary>
public sealed class BulkServer : IDisposable
{
    private readonly TcpListener _listener;
    private readonly Func<int> _pairCode;
    private readonly Func<IPAddress, bool> _trusted;
    private readonly CancellationTokenSource _cts = new();
    private readonly object _gate = new();
    private Client? _client;

    /// <param name="trusted">Addresses that need no pairing code; default loopback (USB via adb reverse).</param>
    public BulkServer(Func<int> pairCode, int port = BulkFrame.Port, IPAddress? bind = null, Func<IPAddress, bool>? trusted = null)
    {
        _pairCode = pairCode;
        _trusted = trusted ?? IPAddress.IsLoopback;
        _listener = new TcpListener(bind ?? IPAddress.Any, port);
        _listener.Start();
        Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
        _ = Task.Run(AcceptLoopAsync);
    }

    public int Port { get; }

    public bool IsConnected { get { lock (_gate) return _client is not null; } }

    /// <summary>A tablet connected (argument: its device name).</summary>
    public event Action<string>? Connected;

    public event Action? Disconnected;

    /// <summary>Raised on the connection's reader thread for every JSON or Blob frame.</summary>
    public event Action<BulkFrame>? FrameReceived;

    /// <summary>Queues a frame. Video frames are dropped when the link falls behind (see <see cref="SendVideo"/>).</summary>
    public void Send(BulkFrame frame)
    {
        Client? c;
        lock (_gate) c = _client;
        c?.Enqueue(frame);
    }

    public void Send(JsonObject message) => Send(BulkFrame.Json(message));

    /// <summary>
    /// Queues a video frame. If the link is backed up, delta frames are dropped until the next
    /// keyframe, so the picture freezes briefly instead of smearing or building latency.
    /// </summary>
    /// <returns>False if the frame was dropped.</returns>
    public bool SendVideo(byte[] accessUnit, bool keyframe, long ptsMicros)
    {
        Client? c;
        lock (_gate) c = _client;
        return c?.EnqueueVideo(accessUnit, keyframe, ptsMicros) ?? false;
    }

    private async Task AcceptLoopAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            TcpClient tcp;
            try
            {
                tcp = await _listener.AcceptTcpClientAsync(_cts.Token);
            }
            catch (Exception ex) when (ex is OperationCanceledException or ObjectDisposedException or SocketException)
            {
                if (_cts.IsCancellationRequested) return;
                continue;
            }
            _ = Task.Run(() => Handshake(tcp));
        }
    }

    private void Handshake(TcpClient tcp)
    {
        try
        {
            tcp.NoDelay = true;
            tcp.ReceiveTimeout = 5000;
            var stream = tcp.GetStream();
            var hello = BulkFrame.ReadFrom(stream);
            var o = hello.Kind == BulkKind.Hello ? hello.AsJson() : null;
            if (o is null)
            {
                tcp.Dispose();
                return;
            }
            // adb reverse delivers USB connections from loopback; anything else needs the code.
            bool loopback = tcp.Client.RemoteEndPoint is IPEndPoint ep && _trusted(ep.Address);
            if (!loopback && o.Int("code", -1) != _pairCode())
            {
                BulkFrame.Json(new JsonObject { ["t"] = "denied", ["message"] = "Wrong pairing code" }).WriteTo(stream);
                tcp.Dispose();
                return;
            }
            tcp.ReceiveTimeout = 0;
            var client = new Client(this, tcp);
            Client? old;
            lock (_gate)
            {
                old = _client;
                _client = client;
            }
            old?.Close();
            string device = o.Str("device", "tablet");
            Log.Info($"Bulk channel: {device} connected ({(loopback ? "USB" : "Wi-Fi")})");
            client.Start();
            Connected?.Invoke(device);
        }
        catch (Exception ex) when (ex is IOException or EndOfStreamException or InvalidDataException or SocketException or ObjectDisposedException)
        {
            tcp.Dispose();
        }
    }

    private void OnClientClosed(Client c)
    {
        bool was;
        lock (_gate)
        {
            was = ReferenceEquals(_client, c);
            if (was) _client = null;
        }
        if (was)
        {
            Log.Info("Bulk channel: tablet disconnected");
            Disconnected?.Invoke();
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        _listener.Stop();
        Client? c;
        lock (_gate)
        {
            c = _client;
            _client = null;
        }
        c?.Close();
        _cts.Dispose();
    }

    private sealed class Client
    {
        private const int MaxQueuedVideo = 3;

        private readonly BulkServer _server;
        private readonly TcpClient _tcp;
        private readonly NetworkStream _stream;
        private readonly BlockingCollection<BulkFrame> _queue = new(new ConcurrentQueue<BulkFrame>());
        private int _queuedVideo;
        private bool _waitingForKeyframe;
        private int _closed;

        public Client(BulkServer server, TcpClient tcp)
        {
            _server = server;
            _tcp = tcp;
            _stream = tcp.GetStream();
        }

        public void Start()
        {
            new Thread(ReadLoop) { IsBackground = true, Name = "slate-bulk-read" }.Start();
            new Thread(WriteLoop) { IsBackground = true, Name = "slate-bulk-write" }.Start();
        }

        public void Enqueue(BulkFrame f)
        {
            if (Volatile.Read(ref _closed) != 0) return;
            try { _queue.Add(f); } catch (InvalidOperationException) { }
        }

        public bool EnqueueVideo(byte[] au, bool keyframe, long pts)
        {
            if (Volatile.Read(ref _closed) != 0) return false;
            lock (this)
            {
                if (_waitingForKeyframe && !keyframe) return false;
                if (Volatile.Read(ref _queuedVideo) >= MaxQueuedVideo && !keyframe)
                {
                    _waitingForKeyframe = true;
                    return false;
                }
                _waitingForKeyframe = false;
            }
            Interlocked.Increment(ref _queuedVideo);
            Enqueue(BulkFrame.Video(au, keyframe, pts));
            return true;
        }

        private void ReadLoop()
        {
            try
            {
                while (true)
                {
                    var f = BulkFrame.ReadFrom(_stream);
                    try
                    {
                        _server.FrameReceived?.Invoke(f);
                    }
                    catch (Exception ex)
                    {
                        Log.Info($"Bulk channel: error handling a frame: {ex}");
                    }
                }
            }
            catch (Exception ex) when (ex is IOException or EndOfStreamException or InvalidDataException or ObjectDisposedException or SocketException)
            {
            }
            Close();
        }

        private void WriteLoop()
        {
            try
            {
                foreach (var f in _queue.GetConsumingEnumerable())
                {
                    f.WriteTo(_stream);
                    if (f.Kind == BulkKind.Video) Interlocked.Decrement(ref _queuedVideo);
                }
            }
            catch (Exception ex) when (ex is IOException or ObjectDisposedException or SocketException or InvalidOperationException)
            {
            }
            Close();
        }

        public void Close()
        {
            if (Interlocked.Exchange(ref _closed, 1) != 0) return;
            _queue.CompleteAdding();
            _tcp.Dispose();
            _server.OnClientClosed(this);
        }
    }
}
