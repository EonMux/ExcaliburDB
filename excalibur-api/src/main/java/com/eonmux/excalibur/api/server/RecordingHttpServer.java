package com.eonmux.excalibur.api.server;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * An {@link HttpServer} that remembers every context path registered on it.
 *
 * <p>{@code HttpServer} offers no way to list its contexts, and the route table is the one thing
 * the node's HTTP contract is stated over: every registered path answers a method it does not
 * serve with 405, every path it does not register answers 404. The registrar builds its routes
 * through this, so the set of paths it registered can be read back and checked rather than
 * restated by hand in a test that would drift from it.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Registration is single-threaded, as {@code start()} is; everything else delegates.</p>
 *
 * @author ExcaliburDB
 */
final class RecordingHttpServer extends HttpServer {

    private final HttpServer delegate;
    private final List<String> paths = new ArrayList<>();

    /**
     * Wraps a server.
     *
     * @param delegate The server contexts are registered on
     */
    RecordingHttpServer(HttpServer delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
    }

    /**
     * The context paths registered so far, in registration order.
     *
     * @return An immutable copy
     */
    List<String> paths() {
        return List.copyOf(paths);
    }

    @Override
    public HttpContext createContext(String path, HttpHandler handler) {
        HttpContext context = delegate.createContext(path, handler);
        paths.add(path);
        return context;
    }

    @Override
    public HttpContext createContext(String path) {
        HttpContext context = delegate.createContext(path);
        paths.add(path);
        return context;
    }

    @Override
    public void removeContext(String path) {
        delegate.removeContext(path);
        paths.remove(path);
    }

    @Override
    public void removeContext(HttpContext context) {
        delegate.removeContext(context);
        paths.remove(context.getPath());
    }

    @Override
    public void bind(InetSocketAddress address, int backlog) throws IOException {
        delegate.bind(address, backlog);
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void setExecutor(Executor executor) {
        delegate.setExecutor(executor);
    }

    @Override
    public Executor getExecutor() {
        return delegate.getExecutor();
    }

    @Override
    public void stop(int delay) {
        delegate.stop(delay);
    }

    @Override
    public InetSocketAddress getAddress() {
        return delegate.getAddress();
    }
}
