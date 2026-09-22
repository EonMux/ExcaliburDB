package com.eonmux.excalibur.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixes what counts as a usable correlation id.
 *
 * <p>The accepted set has to be wide enough for the identifier formats real deployments emit
 * and narrow enough that nothing in it can change the meaning of a header.</p>
 */
class CorrelationIdTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "7f3c1a20-0b1e-4a6d-9f11-2c3d4e5f6071",     // UUID
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", // W3C traceparent
        "req_abc123",
        "trace.id:42",
        "A",
        "0123456789"
    })
    @DisplayName("identifier formats in ordinary use pass through unchanged")
    void ordinaryIdentifiersArePreserved(String id) {
        assertTrue(CorrelationId.isValid(id));
        assertEquals(id, CorrelationId.sanitize(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "abc\r\nX-Injected: yes",   // header injection
        "abc\ndef",
        "abc\rdef",
        "abc def",                  // a space ends a header value's first token
        "abc\tdef",
        "abc\u0000def",
        "abc\u007fdef",
        "naïve",                    // non-ASCII has no defined header encoding
        "a;b",
        "a,b",
        "\"quoted\""
    })
    @DisplayName("anything that could change a header's meaning is replaced")
    void hostileIdentifiersAreReplaced(String id) {
        assertFalse(CorrelationId.isValid(id));

        String safe = CorrelationId.sanitize(id);

        assertNotEquals(id, safe);
        assertTrue(CorrelationId.isValid(safe), "The replacement must itself be valid: " + safe);
    }

    @Test
    @DisplayName("a value past the length limit is replaced with a short one")
    void overlongIsReplaced() {
        String overlong = "a".repeat(CorrelationId.MAX_LENGTH + 1);

        assertFalse(CorrelationId.isValid(overlong));

        String safe = CorrelationId.sanitize(overlong);

        assertTrue(safe.length() <= CorrelationId.MAX_LENGTH);
        assertTrue(CorrelationId.isValid(safe));
    }

    @Test
    @DisplayName("a value exactly at the limit is still accepted")
    void atTheLimitIsAccepted() {
        String atLimit = "a".repeat(CorrelationId.MAX_LENGTH);

        assertTrue(CorrelationId.isValid(atLimit));
        assertEquals(atLimit, CorrelationId.sanitize(atLimit));
    }

    @Test
    @DisplayName("no id means no header, rather than an invented one")
    void absentMeansAbsent() {
        assertNull(CorrelationId.sanitize(null));
        assertNull(CorrelationId.sanitize(""));
        assertNull(CorrelationId.sanitize("   "));
        assertFalse(CorrelationId.isValid(null));
    }

    @Test
    @DisplayName("generated ids are valid, distinct and marked as generated")
    void generatedIdsAreUsable() {
        String first = CorrelationId.generate();
        String second = CorrelationId.generate();

        assertTrue(CorrelationId.isValid(first));
        assertNotEquals(first, second, "A shared id would correlate unrelated requests");
        assertTrue(first.startsWith("gen-"),
            "A substitution has to be recognisable as one when reading a log");
    }
}
