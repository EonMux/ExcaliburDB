package excalibur.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Asserts that what git tracks is enough to build and test this project.
 *
 * <p>Every other test in this repository runs against the working tree, which holds whatever the
 * last edit left behind whether or not it was ever committed. That makes the whole suite blind to
 * the one defect a clone experiences first: a file the build reads that nobody added. The build
 * succeeds here and fails everywhere else, and the failure arrives as a compiler error in a module
 * that was never touched.</p>
 *
 * <p>It has happened twice. A {@code build/} line in {@code .gitignore} matched the Java package
 * {@code io.excalibur.retrieval.build} and kept its sources out of the repository, so a clone could
 * not compile excalibur-api. Later, three modules were added to {@code settings.gradle.kts} with
 * their sources committed and their build scripts left untracked: every module's test runtime
 * depends on {@code :excalibur-test-bootstrap}, so a clone could not run a single test, and the
 * {@code installDist} tasks the README tells an operator to run did not exist. Neither was visible
 * from inside a working tree.</p>
 *
 * <p>Checking git rather than the filesystem is the entire point, so this asks git.</p>
 *
 * @author ExcaliburDB
 */
@DisplayName("Repository completeness")
class RepositoryCompletenessTest {

    /** Pulls the quoted project names out of the {@code include(...)} call in the settings file. */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    /**
     * Root files that something outside this build reads, and that a clone therefore has to hold.
     *
     * <p>The wrapper and the two build scripts are how the project is built at all. The Dockerfile
     * and its context filter are what {@code docker build -t excalibur .} in the README needs. The
     * properties template is the documented first step of configuring a node - the working copy
     * beside it is gitignored precisely because it ends up holding an API key, which leaves the
     * template as the only copy a clone can start from.</p>
     */
    private static final List<String> REQUIRED_ROOT_FILES = List.of(
        "settings.gradle.kts",
        "build.gradle.kts",
        "gradle.properties",
        "gradlew",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties",
        "Dockerfile",
        ".dockerignore",
        "excalibur.properties.example",
        "README.md",
        "USAGE.md",
        "LICENSE"
    );

    private final Path root = repositoryRoot();

    @Nested
    @DisplayName("for every module the settings file includes")
    class ForEveryIncludedModule {

        @Test
        @DisplayName("the directory it names exists")
        void everyIncludedModuleHasADirectory() throws IOException {
            List<String> missing = new ArrayList<>();
            for (String module : includedModules()) {
                if (!Files.isDirectory(root.resolve(module))) {
                    missing.add(module);
                }
            }

            assertThat(missing)
                .as("a project included with no directory configures to nothing, so the tasks "
                    + "that should exist in it are simply absent")
                .isEmpty();
        }

        @Test
        @DisplayName("git tracks its build script")
        void everyIncludedModuleHasATrackedBuildScript() throws IOException {
            List<String> untracked = new ArrayList<>();
            for (String module : includedModules()) {
                String script = module + "/build.gradle.kts";
                if (!tracked(script)) {
                    untracked.add(script);
                }
            }

            assertThat(untracked)
                .as("the root script applies java-library to every subproject, so a module whose "
                    + "own script is missing still compiles - against none of its dependencies, "
                    + "and without the application or shadow plugins that declare its entry point")
                .isEmpty();
        }

        @Test
        @DisplayName("git tracks every file under its source tree")
        void everyIncludedModuleHasNoUntrackedSources() throws IOException {
            List<String> untracked = new ArrayList<>();
            for (String module : includedModules()) {
                Path sources = root.resolve(module).resolve("src");
                if (Files.isDirectory(sources)) {
                    // No --exclude-standard: a source file that .gitignore matches by accident is
                    // the failure being looked for, not one to filter out of the answer.
                    untracked.addAll(git("ls-files", "--others", "--", module + "/src"));
                }
            }

            assertThat(untracked)
                .as("a source file git does not hold is a file a clone compiles without")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("for the files a clone is told to use")
    class ForTheDocumentedEntryPoints {

        @Test
        @DisplayName("git tracks each of them")
        void everyRequiredRootFileIsTracked() throws IOException {
            List<String> untracked = new ArrayList<>();
            for (String file : REQUIRED_ROOT_FILES) {
                if (!tracked(file)) {
                    untracked.add(file);
                }
            }

            assertThat(untracked)
                .as("each of these is named by the README as something to run or copy, so one "
                    + "that is not in the repository is an instruction a clone cannot follow")
                .isEmpty();
        }
    }

    /** The project names in the settings file's {@code include(...)} call. */
    private List<String> includedModules() throws IOException {
        String settings = Files.readString(root.resolve("settings.gradle.kts"),
            StandardCharsets.UTF_8);
        int open = settings.indexOf("include(");
        assertThat(open).as("settings.gradle.kts declares no include(...) call").isNotNegative();
        int close = settings.indexOf(')', open);
        assertThat(close).as("the include(...) call in settings.gradle.kts is unterminated")
            .isGreaterThan(open);

        List<String> modules = new ArrayList<>();
        Matcher matcher = QUOTED.matcher(settings.substring(open, close));
        while (matcher.find()) {
            modules.add(matcher.group(1));
        }

        assertThat(modules).as("no modules were parsed out of settings.gradle.kts, so this test "
            + "is asserting nothing").isNotEmpty();
        return modules;
    }

    /** Whether git holds this path, asked of the index rather than of the filesystem. */
    private boolean tracked(String path) throws IOException {
        return !git("ls-files", "--", path).isEmpty();
    }

    /** Runs git at the repository root and returns its output lines, blank ones dropped. */
    private List<String> git(String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));

        Process process = new ProcessBuilder(command)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start();

        List<String> lines;
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            lines = reader.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        }

        int status;
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(command + " did not finish within 30 seconds");
            }
            status = process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for " + command, e);
        }

        if (status != 0) {
            throw new IOException(command + " exited " + status + ": " + String.join("\n", lines));
        }
        return new ArrayList<>(lines);
    }

    /**
     * The repository root, found by walking up from wherever the test happens to be run.
     *
     * <p>Skipped rather than failed when there is no {@code .git} beside it: an export or a
     * vendored copy of these sources is not a repository and has nothing for this to ask about.
     * Every place this test is meant to catch something - a working tree and CI - is a checkout.</p>
     */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                assumeTrue(Files.isDirectory(directory.resolve(".git")),
                    "not a git checkout, so there is no index to compare the build against");
                return directory;
            }
            directory = directory.getParent();
        }
        throw new AssertionError("no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
