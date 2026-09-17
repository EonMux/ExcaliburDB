package excalibur.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whether a configuration file that will not load is fatal depends on who chose its name.
 *
 * <p>A node that starts on the packaged defaults is not the node the operator configured: it has
 * no allowed ingest roots, the shipped index tuning, and a loopback bind. Doing that because a
 * path was mistyped, and saying so in one WARN line among the startup banner, is the same class
 * of failure the listening port already refuses - a node that starts, reports itself healthy, and
 * is not what anyone expects.
 *
 * <p>But the default name is a different case. Nobody asked for {@code excalibur.properties}, and
 * a node run without one is a supported way to start, so its absence has to stay non-fatal.
 */
@DisplayName("Configuration source")
class ConfigSourceTest {

    @Nested
    @DisplayName("parsing the command line")
    class Parsing {

        @Test
        @DisplayName("a path given with --config is recorded as chosen by the operator")
        void longFlagIsNamed() {
            ExcaliburApplication.ConfigSource source =
                ExcaliburApplication.configSource(new String[] {"--config", "/etc/excalibur.properties"});

            assertThat(source.path()).isEqualTo("/etc/excalibur.properties");
            assertThat(source.named()).isTrue();
        }

        @Test
        @DisplayName("the short flag is the same thing")
        void shortFlagIsNamed() {
            ExcaliburApplication.ConfigSource source =
                ExcaliburApplication.configSource(new String[] {"-c", "custom.properties"});

            assertThat(source.path()).isEqualTo("custom.properties");
            assertThat(source.named()).isTrue();
        }

        @Test
        @DisplayName("no flag falls back to the default name, which nobody chose")
        void absentFlagIsNotNamed() {
            ExcaliburApplication.ConfigSource source =
                ExcaliburApplication.configSource(new String[0]);

            assertThat(source.path()).isEqualTo(ExcaliburApplication.DEFAULT_CONFIG_PATH);
            assertThat(source.named()).isFalse();
        }

        @Test
        @DisplayName("a trailing --config with nothing after it is refused, not ignored")
        void danglingFlagIsRefused() {
            assertThatThrownBy(() ->
                ExcaliburApplication.configSource(new String[] {"--config"}))
                .as("the operator asked for a configuration file; starting on the packaged "
                    + "defaults instead starts a different node than the one asked for")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--config");
        }

        @Test
        @DisplayName("--config=path is the same as --config path")
        void equalsFormIsAccepted() {
            ExcaliburApplication.ConfigSource source =
                ExcaliburApplication.configSource(new String[] {"--config=/etc/node.properties"});

            assertThat(source.path()).isEqualTo("/etc/node.properties");
            assertThat(source.named()).isTrue();
        }

        @Test
        @DisplayName("an argument the server does not understand is refused, not ignored")
        void unknownArgumentIsRefused() {
            assertThatThrownBy(() ->
                ExcaliburApplication.configSource(new String[] {"--prot", "9000"}))
                .as("a mistyped flag that starts the node anyway is a node running on settings "
                    + "nobody chose")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--prot");
        }

        @Test
        @DisplayName("asking for help is recognised before anything is started")
        void helpIsRecognised() {
            assertThat(ExcaliburApplication.isHelpRequest(new String[] {"--help"})).isTrue();
            assertThat(ExcaliburApplication.isHelpRequest(new String[] {"-h"})).isTrue();
            assertThat(ExcaliburApplication.isHelpRequest(new String[] {"help"})).isTrue();
            assertThat(ExcaliburApplication.isHelpRequest(
                new String[] {"--config", "x.properties", "--help"})).isTrue();
            assertThat(ExcaliburApplication.isHelpRequest(
                new String[] {"--config", "x.properties"})).isFalse();
        }

        @Test
        @DisplayName("the usage text names every argument the parser accepts")
        void usageNamesEveryArgument() {
            assertThat(ExcaliburApplication.usage())
                .contains("--config", "-c", "--help", "-h")
                .contains(ExcaliburApplication.DEFAULT_CONFIG_PATH);
        }
    }

    @Nested
    @DisplayName("loading it")
    class Loading {

        @Test
        @DisplayName("a named file that cannot be read refuses to start")
        void namedAndMissingIsFatal(@TempDir Path dir) {
            String missing = dir.resolve("typo.properties").toString();

            assertThatThrownBy(() -> ExcaliburApplication.loadConfiguration(
                new ExcaliburApplication.ConfigSource(missing, true)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(missing)
                .hasMessageContaining("packaged defaults");
        }

        @Test
        @DisplayName("the default name being absent still starts on defaults")
        void unnamedAndMissingIsNotFatal(@TempDir Path dir) {
            String missing = dir.resolve("excalibur.properties").toString();

            assertThatCode(() -> ExcaliburApplication.loadConfiguration(
                new ExcaliburApplication.ConfigSource(missing, false)))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a named file that is there is read")
        void namedAndPresentIsRead(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("excalibur.properties");
            Files.writeString(file, "excalibur.query.top-k-default=7\n");

            var config = ExcaliburApplication.loadConfiguration(
                new ExcaliburApplication.ConfigSource(file.toString(), true));

            assertThat(config.query().getDefaultTopK()).isEqualTo(7);
        }
    }
}
