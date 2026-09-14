using URnetwork.SDK;

// Return this stream from SocketsHttpHandler.ConnectCallback. HttpClient owns TLS.
public sealed class UrStream : Stream
{
    private readonly Conn conn;
    private IOException? pendingRead;
    private bool eof;
    public UrStream(Conn conn) { this.conn = conn; }
    public override bool CanRead => !conn.IsClosed;
    public override bool CanWrite => !conn.IsClosed;
    public override bool CanSeek => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    public override void Flush() {}
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override int Read(byte[] buffer, int offset, int count)
    {
        if (offset < 0 || count < 0 || offset > buffer.Length - count) throw new ArgumentOutOfRangeException();
        if (count == 0) return 0;
        if (pendingRead != null) {var error = pendingRead; pendingRead = null; throw error;}
        if (eof) return 0;
        ReadResult result;
        try {result = conn.Read(Math.Min(count, 65535));}
        catch (URnetwork.SDK.SocketException error) {
            if (error.PartialData.Length == 0) throw;
            pendingRead = error;
            error.PartialData.CopyTo(buffer, offset);
            return error.PartialData.Length;
        }
        eof = result.Eof;
        result.Data.CopyTo(buffer, offset);
        return result.Data.Length;
    }
    public override void Write(byte[] buffer, int offset, int count)
    {
        if (offset < 0 || count < 0 || offset > buffer.Length - count) throw new ArgumentOutOfRangeException();
        int end = offset + count;
        while (offset < end) {
            int n = conn.Write(buffer.AsSpan(offset, Math.Min(end - offset, 65535)).ToArray());
            if (n <= 0) throw new IOException("Socket write made no progress");
            offset += n;
        }
    }
    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (buffer.Length == 0) return 0;
        using var registration = cancellationToken.Register(conn.Dispose);
        var owned = new byte[Math.Min(buffer.Length, 65535)];
        int count = await Task.Run(() => Read(owned, 0, owned.Length), CancellationToken.None).ConfigureAwait(false);
        cancellationToken.ThrowIfCancellationRequested();
        owned.AsMemory(0, count).CopyTo(buffer);
        return count;
    }
    public override async ValueTask WriteAsync(ReadOnlyMemory<byte> data, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        byte[] owned = data.ToArray();
        using var registration = cancellationToken.Register(conn.Dispose);
        await Task.Run(() => Write(owned, 0, owned.Length), CancellationToken.None).ConfigureAwait(false);
        cancellationToken.ThrowIfCancellationRequested();
    }
    protected override void Dispose(bool disposing) { if (disposing) conn.Dispose(); base.Dispose(disposing); }
}
