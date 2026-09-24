package com.eonmux.excalibur.api.bootstrap;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The root log level {@code logback.xml} takes from {@code EXCALIBUR_LOG_LEVEL}.
 *
 * <p>Logback's own {@code ${VAR:-INFO}} applies the default only to an undefined variable, and
 * reads any level it cannot parse as {@code DEBUG}: a blank variable or a typo turned a
 * production node's log into a firehose. Both must land on {@code INFO}.</p>
 */
@DisplayName("Root log level from EXCALIBUR_LOG_LEVEL")
class LogLevelPropertyTest {

    @Test
    @DisplayName("is INFO when the variable is not set")
    void unsetIsInfo() {
        List<String> warnings = new ArrayList<>();
        assertThat(LogLevelProperty.resolve(null, warnings::add)).isEqualTo("INFO");
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "\t" })
    @DisplayName("is INFO when the variable is blank, as for any blank EXCALIBUR_ variable")
    void blankIsInfo(String blank) {
        List<String> warnings = new ArrayList<>();
        assertThat(LogLevelProperty.resolve(blank, warnings::add)).isEqualTo("INFO");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("EXCALIBUR_LOG_LEVEL").contains("INFO");
    }

    @ParameterizedTest
    @CsvSource({ "debug,DEBUG", "' Warn ',WARN", "TRACE,TRACE", "error,ERROR", "off,OFF",
        "info,INFO", "all,ALL" })
    @DisplayName("names a level in any case, trimmed")
    void levelsInAnyCase(String raw, String level) {
        List<String> warnings = new ArrayList<>();
        assertThat(LogLevelProperty.resolve(raw, warnings::add)).isEqualTo(level);
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "verbose", "DEBUGG", "5", "warning" })
    @DisplayName("is INFO, with a warning naming the value, when it is not a level")
    void notALevelIsInfo(String raw) {
        List<String> warnings = new ArrayList<>();
        assertThat(LogLevelProperty.resolve(raw, warnings::add)).isEqualTo("INFO");
        assertThat(warnings).singleElement().asString()
            .contains("EXCALIBUR_LOG_LEVEL").contains(raw).contains("INFO");
    }

    @Test
    @DisplayName("reports its warning through logback's status manager")
    void warnsThroughTheContext() {
        LoggerContext context = new LoggerContext();
        LogLevelProperty property = new LogLevelProperty(name -> "verbose");
        property.setContext(context);

        assertThat(property.getPropertyValue()).isEqualTo("INFO");
        assertThat(context.getStatusManager().getCopyOfStatusList())
            .anySatisfy(status -> {
                assertThat(status.getLevel()).isEqualTo(Status.WARN);
                assertThat(status.getMessage()).contains("verbose");
            });
    }
}
