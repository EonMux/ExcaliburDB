/**
 * Choosing and locating the part of a passage a query matched.
 *
 * <p>Retrieval answers with identifiers and scores; this is what turns one of those into
 * something a person can read and check. It is deliberately independent of which engine
 * produced the result - a node answering from vector search alone has no lexical analyzer and
 * still owes its caller a reason - so everything here works on the passage text and the query
 * text and nothing else.</p>
 */
package io.excalibur.retrieval.snippet;
