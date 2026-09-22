package com.eonmux.excalibur.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixes what survives redaction and what does not.
 *
 * <p>Removing too much makes a failure message useless; removing too little defeats the point.
 * The line is the userinfo component and nothing else.</p>
 */
class SafeUrlTest {

    @ParameterizedTest
    @CsvSource({
        "http://localhost:11434,                            http://localhost:11434",
        "https://api.provider.com/v1/embeddings,            https://api.provider.com/v1/embeddings",
        "http://user:pass@localhost:11434,                  http://***@localhost:11434",
        "https://user:pass@api.provider.com/v1/embeddings,  https://***@api.provider.com/v1/embeddings",
        "https://token@api.provider.com,                    https://***@api.provider.com",
        "http://a:b@c:1/path?q=1,                           http://***@c:1/path?q=1"
    })
    @DisplayName("credentials go, everything that identifies the endpoint stays")
    void redactsOnlyTheCredentials(String input, String expected) {
        assertEquals(expected.trim(), SafeUrl.redact(input.trim()));
    }

    @Test
    @DisplayName("a null URL redacts to null rather than to a literal")
    void nullIsPreserved() {
        assertNull(SafeUrl.redact(null));
    }

    @Test
    @DisplayName("an at-sign in the path is not mistaken for credentials")
    void atSignInPathIsNotAuthority() {
        assertEquals("https://api.provider.com/users/a@b.com",
            SafeUrl.redact("https://api.provider.com/users/a@b.com"),
            "The authority ends at the first slash; an at-sign after it is just a path");
    }

    @Test
    @DisplayName("a password containing an at-sign is still fully removed")
    void atSignInsidePasswordIsStillRemoved() {
        String redacted = SafeUrl.redact("http://user:p@ss@localhost:11434/v1");

        assertFalse(redacted.contains("p@ss"), "Leaked: " + redacted);
        assertEquals("http://***@localhost:11434/v1", redacted);
    }

    @Test
    @DisplayName("a value with no scheme is left alone")
    void schemelessIsLeftAlone() {
        assertEquals("localhost:11434", SafeUrl.redact("localhost:11434"));
    }

    @Test
    @DisplayName("redaction leaves a marker so a reader knows credentials were configured")
    void redactionIsVisible() {
        assertTrue(SafeUrl.redact("http://u:p@host").contains("***"),
            "Silently dropping them would hide a misconfiguration worth seeing");
    }
}
