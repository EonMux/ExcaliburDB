package io.excalibur.core.logger;

/**
 * Simple logger interface for application logging.
 */
public interface Logger {
    
    void info(String message);
    void info(String format, Object arg1);
    void info(String format, Object arg1, Object arg2);
    void info(String format, Object... args);
    
    void debug(String message);
    void debug(String format, Object arg1);
    void debug(String format, Object arg1, Object arg2);
    void debug(String format, Object... args);
    
    void warn(String message);
    void warn(String format, Object arg1);
    void warn(String format, Object arg1, Object arg2);
    void warn(String format, Object... args);
    
    void error(String message);
    void error(String format, Object arg1);
    void error(String format, Object arg1, Object arg2);
    void error(String format, Object... args);
    
    void trace(String message);
    void trace(String format, Object arg1);
    void trace(String format, Object arg1, Object arg2);
    void trace(String format, Object... args);
    
    default void debug(String message, Throwable t) {
        if (t != null) {
            error(message, t);
        }
    }
    
    default void error(String message, Throwable t) {
        error(message + "\n" + t.getMessage());
    }
    
    void setLevel(Level level);
    Level level();
    
    enum Level {
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }
}
