package com.eonmux.excalibur.graph.erasure;

import com.eonmux.excalibur.graph.dictionary.LabelDictionaryWriter;
import com.eonmux.excalibur.graph.file.GraphCorruptionException;
import com.eonmux.excalibur.graph.staging.GraphBuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A request naming an id only a published dictionary could vouch for is kept, not discharged,
 * when that dictionary is too damaged to answer.
 */
@DisplayName("Discharging unassigned ids against a damaged dictionary")
class DamagedDictionaryDischargeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
        ZoneOffset.UTC);

    @Test
    @DisplayName("keeps the request outstanding rather than guess, and does not fail the open")
    void keepsTheRequest(@TempDir Path dir) throws IOException {
        Path graph = Files.createDirectory(dir.resolve("graph"));
        new LabelDictionaryWriter().put(7, "Alice").write(graph, GraphBuilder.ENTITY_DICTIONARY);
        Path text = graph.resolve(GraphBuilder.ENTITY_DICTIONARY + ".dat");
        byte[] bytes = Files.readAllBytes(text);
        bytes[0] ^= 0x01;
        Files.write(text, bytes);

        try (ErasureLog log = ErasureLog.open(dir.resolve("erasure.log"))) {
            GraphErasureCoordinator coordinator =
                new GraphErasureCoordinator(log, (stats, now) -> true, CLOCK);
            coordinator.submit(new ErasureScope.Entity(7));

            assertThatThrownBy(() -> coordinator.entityAssigned(7, graph))
                .as("asked directly, the damage is reported")
                .isInstanceOf(GraphCorruptionException.class);
            assertThat(coordinator.dischargeUnassigned(graph)).isEmpty();
            assertThat(coordinator.outstandingCount()).isEqualTo(1);
        }
    }
}
