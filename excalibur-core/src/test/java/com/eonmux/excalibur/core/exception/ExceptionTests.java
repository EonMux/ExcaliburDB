package com.eonmux.excalibur.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for exception classes.
 */
@DisplayName("Exception Tests")
class ExceptionTests {

    @Nested
    @DisplayName("ExcaliburException Tests")
    class ExcaliburExceptionTests {

        @Test
        @DisplayName("Should create with message")
        void shouldCreateWithMessage() {
            ExcaliburException ex = new ExcaliburException("Test error");
            assertThat(ex.getMessage()).isEqualTo("Test error");
        }

        @Test
        @DisplayName("Should create with message and cause")
        void shouldCreateWithMessageAndCause() {
            Throwable cause = new RuntimeException("Cause");
            ExcaliburException ex = new ExcaliburException("Test error", cause);
            assertThat(ex.getMessage()).isEqualTo("Test error");
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }
}
