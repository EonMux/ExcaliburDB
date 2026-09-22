package com.eonmux.excalibur.bench;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flags handed to every forked benchmark JVM.
 *
 * <p>JMH replaces a benchmark's annotated fork flags with a runner's, so a missing entry here
 * is a fork that cannot run what it was asked to - pinned because nothing short of a
 * benchmark run lasting minutes would otherwise notice.</p>
 */
@DisplayName("Forked benchmark JVM flags")
class ForkedJvmTest {

    @Test
    @DisplayName("carry preview, native access and the Vector API module")
    void carryEveryFlagTheBenchmarksNeed() {
        assertThat(ForkedJvm.flags()).containsExactly(
            "--enable-preview",
            "--enable-native-access=ALL-UNNAMED",
            "--add-modules=jdk.incubator.vector");
    }

    @Test
    @DisplayName("put a runner's own flags after the required ones")
    void appendRunnerFlagsAfterTheRequiredOnes() {
        assertThat(ForkedJvm.flags("-Xms2g", "-Xmx2g")).containsExactly(
            "--enable-preview",
            "--enable-native-access=ALL-UNNAMED",
            "--add-modules=jdk.incubator.vector",
            "-Xms2g",
            "-Xmx2g");
    }
}
