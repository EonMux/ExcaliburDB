package excalibur.api;

import excalibur.api.protocol.*;
import excalibur.api.endpoint.*;
import excalibur.api.handler.*;
import excalibur.api.service.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpServer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * HTTP Server for ExcaliburDB API.
 * Uses Java's built-in HttpServer with virtual threads for request dispatch.
 * <p>
 * Thread-safety:
 * This class is NOT thread-safe. Each HTTP request runs in its own virtual thread
 * via the HttpServer framework. The endpoints themselves must be thread-safe.
 */
public class HttpServer {
    private static final Logger LOG = LoggerFactory.getLogger(HttpServer.class);
    
    private final int port;
    private final int addressPort;
    private final boolean bindToAllInterfaces;
    private final int backlog;
    private final int maxRequestHeaders;
    private final int maxRequestLineSize;
    private final int maxPostBodySize;
    private final HttpServer server;
    private volatile boolean running = false;
    private volatile boolean shutdownStarted = false;
    
    // Configuration
    private final EndpointRegistry registry;
    private final EndpointFactory factory;
    
    /**
     * Creates a new HTTP server with default configuration.
     * 
     * @param port Server port
     */
    public HttpServer(int port) {
        this(port, false, 1024, 8192, 16384, 104857600);
    }
    
    /**
     * Creates a new HTTP server with custom configuration.
     * 
     * @param port Server port
     * @param bindToAllInterfaces Whether to bind to all network interfaces (true for localhost)
     * @param backlog TCP backlog queue size
     * @param maxRequestHeaders Maximum HTTP request headers (in bytes)
     * @param maxRequestLineSize Maximum HTTP request line length (in bytes)
     * @param maxPostBodySize Maximum POST body size (in bytes)
     */
    public HttpServer(int port, boolean bindToAllInterfaces, int backlog,
                      int maxRequestHeaders, int maxRequestLineSize, int maxPostBodySize) {
        this.port = port;
        this.addressPort = bindToAllInterfaces ? port : port + 1;
        this.bindToAllInterfaces = bindToAllInterfaces;
        this.backlog = backlog;
        this.maxRequestHeaders = maxRequestHeaders;
        this.maxRequestLineSize = maxRequestLineSize;
        this.maxPostBodySize = maxPostBodySize;
        
        this.registry = new EndpointRegistry();
        this.factory = new EndpointFactory(registry);
        
        // Register default endpoints
        registerDefaultEndpoints();
    }
    
    /**
     * Sets the endpoint factory to use.
     * 
     * @param factory Endpoint factory to use
     */
    public void setEndpointFactory(EndpointFactory factory) {
        this.factory = factory;
    }
    
    /**
     * Sets the endpoint registry to use.
     * 
     * @param registry Endpoint registry to use
     */
    public void setEndpointRegistry(EndpointRegistry registry) {
        this.registry = registry;
    }
    
    /**
     * Registers the default endpoints for ExcaliburDB.
     */
    private void registerDefaultEndpoints() {
        // Health endpoint
        registry.register(
            "/health",
            HttpMethod.GET,
            (request, context) -> new HealthHandler(context, registry).handle()
        );
        
        // Query endpoint
        registry.register(
            "/v1/query",
            HttpMethod.POST,
            (request, context) -> new QueryHandler(context, registry).handle()
        );
        
        // Ingest endpoint
        registry.register(
            "/v1/ingest",
            HttpMethod.POST,
            (request, context) -> new IngestHandler(context, registry).handle()
        );
        
        // Reindex endpoint
        registry.register(
            "/v1/reindex",
            HttpMethod.POST,
            (request, context) -> new ReindexHandler(context, registry).handle()
        );
        
        // Stats endpoint
        registry.register(
            "/v1/stats",
            HttpMethod.GET,
            (request, context) -> new StatsHandler(context, registry).handle()
        );
        
        // Ingest status endpoint
        registry.register(
            "/v1/ingest/status",
            HttpMethod.GET,
            (request, context) -> new IngestStatusHandler(context, registry).handle()
        );
    }
    
