/**
 * HTTP API server for ExcaliburDB.
 *
 * <p>This package provides the HTTP server implementation that exposes
 * ExcaliburDB's API endpoints via the built-in
 * {@link com.sun.net.httpserver.HttpServer}.</p>
 *
 * <h2>Core Components</h2>
 * <ul>
 *   <li>{@link excalibur.api.server.ExcaliburDBServer} - Main HTTP server with virtual thread dispatch</li>
 * </ul>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Virtual thread executor for efficient concurrent request handling</li>
 *   <li>Graceful shutdown with configurable timeout</li>
 *   <li>Structured logging for all requests</li>
 *   <li>Automatic error handling and JSON responses</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All classes in this package are thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 9.0
 */
package excalibur.api.server;
