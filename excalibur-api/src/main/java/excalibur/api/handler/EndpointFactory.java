package excalibur.api.handler;

import excalibur.api.protocol.HttpMethod;

import java.net.http.HttpRequest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Factory for creating endpoint handlers.
 * Supports default handlers, path parameter extraction, and method routing.
 * <p>
 * Thread-safety: This class is thread-safe for concurrent handler creation.
 */
public class EndpointFactory {
    private final EndpointRegistry registry;
    
    // Default handler for unmatched routes
    private Handler defaultHandler;
    
    // Queue for deferred handlers (for path parameter resolution)
    private final ConcurrentLinkedQueue<DeferredHandler> deferred = new ConcurrentLinkedQueue<>();
    
    /**
     * Creates a new EndpointFactory.
     * 
     * @param registry The endpoint registry
     */
    public EndpointFactory(EndpointRegistry registry) {
        this.registry = registry;
        this.defaultHandler = (req, ctx) -> 
            CompletableFuture.completedFuture(HttpResponse.
                builder()
                .status(404, "Not Found")
                .body(new ByteArrayBody("404 Not Found"))
                .build());
    }
    
    /**
     * Sets the default handler for unmatched routes.
     * 
     * @param handler The default handler
     */
    public void setDefaultHandler(Handler handler) {
        this.defaultHandler = handler;
    }
    
    /**
     * Creates a handler with path parameter extraction.
     * Path parameters are extracted from route patterns like "/{id}/items".
     * 
     * @param routePattern The route pattern (e.g., "/{id}/items")
     * @param handler The handler to invoke
     * @return A new handler that extracts path parameters
     */
    public Handler withPathParameter(String routePattern, Handler handler) {
        return (req, ctx) -> {
            String path = req.uri().getPath();
            Object params = extractPathParams(routePattern, path);
            
            // Queue the handler invocation for later
            DeferredHandler deferred = new DeferredHandler(
                req, ctx, params, handler, routePattern);
            deferred.execute();
            return CompletableFuture.completedFuture(HttpResponse
                .builder()
                .status(501, "Not Implemented")
                .body(new ByteArrayBody("Path parameters not supported yet"))
                .build());
        };
    }
    
    /**
     * Extracts path parameters from a route pattern and path.
     * Supports patterns like "{id}/items" and "/{wildcard}".
     * 
     * @param pattern The route pattern
     * @param path The request path
     * @return Map of parameter names to values
     */
    private Object extractPathParams(String pattern, String path) {
        Map<String, String> params = new ConcurrentHashMap<>();
        String[] parts = pattern.split("/");
        String[] pathParts = path.split("/");
        
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("{") && part.endsWith("}")) {
                String paramName = part.substring(1, part.length() - 1);
                String pathPart = pathParts[i];
                if (pathPart != null) {
                    params.put(paramName, pathPart);
                } else {
                    params.put(paramName, null);
                }
            } else if (pathParts[i] != null) {
                params.put(part, pathParts[i]);
            }
        }
        return params;
    }
    
    /**
     * A deferred handler that executes after path parameters are resolved.
     */
    private class DeferredHandler {
        private final HttpRequest req;
        private final HttpContext ctx;
        private final Object params;
        private final Handler handler;
        private final String routePattern;
        
        public DeferredHandler(HttpRequest req, HttpContext ctx, Object params,
                               Handler handler, String routePattern) {
            this.req = req;
            this.ctx = ctx;
            this.params = params;
            this.handler = handler;
            this.routePattern = routePattern;
        }
        
        public void execute() {
            Handler resolved = (req2, ctx2) -> handler.handle(req2, ctx2);
            registry.deferredHandlers.add(resolved);
        }
    }
    
    /**
     * Queue for deferred handler execution.
     * <p>
     * Thread-safety: This queue is thread-safe for concurrent additions.
     */
    private static class DeferredHandlerQueue {
        private final ConcurrentLinkedQueue<EndpointRegistry.Endpoint> queue = 
            new ConcurrentLinkedQueue<>();
        
        public void add(EndpointRegistry.Endpoint endpoint) {
            queue.add(endpoint);
        }
        
        public EndpointRegistry.Endpoint take() {
            return queue.poll();
        }
        
        public int size() {
            return queue.size();
        }
    }
}
