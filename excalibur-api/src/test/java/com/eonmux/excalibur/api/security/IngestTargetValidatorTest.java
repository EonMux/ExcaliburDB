package com.eonmux.excalibur.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for the ingest target allow-lists.
 *
 * <p>These are the checks standing between an unauthenticated request and arbitrary
 * local file reads or requests into the server's own network, so the cases that matter
 * are the evasions: {@code ..} segments, symlinks pointing out of an allowed root, and
 * hostnames that resolve to loopback or private addresses.</p>
 */
@DisplayName("IngestTargetValidator")
class IngestTargetValidatorTest {

    @Nested
    @DisplayName("Filesystem targets")
    class FilesystemTargets {

        @Test
        @DisplayName("refuses every path when no root is allow-listed")
        void disabledByDefault(@TempDir Path tempDir) {
            IngestTargetValidator validator = new IngestTargetValidator(ApiSecurityConfig.defaults());

            assertThatThrownBy(() -> validator.resolveFilesystemRoot(tempDir.toString()))
                .isInstanceOf(IngestTargetRejectedException.class)
                .hasMessageContaining("disabled");
        }

        @Test
        @DisplayName("accepts a path inside an allowed root and returns its real path")
        void acceptsAllowedRoot(@TempDir Path tempDir) throws IOException {
            Path root = Files.createDirectory(tempDir.resolve("corpus"));
            Path nested = Files.createDirectory(root.resolve("nested"));
            IngestTargetValidator validator = validatorFor(root);

            assertThat(validator.resolveFilesystemRoot(nested.toString())).isEqualTo(nested.toRealPath());
        }

        @Test
        @DisplayName("refuses a path outside every allowed root")
        void refusesOutsideRoot(@TempDir Path tempDir) throws IOException {
            Path root = Files.createDirectory(tempDir.resolve("corpus"));
            Path other = Files.createDirectory(tempDir.resolve("secrets"));
            IngestTargetValidator validator = validatorFor(root);

            assertThatThrownBy(() -> validator.resolveFilesystemRoot(other.toString()))
                .isInstanceOf(IngestTargetRejectedException.class);
        }

        @Test
        @DisplayName("refuses a traversal out of an allowed root")
        void refusesTraversal(@TempDir Path tempDir) throws IOException {
            Path root = Files.createDirectory(tempDir.resolve("corpus"));
            Files.createDirectory(tempDir.resolve("secrets"));
            IngestTargetValidator validator = validatorFor(root);

            String traversal = root.resolve("..").resolve("secrets").toString();

            assertThatThrownBy(() -> validator.resolveFilesystemRoot(traversal))
                .isInstanceOf(IngestTargetRejectedException.class);
        }

        @Test
        @DisplayName("refuses a symlink that points out of an allowed root")
        void refusesSymlinkEscape(@TempDir Path tempDir) throws IOException {
            Path root = Files.createDirectory(tempDir.resolve("corpus"));
            Path secrets = Files.createDirectory(tempDir.resolve("secrets"));
            Path link = root.resolve("escape");
            try {
                Files.createSymbolicLink(link, secrets);
            } catch (UnsupportedOperationException | IOException e) {
                assumeTrue(false, "symlinks not supported on this filesystem");
            }
            IngestTargetValidator validator = validatorFor(root);

            // The link is lexically inside the root; only canonicalising first catches it.
            assertThat(link.startsWith(root)).isTrue();
            assertThatThrownBy(() -> validator.resolveFilesystemRoot(link.toString()))
                .isInstanceOf(IngestTargetRejectedException.class);
        }

        @Test
        @DisplayName("refuses a sibling whose name merely starts with the root name")
        void refusesPrefixSibling(@TempDir Path tempDir) throws IOException {
            Path root = Files.createDirectory(tempDir.resolve("corpus"));
            Path sibling = Files.createDirectory(tempDir.resolve("corpus-private"));
            IngestTargetValidator validator = validatorFor(root);

            assertThatThrownBy(() -> validator.resolveFilesystemRoot(sibling.toString()))
                .isInstanceOf(IngestTargetRejectedException.class);
        }

        @Test
        @DisplayName("refuses a path that does not exist")
        void refusesMissingPath(@TempDir Path tempDir) throws IOException {
            Path root = Files.createDirectory(tempDir.resolve("corpus"));
            IngestTargetValidator validator = validatorFor(root);

            assertThatThrownBy(() -> validator.resolveFilesystemRoot(root.resolve("missing").toString()))
                .isInstanceOf(IngestTargetRejectedException.class);
        }

        @Test
        @DisplayName("does not echo the rejected path back to the caller")
        void rejectionLeaksNothing(@TempDir Path tempDir) throws IOException {
            Path root = Files.createDirectory(tempDir.resolve("corpus"));
            Path other = Files.createDirectory(tempDir.resolve("secrets"));
            IngestTargetValidator validator = validatorFor(root);

            assertThatThrownBy(() -> validator.resolveFilesystemRoot(other.toString()))
                .hasMessageNotContaining(other.toString());
        }
    }

