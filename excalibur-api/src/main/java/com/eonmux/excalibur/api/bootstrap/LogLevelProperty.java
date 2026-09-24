package com.eonmux.excalibur.api.bootstrap;

import ch.qos.logback.core.PropertyDefinerBase;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The root log level, as {@code logback.xml} takes it from {@code EXCALIBUR_LOG_LEVEL}.
 *
 * <p>A property definer rather than {@code ${EXCALIBUR_LOG_LEVEL:-INFO}}, because logback applies
 * that default only to a variable that is not defined, and reads any level it cannot parse as
 * {@code DEBUG}. So a variable set but left blank - which every other {@code EXCALIBUR_} variable
 * treats as unset - and a misspelt level both put a node on debug logging, the most voluminous
 * setting there is, with nothing saying why. Here both resolve to {@code INFO}, and a warning goes
 * to logback's status manager, which prints it at startup because it is a warning.</p>
 *
 * <p>Resolved once, when logback reads its configuration: every entry point that logs through
 * the shipped {@code logback.xml} - the server and the MCP adapter alike - gets the same answer,
 * whichever class happened to touch a logger first.</p>
 */
public final class LogLevelProperty extends PropertyDefinerBase {

    /** The environment variable the level is read from. */
    static final String VARIABLE = "EXCALIBUR_LOG_LEVEL";

    /** The level applied when the variable is unset, blank or not a level. */
    static final String DEFAULT_LEVEL = "INFO";

    /** The levels logback accepts, in upper case. */
    private static final Set<String> LEVELS =
        Set.of("ALL", "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");

    private final UnaryOperator<String> environment;

    /**
     * The definer {@code logback.xml} instantiates, over the process environment.
     */
    public LogLevelProperty() {
        this(System::getenv);
    }

    /**
     * A definer over a given environment.
     *
     * @param environment Looks a variable up by name, returning null when it is not set
     */
    LogLevelProperty(UnaryOperator<String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public String getPropertyValue() {
        return resolve(environment.apply(VARIABLE), this::addWarn);
    }

    /**
     * Resolves a value of {@code EXCALIBUR_LOG_LEVEL} to the level logback is given.
     *
     * @param raw  The variable's value, or null when it is not set
     * @param warn Told why the value was not used, when it was set and could not be
     * @return A level logback parses exactly: the named one in upper case, else {@code INFO}
     */
    static String resolve(String raw, Consumer<String> warn) {
        if (raw == null) {
            return DEFAULT_LEVEL;
        }
        String level = raw.strip().toUpperCase(Locale.ROOT);
        if (level.isEmpty()) {
            warn.accept(VARIABLE + " is set but empty; the root log level is " + DEFAULT_LEVEL);
            return DEFAULT_LEVEL;
        }
        if (!LEVELS.contains(level)) {
            warn.accept(VARIABLE + "=\"" + raw + "\" is not a log level (one of TRACE, DEBUG, "
                + "INFO, WARN, ERROR, OFF, ALL); the root log level is " + DEFAULT_LEVEL);
            return DEFAULT_LEVEL;
        }
        return level;
    }
}
