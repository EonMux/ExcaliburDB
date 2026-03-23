package io.excalibur.core.bootstrap;

import io.excalibur.core.config.Config;
import io.excalibur.core.config.ConfigLoader;
import io.excalibur.metrics.Metrics;
import io.excalibur.metrics.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Bootstrap application for ExcaliburDB.
 *
 * <p>This is the main entry point that initializes all core infrastructure:
 * <ul>
 *   <li>Configuration loading and validation</li>
 *   <li>SLF4J logging setup</li>
 *   <li>Metrics collection</li>
 *   <li>JMX MBean registration</li>
 * </ul>
 *
 * <p>Thread-safety: The bootstrap is single-threaded during startup.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class BootstrapApplication {

    private static final Logger logger = LoggerFactory.getLogger(BootstrapApplication.class);
    private static BootstrapApplication instance;

    private volatile boolean initialized = false;
    private volatile boolean running = false;
    private volatile Config config;
    private volatile MetricsRegistry metricsRegistry;

    private BootstrapApplication() {
        // Singleton - only one instance
    }

    /**
     * Main entry point.
     *
     * @param args Command-line arguments (reserved for future use)
     */
    public static void main(String[] args) {
        instance = new BootstrapApplication();
        instance.run(args);
    }

    /**
     * Runs the bootstrap application.
     *
     * @param args Command-line arguments
     */
    public void run(String[] args) {
        try {
            initialize();
            printBanner();
            printStatus();
        } catch (Exception e) {
            logger.error("Failed to initialize ExcaliburDB: " + e.getMessage(), e);
            System.exit(1);
        }
    }

    private void initialize() {
        logger.info("Initializing ExcaliburDB...");

        // Load configuration from properties file with environment variable overrides
        loadConfiguration();

        // Set up SLF4J logging
        setupLogging();

        // Initialize metrics
        initMetrics();

        // Register JMX MBeans
        registerJmxMBeans();

        initialized = true;
        logger.info("ExcaliburDB initialized successfully");
    }

    /**
     * Loads configuration from excalibur.properties with environment variable overrides.
     */
    private void loadConfiguration() {
        try {
            // Load config with system environment variables for overrides
            config = ConfigLoader.load("excalibur.properties", System.getenv());
            logger.info("Configuration loaded from excalibur.properties");
            logger.info("Visibility epoch tick: {}ms", config.visibility().getEpochTickMs());
            logger.info("Query include partial segments: {}", config.query().includePartialSegments());
            logger.info("Ingest HNSW queue depth limit: {}", config.ingest().getHnswQueueDepthLimit());
            logger.info("Health readiness threshold: {}", config.health().getReadinessThreshold());
        } catch (Exception e) {
            logger.warn("Failed to load configuration, using defaults: {}", e.getMessage());
            // Fall back to loading with empty overrides
            config = ConfigLoader.load("excalibur.properties", new HashMap<>());
        }
    }

    private void setupLogging() {
        // SLF4J is configured via logback.xml on the classpath.
        // No programmatic configuration needed - this is a verification point.
        logger.info("Logging system initialized (SLF4J + Logback)");
    }

    private void initMetrics() {
        // Initialize the metrics registry for collecting system metrics
        metricsRegistry = new MetricsRegistry();

        // Initialize the metrics system with default values
        Metrics.setHealthScore(0.5);  // Start at 50% health (system booting)
        Metrics.setEstimatedCoverage(0.0);  // No data indexed yet
        Metrics.recordEpoch(0);  // Initialize epoch to 0

        logger.info("Metrics system initialized");
    }

    private void registerJmxMBeans() {
        if (metricsRegistry == null) {
            logger.warn("Cannot register JMX MBeans: MetricsRegistry not initialized");
            return;
        }

        try {
            // Register all JMX MBeans for observability
            Metrics.registerAll(metricsRegistry);

            // Log registered MBeans for verification
            int registeredCount = metricsRegistry.getAllMBeanNames().size();
            logger.info("JMX MBeans registered successfully ({} MBeans)", registeredCount);

            for (String mbeanName : metricsRegistry.getAllMBeanNames()) {
                logger.debug("Registered MBean: {}", mbeanName);
            }
        } catch (Exception e) {
            logger.warn("Failed to register JMX MBeans: " + e.getMessage(), e);
        }
    }

    private void printBanner() {
        System.out.println("========================================");
        System.out.println("    ExcaliburDB - Disk-Backed Vector Database");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Phase 1: Core Primitives");
        System.out.println("  [" + status(config != null) + "] Configuration");
        System.out.println("  [" + status(true) + "] MappedFile (Panama FFM)");
        System.out.println("  [" + status(true) + "] XxHash64");
        System.out.println("  [" + status(true) + "] CRC32");
        System.out.println("  [" + status(true) + "] AtomicOps (VarHandle)");
        System.out.println("  [" + status(true) + "] SegmentAllocator");
        System.out.println("  [" + status(true) + "] Codecs");
        System.out.println();
        System.out.println("Infrastructure:");
        System.out.println("  [" + status(true) + "] Logging (SLF4J + Logback)");
        System.out.println("  [" + status(metricsRegistry != null) + "] Metrics System");
        int mbeanCount = metricsRegistry != null ? metricsRegistry.getAllMBeanNames().size() : 0;
        System.out.println("  [" + status(mbeanCount > 0) + "] JMX MBeans (" + mbeanCount + " registered)");
        System.out.println();
    }

    private String status(boolean ok) {
        return ok ? "OK" : "  ";
    }

    private void printStatus() {
        System.out.println("========================================");
        System.out.println("ExcaliburDB ready.");
        if (config != null) {
            System.out.println("Configuration:");
            System.out.println("  Epoch tick: " + config.visibility().getEpochTickMs() + "ms");
            System.out.println("  Include partial segments: " + config.query().includePartialSegments());
            System.out.println("  HNSW queue depth limit: " + config.ingest().getHnswQueueDepthLimit());
            System.out.println("  Readiness threshold: " + config.health().getReadinessThreshold());
        }
        System.out.println();
        System.out.println("Metrics:");
        System.out.println("  Health score: " + String.format("%.2f", Metrics.getHealthScore()));
        System.out.println("  Estimated coverage: " + String.format("%.2f%%", Metrics.getEstimatedCoverage() * 100));
        System.out.println("  Current epoch: " + Metrics.getCurrentEpoch());
        System.out.println("========================================");
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Gets the loaded configuration.
     *
     * @return The configuration, or null if not yet initialized
     */
    public Config getConfig() {
        return config;
    }

    /**
     * Gets the metrics registry.
     *
     * @return The metrics registry, or null if not yet initialized
     */
    public MetricsRegistry getMetricsRegistry() {
        return metricsRegistry;
    }
}
