package excalibur.api.handler;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

/**
 * HTTP context providing request details and response handling.
 * <p>
 * Thread-safety: This class is thread-safe for use across concurrent requests.
 */
public class HttpContext {
    private final HttpRequest request;
    private final URI uri;
    private final String method;
    private final String protocol;
    private final Map<String, String> headers;
    private final InputStream body;
    private final long bodySize;
    private int statusCode = 200;
    private String statusText = "OK";
    
    public HttpContext(HttpRequest request, URI uri) {
        this.request = request;
        this.uri = uri;
        this.method = request.method().name();
        this.protocol = request.protocol().name();
        this.headers = request.headers();
        this.body = request.body();
        this.bodySize = request.bodySize();
    }
    
    public HttpRequest getRequest() { return request; }
    public URI getUri() { return uri; }
    public String getMethod() { return method; }
    public String getProtocol() { return protocol; }
    public Map<String, String> getHeaders() { return headers; }
    public InputStream getBody() { return body; }
    public long getBodySize() { return bodySize; }
    
    public String getHeader(String name) {
        return headers.get(name);
    }
    
    public String getHeader(String name, String defaultVal) {
        return headers.getOrDefault(name, defaultVal);
    }
    
    public int getStatusCode() { return statusCode; }
    public String getStatusText() { return statusText; }
    
    public void setStatus(int code, String text) {
        this.statusCode = code;
        this.statusText = text;
    }
    
    public void setStatus(int code) {
        this.statusCode = code;
    }
}
