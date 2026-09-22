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

    @Nested
    @DisplayName("FileException Tests")
    class FileExceptionTests {

        @Test
        @DisplayName("Should create with message")
        void shouldCreateWithMessage() {
            FileException ex = new FileException("File error");
            assertThat(ex.getMessage()).isEqualTo("File error");
            assertThat(ex).isInstanceOf(ExcaliburException.class);
        }

        @Test
        @DisplayName("Should create with message and cause")
        void shouldCreateWithMessageAndCause() {
            Throwable cause = new RuntimeException("IO error");
            FileException ex = new FileException("File error", cause);
            assertThat(ex.getMessage()).isEqualTo("File error");
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }

    @Nested
    @DisplayName("CodecException Tests")
    class CodecExceptionTests {

        @Test
        @DisplayName("Should create with message")
        void shouldCreateWithMessage() {
            CodecException ex = new CodecException("Encoding failed");
            assertThat(ex.getMessage()).isEqualTo("Encoding failed");
            assertThat(ex).isInstanceOf(ExcaliburException.class);
        }

        @Test
        @DisplayName("Should create with message and cause")
        void shouldCreateWithMessageAndCause() {
            Throwable cause = new RuntimeException("Invalid data");
            CodecException ex = new CodecException("Encoding failed", cause);
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }

    @Nested
    @DisplayName("HashException Tests")
    class HashExceptionTests {

        @Test
        @DisplayName("Should create with message")
        void shouldCreateWithMessage() {
            HashException ex = new HashException("Hash failed");
            assertThat(ex.getMessage()).isEqualTo("Hash failed");
            assertThat(ex).isInstanceOf(ExcaliburException.class);
        }

        @Test
        @DisplayName("Should create with message and cause")
        void shouldCreateWithMessageAndCause() {
            Throwable cause = new RuntimeException("Overflow");
            HashException ex = new HashException("Hash failed", cause);
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }

    @Nested
    @DisplayName("AtomicException Tests")
    class AtomicExceptionTests {

        @Test
        @DisplayName("Should create with message")
        void shouldCreateWithMessage() {
            AtomicException ex = new AtomicException("Atomic failed");
            assertThat(ex.getMessage()).isEqualTo("Atomic failed");
            assertThat(ex).isInstanceOf(ExcaliburException.class);
        }

        @Test
        @DisplayName("Should create with message and cause")
        void shouldCreateWithMessageAndCause() {
            Throwable cause = new RuntimeException("CAS failed");
            AtomicException ex = new AtomicException("Atomic failed", cause);
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }

    @Nested
    @DisplayName("ChecksumException Tests")
    class ChecksumExceptionTests {

        @Test
        @DisplayName("Should create with message")
        void shouldCreateWithMessage() {
            ChecksumException ex = new ChecksumException("Checksum mismatch");
            assertThat(ex.getMessage()).isEqualTo("Checksum mismatch");
            assertThat(ex).isInstanceOf(ExcaliburException.class);
        }

        @Test
        @DisplayName("Should create with message and cause")
        void shouldCreateWithMessageAndCause() {
            Throwable cause = new RuntimeException("Data corrupted");
            ChecksumException ex = new ChecksumException("Checksum mismatch", cause);
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }

    @Nested
    @DisplayName("AllocationException Tests")
    class AllocationExceptionTests {

        @Test
        @DisplayName("Should create with message")
        void shouldCreateWithMessage() {
            AllocationException ex = new AllocationException("Out of memory");
            assertThat(ex.getMessage()).isEqualTo("Out of memory");
            assertThat(ex).isInstanceOf(ExcaliburException.class);
        }

        @Test
        @DisplayName("Should create with message and cause")
        void shouldCreateWithMessageAndCause() {
            Throwable cause = new RuntimeException("Segment full");
            AllocationException ex = new AllocationException("Out of memory", cause);
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }

    @Nested
    @DisplayName("MappedFileException Tests")
    class MappedFileExceptionTests {

        @Test
        @DisplayName("Should create with message")
        void shouldCreateWithMessage() {
            MappedFileException ex = new MappedFileException("Mapping failed");
            assertThat(ex.getMessage()).isEqualTo("Mapping failed");
            assertThat(ex).isInstanceOf(ExcaliburException.class);
        }

        @Test
        @DisplayName("Should create with message and cause")
        void shouldCreateWithMessageAndCause() {
            Throwable cause = new RuntimeException("IO error");
            MappedFileException ex = new MappedFileException("Mapping failed", cause);
            assertThat(ex.getMessage()).isEqualTo("Mapping failed");
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }
}
