package excalibur.api.endpoint;

import java.lang.management.ManagementFactory;

/**
 * Which build this process is, and how long it has been up.
 *
 * <p>"Which version is on this host" is the first question of an incident and of a rollback,
 * and the process could not answer it. The shadow JAR's manifest carries
 * {@code Implementation-Version}, so the answer existed on disk; nothing read it, no route
 * served it, and the startup banner listed every route and the security posture without ever
 * saying what it was a banner for. Finding out meant going and looking at the file on the
 * host, which is what an operator cannot do across forty pods during an outage.</p>
 *
 * <h2>Unknown is an answer</h2>
 * <p>Run from a directory of class files - under Gradle, in an IDE, in these tests - there is
 * no manifest, and {@link #version()} says {@code unknown}. That is deliberate: a rollback is
 * decided against this string, and a plausible-looking default would be decided against too.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Everything here is read once into a final field or delegated to the
 * thread-safe runtime bean.</p>
 *
 * @author ExcaliburDB
 */
public final class BuildInfo {

    /** What a build that stamped no version reports, rather than a plausible-looking guess. */
    private static final String UNKNOWN = "unknown";

    private static final String TITLE = readTitle();
    private static final String VERSION = readVersion();

    private BuildInfo() {
        // Utility class
    }

    /**
     * The product name, from the jar manifest.
     *
     * @return The stamped title, or {@code ExcaliburDB} where none was stamped
     */
    public static String title() {
        return TITLE;
    }

    /**
     * The build this process is running.
     *
     * @return The stamped version, or {@code unknown} where none was stamped
     */
    public static String version() {
        return VERSION;
    }

    /**
     * The JVM this process is running on.
     *
     * <p>Part of the same question: a node behaving differently from its siblings on the same
     * build is usually running a different runtime.</p>
     *
     * @return The Java version string
     */
    public static String jvm() {
        return System.getProperty("java.version", UNKNOWN);
    }

    /**
     * When this process started.
     *
     * @return Epoch milliseconds
     */
    public static long startedAtMs() {
        return ManagementFactory.getRuntimeMXBean().getStartTime();
    }

    /**
     * How long this process has been up.
     *
     * <p>Read from the runtime bean rather than from a timestamp taken when this class was
     * first touched, which would be however long ago the first request arrived.</p>
     *
     * @return Milliseconds since the JVM started
     */
    public static long uptimeMs() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

    private static String readTitle() {
        String title = BuildInfo.class.getPackage().getImplementationTitle();
        return title == null || title.isBlank() ? "ExcaliburDB" : title;
    }

    private static String readVersion() {
        String version = BuildInfo.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? UNKNOWN : version;
    }
}
