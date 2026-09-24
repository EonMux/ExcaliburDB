package com.eonmux.excalibur.core.datadir;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LockHolder")
class LockHolderTest {

    @Test
    @DisplayName("parses every field a holder writes, the last value of a repeated key winning")
    void parsesRecord() {
        LockHolder holder = LockHolder.parse("pid=42\nholder=excalibur node\nsince=2026-09-23T10:00:00Z\n"
            + "url=http://127.0.0.1:1\nurl=https://0.0.0.0:8443\nhealth-url=https://127.0.0.1:8443/health\n");
        assertThat(holder.pid()).isEqualTo(42);
        assertThat(holder.description()).isEqualTo("excalibur node");
        assertThat(holder.since()).contains("2026-09-23T10:00:00Z");
        assertThat(holder.url()).contains("https://0.0.0.0:8443");
        assertThat(holder.healthUrl()).contains("https://127.0.0.1:8443/health");
        assertThat(holder.describe())
            .isEqualTo("pid 42, excalibur node, since 2026-09-23T10:00:00Z");
    }

    @Test
    @DisplayName("a record it cannot read describes an unknown holder rather than failing")
    void toleratesGarbage() {
        LockHolder holder = LockHolder.parse("pid=not-a-number\nnonsense\n=\n");
        assertThat(holder.pid()).isEqualTo(LockHolder.UNKNOWN_PID);
        assertThat(holder.description()).isEqualTo("unknown");
        assertThat(holder.since()).isEmpty();
        assertThat(holder.url()).isEqualTo(Optional.empty());
        assertThat(holder.describe()).isEqualTo("a process that did not record who it is");
    }

    @Test
    @DisplayName("a record with only a pid names the pid")
    void pidOnly() {
        assertThat(LockHolder.parse("pid=7\n").describe()).isEqualTo("pid 7");
    }

    @Test
    @DisplayName("renders back to the same record it parses")
    void roundTrips() {
        LockHolder holder = new LockHolder(9, "excalibur-backup restore", Optional.of("t"),
            Optional.empty(), Optional.empty());
        assertThat(LockHolder.parse(holder.render())).isEqualTo(holder);
    }
}
