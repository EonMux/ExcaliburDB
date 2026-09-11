package excalibur.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.util.logging.Handler;
import java.util.logging.LogManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the storage engine's log lines go.
 *
 * <p>That module logs through {@code java.util.logging}, and the records it writes are the ones
 * an operator most needs: a tombstone the routing directory still writes to, a segment that would
 * not purge, a checksum that did not match. Without the bridge they are written by the JDK's own
 * handler in the JDK's format, so a pipeline parsing this process's JSON output drops them, and
 * anything below INFO is discarded before the configured level is consulted at all.</p>
 */
@DisplayName("java.util.logging bridge")
class JulBridgeTest {

    @Test
    @DisplayName("the JDK logger is routed through SLF4J, and only once")
    void julRecordsReachTheConfiguredAppender() {
        ExcaliburApplication.routeStorageEngineLogsToTheConfiguredAppender();

        assertThat(SLF4JBridgeHandler.isInstalled())
            .as("the storage engine's records reach the same appender as everything else")
            .isTrue();

        Handler[] handlers = LogManager.getLogManager().getLogger("").getHandlers();
        assertThat(handlers)
            .as("the JDK's own handler is removed, or every record is written twice")
            .hasSize(1);
        assertThat(handlers[0]).isInstanceOf(SLF4JBridgeHandler.class);
    }
}
