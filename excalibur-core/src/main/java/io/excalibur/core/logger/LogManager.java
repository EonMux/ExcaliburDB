package io.excalibur.core.logger;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Simple LogManager for file-based logging.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe for concurrent logging operations.</p>
 *
 * @author ExcaliburDB
 */
public class LogManager {

    private static volatile LogManager instance;
    private final Logger logger;
    private volatile Logger.Level level;
    private final File logFile;

    private LogManager() {
        this.level = Logger.Level.INFO;
        this.logFile = createLogFile();
        this.logger = new LoggerImpl();
    }

    private File createLogFile() {
        String dir = System.getProperty("excalibur.log.dir", "logs");
        File logDir = new File(dir);
        try {
            Files.createDirectories(logDir.toPath());
        } catch (IOException e) {
            // Ignore - logging will still work to console
        }
        return new File(logDir, "excalibur.log");
    }

    /**
     * Gets the singleton LogManager instance.
     *
     * @return The LogManager instance
     */
    public static LogManager getInstance() {
        if (instance == null) {
            synchronized (LogManager.class) {
                if (instance == null) {
                    instance = new LogManager();
                }
            }
        }
        return instance;
    }

    /**
     * Gets a Logger for the specified class.
     *
     * @param clazz Class to get logger for
     * @return Logger instance
     */
    public Logger getLogger(Class<?> clazz) {
        return logger;
    }

    /**
     * Gets the current log level.
     *
     * @return Current level
     */
    public Logger.Level level() {
        return level;
    }

    /**
     * Sets the log level.
     *
     * @param level New level
     */
    public void setLevel(Logger.Level level) {
        this.level = level;
    }

    /**
     * Logger implementation.
     */
    private class LoggerImpl implements Logger {

        @Override
        public void info(String message) {
            log(Level.INFO, message);
        }

        @Override
        public void info(String format, Object arg1) {
            log(Level.INFO, format, arg1);
        }

        @Override
        public void info(String format, Object arg1, Object arg2) {
            log(Level.INFO, format, arg1, arg2);
        }

        @Override
        public void info(String format, Object... args) {
            log(Level.INFO, format, args);
        }

        @Override
        public void debug(String message) {
            log(Level.DEBUG, message);
        }

        @Override
        public void debug(String format, Object arg1) {
            log(Level.DEBUG, format, arg1);
        }

        @Override
        public void debug(String format, Object arg1, Object arg2) {
            log(Level.DEBUG, format, arg1, arg2);
        }

        @Override
        public void debug(String format, Object... args) {
            log(Level.DEBUG, format, args);
        }

        @Override
        public void warn(String message) {
            log(Level.WARN, message);
        }

        @Override
        public void warn(String format, Object arg1) {
            log(Level.WARN, format, arg1);
        }

        @Override
        public void warn(String format, Object arg1, Object arg2) {
            log(Level.WARN, format, arg1, arg2);
        }

        @Override
        public void warn(String format, Object... args) {
            log(Level.WARN, format, args);
        }

        @Override
        public void error(String message) {
            log(Level.ERROR, message);
        }

        @Override
        public void error(String format, Object arg1) {
            log(Level.ERROR, format, arg1);
        }

        @Override
        public void error(String format, Object arg1, Object arg2) {
            log(Level.ERROR, format, arg1, arg2);
        }

        @Override
        public void error(String format, Object... args) {
            log(Level.ERROR, format, args);
        }

        @Override
        public void trace(String message) {
            log(Level.TRACE, message);
        }

        @Override
        public void trace(String format, Object arg1) {
            log(Level.TRACE, format, arg1);
        }

        @Override
        public void trace(String format, Object arg1, Object arg2) {
            log(Level.TRACE, format, arg1, arg2);
        }

        @Override
        public void trace(String format, Object... args) {
            log(Level.TRACE, format, args);
        }

        @Override
        public void setLevel(Level level) {
            LogManager.this.setLevel(level);
        }

        @Override
        public Level level() {
            return LogManager.this.level();
        }

        private void log(Level level, String message) {
            if (shouldLog(level)) {
                appendLog(level, message);
            }
        }

        private void log(Level level, String format, Object arg1) {
            if (shouldLog(level)) {
                appendLog(level, String.format(format.replace("{}", "%s"), arg1));
            }
        }

        private void log(Level level, String format, Object arg1, Object arg2) {
            if (shouldLog(level)) {
                appendLog(level, String.format(format.replace("{}", "%s").replace("{}", "%s"), arg1, arg2));
            }
        }

        private void log(Level level, String format, Object... args) {
            if (shouldLog(level)) {
                appendLog(level, String.format(format.replace("{}", "%s"), args));
            }
        }

        private boolean shouldLog(Level msgLevel) {
            return msgLevel.ordinal() >= LogManager.this.level.ordinal();
        }

        private void appendLog(Level level, String message) {
            String logLine = "[" + level + "] " + message + "\n";
            synchronized (LogManager.this) {
                try {
                    if (!logFile.exists()) {
                        logFile.getParentFile().mkdirs();
                        logFile.createNewFile();
                    }
                    try (BufferedOutputStream bos = new BufferedOutputStream(
                            new FileOutputStream(logFile, true))) {
                        bos.write(logLine.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException e) {
                    // Fallback to stderr
                    System.err.print(logLine);
                }
            }
        }
    }
}
