package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.file.LogInspection;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A staging log shorter than its header is either its own creation cut short or someone else's
 * bytes, and the two are opened very differently.
 *
 * <p>The header is the first thing ever written to one of these files, so a crash inside that
 * write leaves a prefix of it: a log that never held a record, which the open starts afresh. Any
 * other short file was not written by this log, and appending a header after it would leave a file
 * no later open accepts - so it is refused, and left exactly as it was.</p>
 */
@DisplayName("A staging log shorter than its header")
class TornHeaderTest {

    private static final Provenance DOCUMENT = Provenance.of(SourceType.DOCUMENT, 7, 0.9, 1);

    private static byte[] header(long magic, int version) {
        return ByteBuffer.allocate(16).putLong(magic).putInt(version).putInt(0).array();
    }

    private static final byte[] JUNK = "not a log".getBytes(StandardCharsets.US_ASCII);

    @Nested
    @DisplayName("a label interner")
    class Labels {

        private Path file(Path dir, int prefix) throws IOException {
            Path file = dir.resolve(GraphStagingStore.ENTITY_LABELS);
            Files.write(file, Arrays.copyOf(header(LabelInterner.MAGIC, LabelInterner.VERSION),
                prefix));
            return file;
        }

        @ParameterizedTest(name = "a {0}-byte prefix of its header opens fresh and survives a restart")
        @ValueSource(ints = {1, 7, 8, 12, 15})
        void aTornHeaderStartsFresh(int prefix, @TempDir Path dir) throws IOException {
            Path file = file(dir, prefix);

            LogInspection inspected = LabelInterner.inspect(file);
            assertThat(inspected.records()).isZero();
            assertThat(inspected.intactBytes()).isZero();
            assertThat(inspected.tornBytes()).isEqualTo(prefix);

            try (LabelInterner interner = LabelInterner.open(file)) {
                assertThat(interner.size()).isZero();
                assertThat(interner.intern("Ada")).isZero();
            }
            try (LabelInterner reopened = LabelInterner.open(file)) {
                assertThat(reopened.labels()).containsExactly("Ada");
            }
        }

        @ParameterizedTest(name = "{0} bytes that are not its header are refused and left alone")
        @ValueSource(ints = {1, 9})
        void shortJunkIsRefused(int length, @TempDir Path dir) throws IOException {
            Path file = dir.resolve(GraphStagingStore.ENTITY_LABELS);
            byte[] junk = Arrays.copyOf(JUNK, length);
            Files.write(file, junk);

            assertThatThrownBy(() -> LabelInterner.open(file)).isInstanceOf(IOException.class)
                .hasMessageContaining("too short to be a torn one");
            assertThatThrownBy(() -> LabelInterner.inspect(file)).isInstanceOf(IOException.class)
                .hasMessageContaining("too short to be a torn one");
            assertThat(Files.readAllBytes(file)).isEqualTo(junk);
        }

        @ParameterizedTest(name = "a {0}-byte header naming another version is refused")
        @ValueSource(ints = {12, 15})
        void anotherVersionIsNotATornHeader(int prefix, @TempDir Path dir) throws IOException {
            Path file = dir.resolve(GraphStagingStore.ENTITY_LABELS);
            byte[] other = Arrays.copyOf(header(LabelInterner.MAGIC, LabelInterner.VERSION + 1),
                prefix);
            Files.write(file, other);

            assertThatThrownBy(() -> LabelInterner.open(file)).isInstanceOf(IOException.class);
            assertThat(Files.readAllBytes(file)).isEqualTo(other);
        }
    }

    @Nested
    @DisplayName("an entity text log")
    class EntityText {

