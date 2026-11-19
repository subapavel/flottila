package dev.rail8.runtime.git;

import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The https connections of one fetch. A connection is made only to the base's scheme, host and port: a redirect anywhere else is
 * refused before any request reaches it, so the credentials never go there. Every socket the fetch opens is kept, so that
 * {@link #abort()} can stop a fetch that waits on a silent server. Every byte received is counted, so that the cache's limit holds
 * while the fetch receives. TLS is always verified: the git configuration cannot turn the check off.
 */
final class GuardedHttps implements HttpConnectionFactory {

    /**
     * The JDK's https client writes every request's headers, the credentials among them, to this logger at {@code FINE}; it is held
     * at {@code INFO} for as long as this class is loaded, so a node's debug logging never shows the token.
     */
    private static final Logger JDK_HEADERS = Logger.getLogger("sun.net.www.protocol.http.HttpURLConnection");

    static {
        JDK_HEADERS.setLevel(Level.INFO);
    }

    /** Told the bytes the fetch has received so far; throws to stop it. */
    interface Receiving {
        void received(long bytes) throws IOException;
    }

    private final URI base;
    private final SSLSocketFactory tls;
    private final Receiving receiving;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final AtomicLong received = new AtomicLong();
    private final Sockets factory = new Sockets();
    private volatile boolean aborted;
    private volatile boolean refused;
    private volatile int status;

    /**
     * @param base the base the connections stay on
     * @param tls the TLS sockets, layered over the plain sockets this class opens and keeps
     * @param receiving told the bytes received after every read
     */
    GuardedHttps(URI base, SSLSocketFactory tls, Receiving receiving) {
        this.base = base;
        this.tls = tls;
        this.receiving = receiving;
    }

    /** Whether {@code scheme}, {@code host} and {@code port} (-1 for the default) are the base's. */
    static boolean sameOrigin(URI base, String scheme, String host, int port) {
        int basePort = base.getPort() == -1 ? 443 : base.getPort();
        return "https".equals(scheme) && host != null && host.toLowerCase(Locale.ROOT).equals(base.getHost().toLowerCase(Locale.ROOT))
                && (port == -1 ? 443 : port) == basePort;
    }

    @Override
    public HttpConnection create(URL url) throws IOException {
        return create(url, null);
    }

    @Override
    public HttpConnection create(URL url, Proxy proxy) throws IOException {
        if (aborted) throw new IOException("the fetch was stopped");
        if (!sameOrigin(base, url.getProtocol(), url.getHost(), url.getPort())) {
            refused = true;
            throw new IOException("a redirect away from the base's scheme and host is refused");
        }
        HttpsURLConnection connection = (HttpsURLConnection) (proxy == null ? url.openConnection() : url.openConnection(proxy));
        connection.setSSLSocketFactory(factory);
        return new Connection(connection);
    }

    /** Whether a connection away from the base was refused. */
    boolean refused() {
        return refused;
    }

    /** The status code of the last answer, 0 before the first. */
    int status() {
        return status;
    }

    /** Closes every socket the fetch opened, and refuses new ones: a read that waits fails at once. */
    void abort() {
        aborted = true;
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // the socket is closed or broken either way
            }
        }
    }

    private Socket kept(Socket socket) throws IOException {
        sockets.add(socket);
        if (aborted) {
            socket.close();
            throw new IOException("the fetch was stopped");
        }
        return socket;
    }

    /** Plain sockets this fetch keeps, with TLS layered over them. */
    private final class Sockets extends SSLSocketFactory {

        /** Unconnected: the caller connects it, and an abort can close it while it connects. */
        @Override
        public Socket createSocket() throws IOException {
            return kept(new Socket(Proxy.NO_PROXY));
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            if (!sockets.contains(socket)) kept(socket);
            return tls.createSocket(socket, host, port, autoClose);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(new InetSocketAddress(host, port));
            return createSocket(socket, host, port, true);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new InetSocketAddress(localHost, localPort));
            socket.connect(new InetSocketAddress(host, port));
            return createSocket(socket, host, port, true);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(new InetSocketAddress(host, port));
            return createSocket(socket, host.getHostName(), port, true);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new InetSocketAddress(localAddress, localPort));
            socket.connect(new InetSocketAddress(address, port));
            return createSocket(socket, address.getHostName(), port, true);
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return tls.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return tls.getSupportedCipherSuites();
        }
    }

    /** One request over {@link HttpsURLConnection}, its status kept and its answer counted. */
    private final class Connection implements HttpConnection {

        private final HttpsURLConnection connection;

        Connection(HttpsURLConnection connection) {
            this.connection = connection;
        }

        @Override
        public int getResponseCode() throws IOException {
            int code = connection.getResponseCode();
            status = code;
            return code;
        }

        @Override
        public URL getURL() {
            return connection.getURL();
        }

        @Override
        public String getResponseMessage() throws IOException {
            return connection.getResponseMessage();
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            return connection.getHeaderFields();
        }

        @Override
        public void setRequestProperty(String key, String value) {
            connection.setRequestProperty(key, value);
        }

        @Override
        public void setRequestMethod(String method) throws java.net.ProtocolException {
            connection.setRequestMethod(method);
        }

        @Override
        public void setUseCaches(boolean usecaches) {
            connection.setUseCaches(usecaches);
        }

        @Override
        public void setConnectTimeout(int timeout) {
            connection.setConnectTimeout(timeout);
        }

        @Override
        public void setReadTimeout(int timeout) {
            connection.setReadTimeout(timeout);
        }

        @Override
        public String getContentType() {
            return connection.getContentType();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new FilterInputStream(connection.getInputStream()) {
                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) receiving.received(received.incrementAndGet());
                    return b;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = super.read(buffer, offset, length);
                    if (n > 0) receiving.received(received.addAndGet(n));
                    return n;
                }
            };
        }

        @Override
        public String getHeaderField(String name) {
            return connection.getHeaderField(name);
        }

        @Override
        public List<String> getHeaderFields(String name) {
            return connection.getHeaderFields().entrySet().stream()
                    .filter(e -> name.equalsIgnoreCase(e.getKey()) && e.getValue() != null)
                    .flatMap(e -> e.getValue().stream())
                    .toList();
        }

        @Override
        public int getContentLength() {
            return connection.getContentLength();
        }

        @Override
        public void setInstanceFollowRedirects(boolean followRedirects) {
            connection.setInstanceFollowRedirects(followRedirects);
        }

        @Override
        public void setDoOutput(boolean dooutput) {
            connection.setDoOutput(dooutput);
        }

        @Override
        public void setFixedLengthStreamingMode(int contentLength) {
            connection.setFixedLengthStreamingMode(contentLength);
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return connection.getOutputStream();
        }

        @Override
        public void setChunkedStreamingMode(int chunklen) {
            connection.setChunkedStreamingMode(chunklen);
        }

        @Override
        public String getRequestMethod() {
            return connection.getRequestMethod();
        }

        @Override
        public boolean usingProxy() {
            return connection.usingProxy();
        }

        @Override
        public void connect() throws IOException {
            connection.connect();
        }

        /** Ignored: the server's certificate is always verified. */
        @Override
        public void configure(KeyManager[] km, TrustManager[] tm, SecureRandom random) {
            // never replaces the verifying TLS sockets
        }

        /** Ignored: the server's name is always verified. */
        @Override
        public void setHostnameVerifier(HostnameVerifier hostnameverifier) {
            // never replaces the verifying host name check
        }
    }
}
