package excalibur.api.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which port the node listens on.
 *
 * <p>The property has to actually be read - a key that is documented and ignored sends an operator
 * looking for the node on a port it was never going to use - and a value that is not a port has to
 * stop startup, because a node that falls back to the default is running somewhere nothing is
 * looking for it while reporting itself healthy.</p>
 */
@DisplayName("Listen port")
class ListenPortTest {

    private static final Map<String, String> NO_ENV = Map.of();
    private static final Map<String, String> NO_PROPS = Map.of();

    @Test
    @DisplayName("nothing configured listens on the default")
    void theDefaultIsUsedWhenNothingIsSet() {
        assertThat(ListenPort.resolve(NO_PROPS, NO_ENV))
            .isEqualTo(ExcaliburDBServer.DEFAULT_PORT);
    }

    @Test
    @DisplayName("the properties file is read")
    void thePropertyIsHonoured() {
        assertThat(ListenPort.resolve(Map.of(ListenPort.KEY_PORT, "9090"), NO_ENV))
            .isEqualTo(9090);
    }

    @Test
    @DisplayName("the environment is read")
    void theEnvironmentIsHonoured() {
        assertThat(ListenPort.resolve(NO_PROPS, Map.of(ListenPort.ENV_PORT, "9091")))
            .isEqualTo(9091);
    }

    @Test
    @DisplayName("the environment beats the file, as it does for every other setting")
    void theEnvironmentWins() {
        assertThat(ListenPort.resolve(
            Map.of(ListenPort.KEY_PORT, "9090"),
            Map.of(ListenPort.ENV_PORT, "9091")))
            .isEqualTo(9091);
    }

    @Test
    @DisplayName("a blank value is not a configured value")
    void blankFallsThrough() {
        assertThat(ListenPort.resolve(
            Map.of(ListenPort.KEY_PORT, "9090"),
            Map.of(ListenPort.ENV_PORT, "   ")))
            .isEqualTo(9090);
        assertThat(ListenPort.resolve(Map.of(ListenPort.KEY_PORT, ""), NO_ENV))
            .isEqualTo(ExcaliburDBServer.DEFAULT_PORT);
    }

    @Test
    @DisplayName("surrounding whitespace is tolerated")
    void whitespaceIsTrimmed() {
        assertThat(ListenPort.resolve(Map.of(ListenPort.KEY_PORT, " 9090 "), NO_ENV))
            .isEqualTo(9090);
    }

    @Test
    @DisplayName("zero is allowed, because it means any free port")
    void zeroIsAllowed() {
        assertThat(ListenPort.resolve(Map.of(ListenPort.KEY_PORT, "0"), NO_ENV)).isZero();
    }

    @Test
    @DisplayName("a value that is not a number stops startup rather than defaulting")
    void aNonNumberIsFatal() {
        assertThatThrownBy(() -> ListenPort.resolve(NO_PROPS, Map.of(ListenPort.ENV_PORT, "http")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(ListenPort.ENV_PORT)
            .hasMessageContaining("http");

        assertThatThrownBy(() -> ListenPort.resolve(Map.of(ListenPort.KEY_PORT, "80x"), NO_ENV))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(ListenPort.KEY_PORT);
    }

    @Test
    @DisplayName("a value outside the port range stops startup")
    void anOutOfRangePortIsFatal() {
        assertThatThrownBy(() -> ListenPort.resolve(Map.of(ListenPort.KEY_PORT, "65536"), NO_ENV))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("65536");

        assertThatThrownBy(() -> ListenPort.resolve(Map.of(ListenPort.KEY_PORT, "-1"), NO_ENV))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("-1");
    }

    @Test
    @DisplayName("the boundaries of the range are usable")
    void theBoundsAreAccepted() {
        assertThat(ListenPort.resolve(Map.of(ListenPort.KEY_PORT, "65535"), NO_ENV))
            .isEqualTo(ListenPort.MAX_PORT);
        assertThat(ListenPort.resolve(Map.of(ListenPort.KEY_PORT, "1"), NO_ENV)).isEqualTo(1);
    }
}
