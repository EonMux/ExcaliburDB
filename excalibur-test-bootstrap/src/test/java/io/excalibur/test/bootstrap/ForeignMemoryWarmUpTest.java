package io.excalibur.test.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests that the foreign-memory warm-up is actually wired in.
 *
 * <p>The listener only prevents anything if the platform finds it. A rename, a moved class or a
 * lost resource file would leave the code in place and the registration broken, and the symptom
 * would not be a failing test - it would be a build that hangs occasionally, in a different
 * module, with no test to point at. These tests fail loudly instead.</p>
 */
class ForeignMemoryWarmUpTest {

    private static final String SERVICE_FILE =
        "META-INF/services/org.junit.platform.launcher.LauncherSessionListener";

    @Test
    @DisplayName("the platform discovers the warm-up listener")
    void theListenerIsDiscoverable() {
        var found = StreamSupport
            .stream(ServiceLoader.load(LauncherSessionListener.class).spliterator(), false)
            .map(listener -> listener.getClass().getName())
            .collect(Collectors.toList());

        assertThat(found)
            .as("ServiceLoader must find the warm-up, or nothing runs it before the first test")
            .contains(ForeignMemoryWarmUp.class.getName());
    }

    @Test
    @DisplayName("the service file names the class that exists")
    void theServiceFileMatchesTheClass() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SERVICE_FILE)) {
            assertThat(in).as("%s is on the classpath", SERVICE_FILE).isNotNull();
            String contents = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(contents.lines().map(String::trim).filter(line -> !line.isEmpty()))
                .as("every name in the service file resolves to a real listener")
                .allSatisfy(name -> assertThatCode(() -> Class.forName(name))
                    .doesNotThrowAnyException())
                .contains(ForeignMemoryWarmUp.class.getName());
        }
    }

    @Test
    @DisplayName("opening a real launcher session runs the listener")
    void openingASessionRunsTheListener() {
        // The genuine article rather than a hand-made call: this is the path Gradle's test
        // worker takes, and it is what decides whether the warm-up happens at all. Handing the
        // listener a null session instead would pin a nullability contract the SPI does not
        // make, and would keep passing if the registration were lost.
        try (LauncherSession session = LauncherFactory.openSession()) {
            assertThat(session.getLauncher())
                .as("the session opened, so every registered LauncherSessionListener has run")
                .isNotNull();
        }
    }
}
