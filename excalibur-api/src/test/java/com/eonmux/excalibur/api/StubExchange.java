package com.eonmux.excalibur.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * An exchange that behaves like the server's for the parts a handler touches: headers,
 * attributes, the body in both directions and the status it was answered with.
 *
 * <p>A mock answers null for an attribute it was just given, which is exactly what the code that
 * carries the request id and the audit targets across a request relies on not happening.</p>
 */
public final class StubExchange extends HttpExchange {

    private final String method;
    private final URI uri;
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final Map<String, Object> attributes = new HashMap<>();
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private final InetSocketAddress remote;
    private InputStream requestBody;
    private int responseCode = -1;

    /**
     * An exchange from {@code 203.0.113.9}.
     *
     * @param method The request method
     * @param uri    The request URI, path and query
     * @param body   The request body, or null for none
     */
    public StubExchange(String method, String uri, String body) {
        this(method, uri, body, new InetSocketAddress("203.0.113.9", 40000));
    }

    /**
     * An exchange from a given peer.
     *
     * @param method The request method
     * @param uri    The request URI, path and query
     * @param body   The request body, or null for none
     * @param remote The peer
     */
    public StubExchange(String method, String uri, String body, InetSocketAddress remote) {
        this.method = method;
        this.uri = URI.create(uri);
        this.remote = remote;
        this.requestBody = new ByteArrayInputStream(
            (body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
    }

    /** Sets a request header, returning this exchange. */
    public StubExchange header(String name, String value) {
        requestHeaders.set(name, value);
        return this;
    }

    /** @return the response body as text */
    public String responseText() {
        return responseBody.toString(StandardCharsets.UTF_8);
    }

    @Override
    public Headers getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public Headers getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public URI getRequestURI() {
        return uri;
    }

    @Override
    public String getRequestMethod() {
        return method;
    }

    @Override
    public HttpContext getHttpContext() {
        return null;
    }

    @Override
    public void close() {
    }

    @Override
    public InputStream getRequestBody() {
        return requestBody;
    }

    @Override
    public OutputStream getResponseBody() {
        return responseBody;
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) {
        if (responseCode != -1) {
            throw new IllegalStateException("headers already sent");
        }
        responseCode = rCode;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return remote;
    }

    @Override
    public int getResponseCode() {
        return responseCode;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress("127.0.0.1", 8080);
    }

    @Override
    public String getProtocol() {
        return "HTTP/1.1";
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
    }

    @Override
    public void setStreams(InputStream i, OutputStream o) {
        if (i != null) {
            requestBody = i;
        }
    }

    @Override
    public HttpPrincipal getPrincipal() {
        return null;
    }
}
