package excalibur.api.handler;

import excalibur.api.endpoint.EndpointRegistry;
import excalibur.api.protocol.HttpMethod;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Endpoint registry for managing HTTP routes.
 * Thread-safe registration and lookup of endpoints.
 * <p>
 * Thread-safety:
 * This class is thread-safe for concurrent endpoint registration.
 * Endpoint handlers must be thread-safe as multiple request threads may invoke them.
 */
public class EndpointRegistry {
    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(EndpointRegistry.class);
    
    // Endpoint cache for fast lookup by path
    private final Map<String, Endpoint> endpoints = new ConcurrentHashMap<>();
    
    // Cache for path-to-method mappings
    private final Map<String, Set<String>> methodsByPath = new ConcurrentHashMap<>();
    
    // Read-write lock for path lookups
    private final ReentrantReadWriteLock pathLock = new ReentrantReadWriteLock();
    
    /**
     * Registers an endpoint at the given path.
     * 
     * @param path The endpoint path (e.g., "/v1/query")
     * @param method The HTTP method (GET, POST, PUT, DELETE)
     * @param handler The handler to invoke for matching requests
     * @throws IllegalArgumentException if path already exists with a different method
     */
    public void register(String path, HttpMethod method, Handler handler) {
        synchronized (endpoints) {
            if (endpoints.containsKey(path)) {
                HttpMethod existingMethod = endpoints.get(path).getMethod();
                if (!existingMethod.equals(method)) {
                    throw new IllegalArgumentException(
                        "Endpoint already exists at path " + path + " with method " + existingMethod);
                }
            }
            
            Endpoint endpoint = new Endpoint(path, method, handler);
            endpoints.put(path, endpoint);
            
            methodsByPath.computeIfAbsent(path, k -> new HashSet<>())
                .add(method.name());
            
            LOG.info("Registered endpoint: {} {}", path, method);
        }
    }
    
    /**
     * Removes an endpoint.
     * 
     * @param path The endpoint path
     * @return true if the endpoint was found and removed, false otherwise
     */
    public boolean remove(String path) {
        synchronized (endpoints) {
            Endpoint removed = endpoints.remove(path);
            if (removed != null) {
                methodsByPath.remove(path);
                LOG.info("Removed endpoint: {}", path);
                return true;
            }
            return false;
        }
    }
    
    /**
     * Checks if an endpoint exists at the given path.
     * 
     * @param path The endpoint path
     * @return true if the endpoint exists
     */
    public boolean hasEndpoint(String path) {
        return endpoints.containsKey(path);
    }
    
    /**
     * Checks if any endpoint exists at the given path.
     * 
     * @param path The endpoint path
     * @return true if at least one method is registered at this path
     */
    public boolean hasAnyMethod(String path) {
        pathLock.readLock().lock();
        try {
            return methodsByPath.containsKey(path);
        } finally {
            pathLock.readLock().unlock();
        }
    }
    
    /**
     * Gets all methods registered at a given path.
     * 
     * @param path The endpoint path
     * @return Set of registered HTTP methods, empty set if not found
     */
    public Set<String> getMethodsAtPath(String path) {
        pathLock.readLock().lock();
        try {
            return methodsByPath.getOrDefault(path, Collections.emptySet());
        } finally {
            pathLock.readLock().unlock();
        }
    }
    
    /**
     * Gets all registered endpoints.
     * 
     * @return Immutable map of all endpoints
     */
    public Map<String, Endpoint> getAllEndpoints() {
        return Collections.unmodifiableMap(endpoints);
    }
    
    /**
     * Finds all endpoints matching the given path prefix.
     * 
     * @param prefix Path prefix to match
     * @return List of matching endpoints
     */
    public List<String> findEndpointsByPrefix(String prefix) {
        List<String> results = new ArrayList<>();
        for (String path : endpoints.keySet()) {
            if (path.startsWith(prefix)) {
                results.add(path);
            }
        }
        return results;
    }
    
    /**
     * Checks if any endpoint matches the given URI path.
     * 
     * @param path The path to check
     * @return true if an endpoint matches
     */
    public boolean matchesPath(String path) {
        for (String registeredPath : endpoints.keySet()) {
            if (registeredPath.equals(path) || 
                registeredPath.startsWith(path + "/")) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * An endpoint definition.
     * 
     * @param path The endpoint path
     * @param method The HTTP method
     * @param handler The request handler
     */
    public static class Endpoint {
        private final String path;
        private final HttpMethod method;
        private final Handler handler;
        
        public Endpoint(String path, HttpMethod method, Handler handler) {
            this.path = path;
            this.method = method;
            this.handler = handler;
        }
        
        public String getPath() { return path; }
        public HttpMethod getMethod() { return method; }
        public Handler getHandler() { return handler; }
        
        @Override
        public String toString() {
            return String.format("Endpoint(path=%s, method=%s)", path, method);
        }
    }
    
    /**
     * Function interface for endpoint handlers.
     * <p>
     * Thread-safety: Handler implementations must be thread-safe
     * as multiple virtual threads may invoke them concurrently.
     */
    @FunctionalInterface
    public interface Handler {
        /**
         * Handles an HTTP request.
         * 
         * @param request The incoming HTTP request
         * @param context The HTTP context
         * @return CompletableFuture with the response
         */
        CompletableFuture<HttpResponse<ByteArrayBody>> handle(
            HttpRequest request, 
            HttpContext context); 
    }
}