    @Nested
    @DisplayName("URL targets")
    class UrlTargets {

        @Test
        @DisplayName("refuses every URL when no host is allow-listed")
        void disabledByDefault() {
            IngestTargetValidator validator = new IngestTargetValidator(ApiSecurityConfig.defaults());

            assertThatThrownBy(() -> validator.resolveUrl("https://docs.example.com/"))
                .isInstanceOf(IngestTargetRejectedException.class)
                .hasMessageContaining("disabled");
        }

        @Test
        @DisplayName("refuses a host that is not allow-listed")
        void refusesUnknownHost() {
            IngestTargetValidator validator = validatorForHost("docs.example.com");

            assertThatThrownBy(() -> validator.resolveUrl("https://evil.example.net/"))
                .isInstanceOf(IngestTargetRejectedException.class);
        }

        @Test
        @DisplayName("refuses a scheme that is not http or https")
        void refusesOtherSchemes() {
            IngestTargetValidator validator = validatorForHost("localhost");

            assertThatThrownBy(() -> validator.resolveUrl("file:///etc/passwd"))
                .isInstanceOf(IngestTargetRejectedException.class);
            assertThatThrownBy(() -> validator.resolveUrl("gopher://localhost/"))
                .isInstanceOf(IngestTargetRejectedException.class);
        }

        @Test
        @DisplayName("refuses an allow-listed host that resolves to loopback")
        void refusesLoopback() {
            IngestTargetValidator validator = validatorForHost("127.0.0.1");

            assertThatThrownBy(() -> validator.resolveUrl("http://127.0.0.1:8080/admin"))
                .isInstanceOf(IngestTargetRejectedException.class);
        }

        @Test
        @DisplayName("refuses the cloud metadata address")
        void refusesMetadataEndpoint() {
            IngestTargetValidator validator = validatorForHost("169.254.169.254");

            assertThatThrownBy(() -> validator.resolveUrl("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(IngestTargetRejectedException.class);
        }

        @Test
        @DisplayName("refuses private address ranges")
        void refusesPrivateRanges() {
            assertThatThrownBy(() -> validatorForHost("10.0.0.5").resolveUrl("http://10.0.0.5/"))
                .isInstanceOf(IngestTargetRejectedException.class);
            assertThatThrownBy(() -> validatorForHost("192.168.1.7").resolveUrl("http://192.168.1.7/"))
                .isInstanceOf(IngestTargetRejectedException.class);
            assertThatThrownBy(() -> validatorForHost("172.16.3.9").resolveUrl("http://172.16.3.9/"))
                .isInstanceOf(IngestTargetRejectedException.class);
        }

        @Test
        @DisplayName("refuses the ranges the platform's address predicates do not cover")
        void refusesSharedBenchmarkAndTranslatedRanges() {
            // Carrier NAT and benchmarking space pass every InetAddress predicate, and a NAT64
            // address is IPv6 global unicast in form while its gateway delivers it to the IPv4
            // address it carries - here, the metadata endpoint.
            assertThatThrownBy(() -> validatorForHost("100.64.0.7").resolveUrl("http://100.64.0.7/"))
                .isInstanceOf(IngestTargetRejectedException.class);
            assertThatThrownBy(() -> validatorForHost("198.18.0.1").resolveUrl("http://198.18.0.1/"))
                .isInstanceOf(IngestTargetRejectedException.class);
            assertThatThrownBy(() -> validatorForHost("[64:ff9b::a9fe:a9fe]")
                    .resolveUrl("http://[64:ff9b::a9fe:a9fe]/latest/meta-data/"))
                .isInstanceOf(IngestTargetRejectedException.class);
            assertThat(validatorForHost("[64:ff9b::808:808]").resolveUrl("http://[64:ff9b::808:808]/"))
                .as("the same prefix carrying a public address is allowed, so it is the address "
                    + "that was refused above and not the allow-list")
                .isNotNull();
        }

        @Test
        @DisplayName("refuses a malformed URL")
        void refusesMalformed() {
            IngestTargetValidator validator = validatorForHost("docs.example.com");

            assertThatThrownBy(() -> validator.resolveUrl("ht!tp://docs.example.com"))
                .isInstanceOf(IngestTargetRejectedException.class);
            assertThatThrownBy(() -> validator.resolveUrl("   "))
                .isInstanceOf(IngestTargetRejectedException.class);
        }

        @Test
        @DisplayName("exposes the host of a validated URL in lower case")
        void hostOfIsNormalised() {
            assertThat(IngestTargetValidator.hostOf(URI.create("https://Docs.Example.COM/a")))
                .isEqualTo("docs.example.com");
        }
    }

    private static IngestTargetValidator validatorFor(Path root) {
        return new IngestTargetValidator(ApiSecurityConfig.builder().allowIngestRoot(root).build());
    }

    private static IngestTargetValidator validatorForHost(String host) {
        return new IngestTargetValidator(ApiSecurityConfig.builder().allowIngestHost(host).build());
    }
}
