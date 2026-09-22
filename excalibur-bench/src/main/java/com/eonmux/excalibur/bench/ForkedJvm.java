package com.eonmux.excalibur.bench;

import java.util.List;
import java.util.stream.Stream;

/**
 * The JVM flags every forked benchmark JVM needs, in one place.
 *
 * <p>A runner that sets {@code jvmArgsAppend} on its JMH options replaces the flags a
 * benchmark's {@code @Fork} annotation declares rather than adding to them, so whatever a
 * runner passes is the whole of what the fork starts with. A runner that forgets one of these
 * produces a fork that cannot run some of what it includes: without {@code --enable-preview}
 * no class in this build loads, and without {@code --add-modules=jdk.incubator.vector} every
 * benchmark that touches the Vector API dies with {@code NoClassDefFoundError} - which the
 * runners that select by pattern reach whenever the pattern is broad enough.</p>
 *
 * <p>The Gradle tasks that launch these runners carry the same list, as
 * {@code benchmarkJvmFlags} in {@code excalibur-bench/build.gradle.kts}.</p>
 *
 * @author ExcaliburDB
 */
public final class ForkedJvm {

    /** Flags without which a fork cannot load or run every benchmark in this module. */
    public static final List<String> REQUIRED_FLAGS = List.of(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector"
    );

    private ForkedJvm() {
    }

    /**
     * Returns the required flags followed by a runner's own, such as its heap size.
     *
     * @param extra Flags particular to one runner
     * @return The arguments to hand to {@code jvmArgsAppend}
     */
    public static String[] flags(String... extra) {
        return Stream.concat(REQUIRED_FLAGS.stream(), Stream.of(extra)).toArray(String[]::new);
    }
}