    /**
     * Starts the HTTP server.
     * 
     * @throws IOException if the server cannot be started
     */
    public void start() throws IOException {
        if (running) {
            LOG.warn("Server is already running on port {}", port);
            return;
        }
        
        InetSocketAddress address = bindAddress();
        server = HttpServer.create(address, backlog);
        
        server.createDecorator((request, handler) -> {
            // Add correlation header to all requests
            String correlationId = request.headers().firstValue("X-Correlation-ID");
            correlationId = correlationId.orElse(generateCorrelationId());
            request = HttpRequest.newBuilder(request)
                .header("X-Correlation-ID", correlationId)
                .build();
            return handler.handle(request);
        });
        
        server.start();
        running = true;
        
        LOG.info("ExcaliburDB HTTP server started on http://{}:{}", 
                 address.getAddress().getHostAddress(), address.getPort());
        LOG.info("Bind to all interfaces: {}", bindToAllInterfaces);
        LOG.info("Max request headers: {} bytes", maxRequestHeaders);
        LOG.info("Max request line size: {} bytes", maxRequestLineSize);
        LOG.info("Max POST body size: {} bytes", maxPostBodySize);
    }
    
    /**
     * Gets the server address.
     * 
     * @return The server's InetSocketAddress
     */
    public InetSocketAddress getAddress() {
        if (server == null) return null;
        try {
            return server.getAddress();
        } catch (IOException e) {
            LOG.error("Failed to get server address", e);
            return null;
        }
    }
    
    /**
     * Gets the local address the server is listening on.
     * 
     * @return The local InetSocketAddress
     */
    public InetSocketAddress getLocalAddress() {
        if (server == null) return null;
        try {
            return server.getLocalAddress();
        } catch (IOException e) {
            LOG.error("Failed to get local address", e);
            return null;
        }
    }
    
    /**
     * Checks if the server is running.
     * 
     * @return true if the server is running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Gracefully shuts down the server.
     * 
     * @param timeout Maximum time to wait for shutdown in seconds
     * @return true if shutdown completed, false if timed out
     */
    public boolean shutdownGracefully(int timeout, TimeUnit unit) {
        if (shutdownStarted) {
            LOG.debug("Shutdown already in progress");
            return true;
        }
        
        shutdownStarted = true;
        server.shutdown();
        running = false;
        
        try {
            boolean completed = server.await(timeout, unit);
            if (!completed) {
                LOG.warn("Server shutdown timed out after {}", timeout + " seconds");
                server.shutdownNow();
            }
            return completed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Server shutdown interrupted", e);
            server.shutdownNow();
            return false;
        } catch (IOException e) {
            LOG.error("Server shutdown failed", e);
            server.shutdownNow();
            return false;
        }
    }
    
    /**
     * Gets the endpoint registry.
     * 
     * @return The endpoint registry
     */
    public EndpointRegistry getRegistry() {
        return registry;
    }
    
    /**
     * Generates a unique correlation ID for request tracking.
     * 
     * @return A unique correlation ID
     */
    private String generateCorrelationId() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            return String.format("req-%s-%s", 
                hostname.hashCode(), 
                System.nanoTime());
        } catch (Exception e) {
            return String.format("req-%s", System.nanoTime());
        }
    }
    
    /**
     * Binds the server to a specific address and port.
     * 
     * @return The InetSocketAddress to bind to
     */
    private InetSocketAddress bindAddress() throws IOException {
        String host = bindToAllInterfaces ? "0.0.0.0" : "127.0.0.1";
        return new InetSocketAddress(host, addressPort);
    }
    
    /**
     * Blocks until the server shuts down.
     * 
     * @throws InterruptedException if shutdown is interrupted
     */
    public void waitForShutdown() throws InterruptedException {
        server.awaitShutdown();
    }
    
    /**
     * Shuts down the server forcefully.
     * 
     * @throws IOException if shutdown fails
     */
    public void shutdownForcibly() throws IOException {
        if (running) {
            server.shutdownNow();
            running = false;
        }
    }
    
    /**
     * Creates a new HttpServer instance for testing.
     * 
     * @param port Server port
     * @return A new HttpServer instance
     */
    public static HttpServer createForTesting(int port) {
        return new HttpServer(port, true, 1024, 8192, 16384, 104857600);
    }
}
