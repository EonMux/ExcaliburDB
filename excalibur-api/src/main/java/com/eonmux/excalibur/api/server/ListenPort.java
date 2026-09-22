package com.eonmux.excalibur.api.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * Which port the node listens on, and where that answer comes from.
 *
 * <h2>Precedence</h2>
 * <ol>
 *   <li>{@code EXCALIBUR_PORT} in the environment</li>
 *   <li>{@code excalibur.api.port} in the properties file</li>
 *   <li>{@link ExcaliburDBServer#DEFAULT_PORT}</li>
 * </ol>
 *
 * <p>Environment over file is the rule every other setting follows, and {@code EXCALIBUR_PORT} is
 * the name deployments already use. The generic transform would spell the property's own override
 * {@code EXCALIBUR_API_PORT}; that one keeps working, because it is applied to the properties
 * before they reach here, and the shorter name is checked first so an existing deployment that
 * sets it keeps winning over a file.</p>
 *
 * <h2>Why a bad value is fatal</h2>
 * <p>Falling back to the default when the configured port will not parse produces a node that
 * starts, reports itself healthy, and is not where anything is looking for it. That is a harder
 * failure to diagnose than not starting at all, and the rest of the configuration already refuses
 * values it cannot use.</p>
 *
 * @author ExcaliburDB
 */
public final class ListenPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListenPort.class);

    /** Property naming the port to listen on. */
    public static final String KEY_PORT = "excalibur.api.port";

    /** Environment variable naming the port to listen on. */
    public static final String ENV_PORT = "EXCALIBUR_PORT";

    /** Lowest port a socket can be asked for; 0 means "any free port". */
    public static final int MIN_PORT = 0;

    /** Highest port a socket can be asked for. */
    public static final int MAX_PORT = 65535;

    private ListenPort() {
    }

    /**
     * Resolves the port to listen on.
     *
     * @param properties  The effective properties, file merged with {@code EXCALIBUR_*}
     * @param environment The environment, for {@link #ENV_PORT}
     * @return The port, in {@code [0, 65535]}
     * @throws IllegalArgumentException if a configured value is not a port number
     */
    public static int resolve(Map<String, String> properties, Map<String, String> environment) {
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(environment, "environment must not be null");

        Integer fromEnv = parse(environment.get(ENV_PORT), ENV_PORT);
        if (fromEnv != null) {
            LOGGER.info("listening on port {} from {}", fromEnv, ENV_PORT);
            return fromEnv;
        }

        Integer fromFile = parse(properties.get(KEY_PORT), KEY_PORT);
        if (fromFile != null) {
            LOGGER.info("listening on port {} from {}", fromFile, KEY_PORT);
            return fromFile;
        }

        LOGGER.info("listening on the default port {}", ExcaliburDBServer.DEFAULT_PORT);
        return ExcaliburDBServer.DEFAULT_PORT;
    }

    /**
     * Parses one configured value, or reports that it was not set.
     *
     * @param value  The raw value, possibly null or blank
     * @param source What to name in the failure, so the message points at the thing to edit
     * @return The port, or null when nothing was configured here
     */
    private static Integer parse(String value, String source) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                source + " is not a number: '" + value + "'");
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(
                source + " must be in range " + MIN_PORT + "-" + MAX_PORT + ": " + port);
        }
        return port;
    }
}
