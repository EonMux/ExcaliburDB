package excalibur.api.handler;

import excalibur.config.Config;
import excalibur.metrics.Metrics;
import excalibur.api.protocol.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Handler for the /health endpoint.
 * 
 * Thread-safety: This class is thread-safe for reading. The Context object
 * is passed by reference, so modifications are visible to subsequent calls.
 */
public final class HealthHandler {
    private static final Logger LOG = LoggerFactory.getLogger(HealthHandler.class);
    
    private final HttpContext context;
    private final EndpointRegistry registry;
    
    public HealthHandler(HttpContext context, EndpointRegistry registry) {
        this.context = context;
        this.registry = registry;
    }
    
    /**
     * Handles the health check request.
     * 
     * Response format:
     * {
     *   "live": true,
     *   "ready": <boolean>,
     *   "epoch": <long>,
     *   "coverage": <float>
     * }
     * 
     * @return HTTP response with health status
     */
    public HttpResponse handle() {
        // Load configuration (may use defaults)
        Config config = ConfigLoader.load(this, registry);
        
        // Get current visibility epoch
        long currentEpoch = Metrics.getCurrentEpoch();
        
        // Get estimated corpus coverage
        double coverage = Metrics.getEstimatedCoverage();
        
        // Determine if ready based on config threshold
        boolean ready = config.readinessThreshold() <= coverage;
        
        // Build response body
        String responseBody = generateHealthResponse(config, ready, currentEpoch, coverage);
        
        // Send response
        response(context, "200 OK", "application/json", responseBody);
        
        LOG.debug("Health check: live=true, ready={}, epoch={}, coverage={}", ready, currentEpoch, coverage);
        
        return HttpResponse.empty();
    }
    
    /**
     * Generates the health endpoint response body.
     * 
     * @param config Configuration
     * @param ready Whether system is ready
     * @param currentEpoch Current visibility epoch
     * @param coverage Estimated corpus coverage
     * @return JSON response body
     */
    private String generateHealthResponse(Config config, boolean ready, long currentEpoch, double coverage) {
        return Json.builder()
            .put("live", true)
            .put("ready", ready)
            .put("epoch", currentEpoch)
            .put("coverage", coverage)
            .build();
    }
    
    /**
     * Sends an HTTP response.
     * 
     * @param context Request context
     * @param statusCode HTTP status code
     * @param contentType Content type
     * @param body Response body
     */
    private void response(HttpContext context, int statusCode, String contentType, String body) {
        context.setStatus(statusCode);
        context.setContentType(contentType);
        context.setBody(body);
    }
}