        @ParameterizedTest(name = "a {0}-byte prefix of its header opens fresh and survives a restart")
        @ValueSource(ints = {1, 8, 15})
        void aTornHeaderStartsFresh(int prefix, @TempDir Path dir) throws IOException {
            Path file = dir.resolve(GraphStagingStore.ENTITY_TEXT_LOG);
            Files.write(file, Arrays.copyOf(header(EntityTextLog.MAGIC, EntityTextLog.VERSION),
                prefix));

            LogInspection inspected = EntityTextLog.inspect(file);
            assertThat(inspected.records()).isZero();
            assertThat(inspected.tornBytes()).isEqualTo(prefix);

            try (EntityTextLog log = EntityTextLog.open(file)) {
                log.append(0, "Ada wrote the first program", DOCUMENT);
            }
            try (EntityTextLog reopened = EntityTextLog.open(file)) {
                assertThat(reopened.forEach((entity, snippet, provenance) -> { })).isEqualTo(1);
            }
        }

        @ParameterizedTest(name = "{0} bytes that are not its header are refused and left alone")
        @ValueSource(ints = {1, 9})
        void shortJunkIsRefused(int length, @TempDir Path dir) throws IOException {
            Path file = dir.resolve(GraphStagingStore.ENTITY_TEXT_LOG);
            byte[] junk = Arrays.copyOf(JUNK, length);
            Files.write(file, junk);

            assertThatThrownBy(() -> EntityTextLog.open(file)).isInstanceOf(IOException.class)
                .hasMessageContaining("too short to be a torn one");
            assertThatThrownBy(() -> EntityTextLog.inspect(file)).isInstanceOf(IOException.class)
                .hasMessageContaining("too short to be a torn one");
            assertThat(Files.readAllBytes(file)).isEqualTo(junk);
        }
    }

    @Nested
    @DisplayName("a triple log")
    class Triples {

        @ParameterizedTest(name = "a {0}-byte prefix of its header opens fresh and survives a restart")
        @ValueSource(ints = {1, 8, 15})
        void aTornHeaderStartsFresh(int prefix, @TempDir Path dir) throws IOException {
            Path file = dir.resolve(GraphStagingStore.TRIPLE_LOG);
            Files.write(file, Arrays.copyOf(header(GraphStagingStore.MAGIC,
                GraphStagingStore.VERSION), prefix));

            LogInspection inspected = GraphStagingStore.inspectTripleLog(file);
            assertThat(inspected.records()).isZero();
            assertThat(inspected.tornBytes()).isEqualTo(prefix);

            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                staging.append(ExtractedTriple.alwaysValid("Ada", "knows", "Grace", DOCUMENT));
            }
            try (GraphStagingStore reopened = GraphStagingStore.open(dir)) {
                assertThat(reopened.forEach((s, r, o, from, to, p) -> { })).isEqualTo(1);
            }
        }

        @ParameterizedTest(name = "{0} bytes that are not its header are refused and left alone")
        @ValueSource(ints = {1, 9})
        void shortJunkIsRefused(int length, @TempDir Path dir) throws IOException {
            Path file = dir.resolve(GraphStagingStore.TRIPLE_LOG);
            byte[] junk = Arrays.copyOf(JUNK, length);
            Files.write(file, junk);

            assertThatThrownBy(() -> GraphStagingStore.open(dir)).isInstanceOf(IOException.class)
                .hasMessageContaining("too short to be a torn one");
            assertThatThrownBy(() -> GraphStagingStore.inspectTripleLog(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("too short to be a torn one");
            assertThat(Files.readAllBytes(file)).isEqualTo(junk);
        }
    }

    @Nested
    @DisplayName("an erasure log")
    class Erasures {

        @ParameterizedTest(name = "a {0}-byte header naming another version is refused, as the "
            + "other logs refuse it")
        @ValueSource(ints = {12, 15})
        void anotherVersionIsNotATornHeader(int prefix, @TempDir Path dir) throws IOException {
            Path file = dir.resolve("erasure.log");
            byte[] other = Arrays.copyOf(header(ErasureLog.MAGIC, ErasureLog.VERSION + 1), prefix);
            Files.write(file, other);

            assertThatThrownBy(() -> ErasureLog.open(file)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> ErasureLog.inspectLog(file)).isInstanceOf(IOException.class);
            assertThat(Files.readAllBytes(file)).isEqualTo(other);
        }
    }
}
