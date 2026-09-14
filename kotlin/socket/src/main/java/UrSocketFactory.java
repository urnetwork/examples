import io.ur.sdk.Sdk;
import javax.net.SocketFactory;
import java.net.*;
import java.io.*;
import java.util.Arrays;
import java.util.Objects;

/** A java.net.Socket facade over a URnetwork TCP connection. */
public final class UrSocketFactory extends SocketFactory {
    private final Sdk.Device device;
    public UrSocketFactory(Sdk.Device device) { this.device = device; }
    @Override public Socket createSocket() { return new UrSocket(device); }
    @Override public Socket createSocket(String host, int port) throws IOException {
        Socket socket = createSocket(); socket.connect(InetSocketAddress.createUnresolved(host, port)); return socket;
    }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return createSocket(host.getHostAddress(), port); }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { throw new SocketException("Local bind is not supported"); }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { throw new SocketException("Local bind is not supported"); }

    private static final class UrSocket extends Socket {
        private final Sdk.Device device;
        private volatile Sdk.Conn conn;
        private volatile boolean closed, eof, inputShutdown, outputShutdown;
        private volatile int timeout;
        private InetSocketAddress endpoint;
        UrSocket(Sdk.Device device) { this.device = device; }
        @Override public void connect(SocketAddress address) throws IOException { connect(address, 30000); }
        @Override public void connect(SocketAddress address, int timeoutMillis) throws IOException {
            if (!(address instanceof InetSocketAddress target)) throw new SocketException("Internet endpoint required");
            synchronized (this) {
                if (closed || conn != null) throw new SocketException("Socket is closed or already connected");
                endpoint = target;
            }
            String host = target.getHostString();
            String destination = (host.contains(":") ? "[" + host + "]" : host) + ":" + target.getPort();
            Sdk.Conn opened = device.dial("tcp", destination, timeoutMillis);
            synchronized (this) {
                if (closed) { opened.close(); throw new SocketException("Socket closed during connect"); }
                conn = opened;
            }
        }
        private Sdk.Conn connection() throws SocketException {
            Sdk.Conn c = conn;
            if (closed || c == null) throw new SocketException("Socket is not connected");
            return c;
        }
        private final InputStream input = new InputStream() {
            private IOException pending;
            @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255; }
            @Override public int read(byte[] out, int offset, int count) throws IOException {
                Objects.checkFromIndexSize(offset, count, out.length);
                if (count == 0) return 0;
                if (pending != null) { IOException error = pending; pending = null; throw error; }
                if (eof || inputShutdown) return -1;
                Sdk.Conn c = connection();
                c.setReadDeadline(timeout == 0 ? 0 : System.currentTimeMillis() + timeout);
                Sdk.ReadResult result;
                try { result = c.read(Math.min(count, 65535)); }
                catch (Sdk.SocketException error) {
                    if (error.data.length == 0) throw error;
                    pending = error;
                    System.arraycopy(error.data, 0, out, offset, error.data.length);
                    return error.data.length;
                }
                byte[] data = result.data();
                System.arraycopy(data, 0, out, offset, data.length);
                eof = result.eof();
                return data.length == 0 && eof ? -1 : data.length;
            }
            @Override public void close() { UrSocket.this.close(); }
        };
        private final OutputStream output = new OutputStream() {
            @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}); }
            @Override public void write(byte[] data, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, data.length);
                if (outputShutdown) throw new SocketException("Output closed");
                int end = offset + length;
                while (offset < end) {
                    int n = connection().write(Arrays.copyOfRange(data, offset, Math.min(end, offset + 65535)));
                    if (n <= 0) throw new IOException("Socket write made no progress");
                    offset += n;
                }
            }
            @Override public void close() { UrSocket.this.close(); }
        };
        @Override public InputStream getInputStream() throws IOException { connection(); return input; }
        @Override public OutputStream getOutputStream() throws IOException { connection(); return output; }
        @Override public synchronized void close() { if (!closed) { closed = true; if (conn != null) conn.close(); } }
        @Override public void shutdownInput() throws IOException { inputShutdown = true; connection().closeRead(); }
        @Override public void shutdownOutput() throws IOException { outputShutdown = true; connection().closeWrite(); }
        @Override public boolean isConnected() { return conn != null; }
        @Override public boolean isClosed() { return closed; }
        @Override public boolean isInputShutdown() { return inputShutdown; }
        @Override public boolean isOutputShutdown() { return outputShutdown; }
        @Override public int getPort() { return endpoint == null ? 0 : endpoint.getPort(); }
        @Override public SocketAddress getRemoteSocketAddress() { return endpoint; }
        @Override public InetAddress getInetAddress() { return endpoint == null ? null : endpoint.getAddress(); }
        @Override public void setSoTimeout(int millis) { if (millis < 0) throw new IllegalArgumentException(); timeout = millis; }
        @Override public int getSoTimeout() { return timeout; }
        // These are kernel TCP tuning hints; the connect stack controls its own policy.
        @Override public void setTcpNoDelay(boolean value) {}
        @Override public void setKeepAlive(boolean value) {}
        @Override public void setReuseAddress(boolean value) {}
        @Override public void setSoLinger(boolean value, int seconds) {}
    }
}
