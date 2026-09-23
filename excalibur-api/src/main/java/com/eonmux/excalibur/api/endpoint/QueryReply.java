package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * An answer to a query, decided but not yet written.
 *
 * <p>Deciding and writing are separated because a query can be withdrawn right up to the moment
 * it stops being cancellable, and that moment comes after its answer has been worked out. Every
 * route decides its answer while the query is registered, stops it being cancellable, and only
 * then writes - either what it decided, or the cancelled answer when a {@code DELETE} got there
 * first. Writing inside the registration would let a caller be told {@code "cancelled": true}
 * and then be sent the results anyway.</p>
 *
 * <p>Deferred rather than rendered: a reply that is about to be replaced by the cancelled one
 * should not have read chunk text or built an explanation first.</p>
 */
@FunctionalInterface
interface QueryReply {

    /**
     * Writes the answer.
     *
     * @param exchange The request to answer
     * @throws IOException if the response cannot be written
     */
    void send(HttpExchange exchange) throws IOException;

    /**
     * An error answer.
     *
     * @param status  The HTTP status
     * @param message The message the client is shown, written for a client to read
     * @return The reply
     */
    static QueryReply error(int status, String message) {
        return exchange -> ResponseBody.sendError(exchange, status, message);
    }
}
