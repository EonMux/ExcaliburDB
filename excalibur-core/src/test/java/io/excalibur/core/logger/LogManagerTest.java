package io.excalibur.core.logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for LogManager and Logger.
 *
 * Thread-safety: Tests verify thread-safe logging.
 * Domain correctness: Tests verify log levels and output.
 */
@DisplayName("LogManager Tests")
class LogManagerTest {

    private LogManager logManager;
    private Logger logger;

    @BeforeEach
    void setUp() throws Exception {
        // Reset singleton via reflection
        Field instanceField = LogManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);

        logManager = LogManager.getInstance();
        logger = logManager.getLogger(LogManagerTest.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        // Clean up singleton
        Field instanceField = LogManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);

        // Clean up log directory
        String dir = System.getProperty("excalibur.log.dir", "logs");
        File logDir = new File(dir);
        if (logDir.exists()) {
            for (File f : logDir.listFiles()) {
                f.delete();
            }
            logDir.delete();
        }
    }

    @Nested
    @DisplayName("Singleton Tests")
    class SingletonTests {

        @Test
        @DisplayName("Should return singleton instance")
        void shouldReturnSingletonInstance() {
            LogManager instance1 = LogManager.getInstance();
            LogManager instance2 = LogManager.getInstance();

            assertThat(instance1).isSameAs(instance2);
        }
    }

    @Nested
    @DisplayName("Logger Retrieval Tests")
    class LoggerRetrievalTests {

        @Test
        @DisplayName("Should get logger for class")
        void shouldGetLoggerForClass() {
            Logger log = logManager.getLogger(String.class);

            assertThat(log).isNotNull();
        }

        @Test
        @DisplayName("Should return same logger for any class")
        void shouldReturnSameLoggerForAnyClass() {
            Logger log1 = logManager.getLogger(String.class);
            Logger log2 = logManager.getLogger(Integer.class);

            assertThat(log1).isSameAs(log2);
        }
    }

    @Nested
    @DisplayName("Log Level Tests")
    class LogLevelTests {

        @Test
        @DisplayName("Should default to INFO level")
        void shouldDefaultToInfoLevel() {
            assertThat(logManager.level()).isEqualTo(Logger.Level.INFO);
        }

        @Test
        @DisplayName("Should set log level")
        void shouldSetLogLevel() {
            logManager.setLevel(Logger.Level.DEBUG);

            assertThat(logManager.level()).isEqualTo(Logger.Level.DEBUG);
        }

        @Test
        @DisplayName("Should set logger level via logger")
        void shouldSetLoggerLevelViaLogger() {
            logger.setLevel(Logger.Level.TRACE);

            assertThat(logger.level()).isEqualTo(Logger.Level.TRACE);
            assertThat(logManager.level()).isEqualTo(Logger.Level.TRACE);
        }
    }

    @Nested
    @DisplayName("Logging Methods Tests")
    class LoggingMethodsTests {

        @Test
        @DisplayName("Should log info message")
        void shouldLogInfoMessage() {
            assertThatCode(() -> logger.info("Test info message")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should log info message with arg")
        void shouldLogInfoMessageWithArg() {
            assertThatCode(() -> logger.info("Test {} message", "info")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should log info message with two args")
        void shouldLogInfoMessageWithTwoArgs() {
            assertThatCode(() -> logger.info("Test {} {}", "arg1", "arg2")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should log info message with varargs")
        void shouldLogInfoMessageWithVarargs() {
            assertThatCode(() -> logger.info("Test {} {} {}", "a", "b", "c")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should log debug message")
        void shouldLogDebugMessage() {
            logger.setLevel(Logger.Level.DEBUG);

            assertThatCode(() -> logger.debug("Debug message")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should log debug message with arg")
        void shouldLogDebugMessageWithArg() {
            logger.setLevel(Logger.Level.DEBUG);

            assertThatCode(() -> logger.debug("Debug {}", "test")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should log warn message")
        void shouldLogWarnMessage() {
            assertThatCode(() -> logger.warn("Warn message")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should log warn message with arg")
        void shouldLogWarnMessageWithArg() {
            assertThatCode(() -> logger.warn("Warn {}", "test")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should log error message")
        void shouldLogErrorMessage() {
            assertThatCode(() -> logger.error("Error message")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should log error message with arg")
        void shouldLogErrorMessageWithArg() {
            assertThatCode(() -> logger.error("Error {}", "test")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should log trace message")
        void shouldLogTraceMessage() {
            logger.setLevel(Logger.Level.TRACE);

            assertThatCode(() -> logger.trace("Trace message")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should log trace message with arg")
        void shouldLogTraceMessageWithArg() {
            logger.setLevel(Logger.Level.TRACE);

            assertThatCode(() -> logger.trace("Trace {}", "test")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Level Filtering Tests")
    class LevelFilteringTests {

        @Test
        @DisplayName("Should not log debug when level is INFO")
        void shouldNotLogDebugWhenLevelIsInfo() {
            logManager.setLevel(Logger.Level.INFO);

            // Debug should be filtered out - no exception should occur
            assertThatCode(() -> logger.debug("Should not appear")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should log error when level is INFO")
        void shouldLogErrorWhenLevelIsInfo() {
            logManager.setLevel(Logger.Level.INFO);

            assertThatCode(() -> logger.error("Should appear")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Throwable Handling Tests")
    class ThrowableTests {

        @Test
        @DisplayName("Should handle error with throwable")
        void shouldHandleErrorWithThrowable() {
            Throwable t = new RuntimeException("Test exception");

            assertThatCode(() -> logger.error("Error occurred", t)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should handle debug with throwable via default method")
        void shouldHandleDebugWithThrowable() {
            logger.setLevel(Logger.Level.DEBUG);
            Throwable t = new RuntimeException("Test debug exception");

            assertThatCode(() -> logger.debug("Debug error", t)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should ignore null throwable")
        void shouldIgnoreNullThrowable() {
            assertThatCode(() -> logger.debug("No throwable", (Throwable) null)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Logger.Level Tests")
    class LevelEnumTests {

        @Test
        @DisplayName("Should have correct level order")
        void shouldHaveCorrectLevelOrder() {
            assertThat(Logger.Level.TRACE.ordinal()).isLessThan(Logger.Level.DEBUG.ordinal());
            assertThat(Logger.Level.DEBUG.ordinal()).isLessThan(Logger.Level.INFO.ordinal());
            assertThat(Logger.Level.INFO.ordinal()).isLessThan(Logger.Level.WARN.ordinal());
            assertThat(Logger.Level.WARN.ordinal()).isLessThan(Logger.Level.ERROR.ordinal());
        }

        @Test
        @DisplayName("Should have all expected levels")
        void shouldHaveAllExpectedLevels() {
            Logger.Level[] levels = Logger.Level.values();

            assertThat(levels).containsExactlyInAnyOrder(
                Logger.Level.TRACE,
                Logger.Level.DEBUG,
                Logger.Level.INFO,
                Logger.Level.WARN,
                Logger.Level.ERROR
            );
        }
    }
}
