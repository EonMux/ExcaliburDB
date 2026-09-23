// excalibur-backup - verified snapshot, verify and restore for a node's data directory.
//
// A command, not a service. Every entry point here runs and exits; nothing in this module
// opens a port, schedules a thread pool or outlives the process that invoked it.
plugins {
    `java-library`
    application
}

// The snapshot tool reuses the engine's own integrity checkers rather than re-deriving what
// a valid segment, graph or codebook looks like. Those live in excalibur-store,
// excalibur-vector and excalibur-filter, and each of those modules declares its own
// dependencies as `implementation`, so the types this module names are declared here
// directly instead of being inherited.
dependencies {
    implementation("org.slf4j:slf4j-api:2.0.16")

    implementation(project(":excalibur-core"))
    implementation(project(":excalibur-config"))
    implementation(project(":excalibur-store"))
    implementation(project(":excalibur-vector"))
    implementation(project(":excalibur-filter"))
    // For SegmentManifest: the lexical index stamps the epoch a batch was published at, and
    // that stamp is half of what a node recomputes its epoch counter from on open.
    implementation(project(":excalibur-retrieval"))
    // For every format the engine keeps no verifier of its own beside - the write-ahead logs,
    // the routing directory, the registries, the graph staging logs, the lexical store and each
    // tenant's built graph - and for a compaction's staged pair, whose verdict depends on which
    // of the two is present rather than on either file's format. verify --deep hands those to
    // the same per-file dispatch excalibur-fsck surveys a live directory with, so the two tools
    // agree on which checker a file belongs to.
    implementation(project(":excalibur-fsck"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    // The graph staging logs a deep verify replays are written in tests by the classes that own
    // their formats, so a verdict of sound is about bytes a node really writes. Only the tests
    // need it: verify reaches those logs through excalibur-fsck's dispatch, never by name.
    testImplementation(project(":excalibur-graph"))
}

// Two flags are not optional. --enable-preview because every class in this build is stamped
// as a preview class file and a JVM without it refuses to load one; --add-modules for the
// incubating Vector API, which excalibur-vector's classes reference and which is not
// resolvable at runtime unless the module is added. --enable-native-access silences the
// restricted-method warning the memory-mapped engine would otherwise print on every run,
// which would otherwise be interleaved with this command's own output. The logging
// configuration is named rather than discovered: excalibur-fsck's jar carries a logback.xml too,
// and logback answers two of the default name by printing its status report to stdout, where
// this command's report goes (see src/main/resources/excalibur-backup-logback.xml).
val runtimeJvmFlags = listOf(
    "--enable-preview",
    "--enable-native-access=ALL-UNNAMED",
    "--add-modules=jdk.incubator.vector",
    "-Dlogback.configurationFile=excalibur-backup-logback.xml"
)

application {
    mainClass.set("com.eonmux.excalibur.backup.cli.BackupCli")
    applicationDefaultJvmArgs = runtimeJvmFlags
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("--add-modules=jdk.incubator.vector")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs(runtimeJvmFlags)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<JavaExec> {
    jvmArgs(runtimeJvmFlags)
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions)
        .addStringOption("-add-modules", "jdk.incubator.vector")
}

tasks.jar {
    archiveBaseName.set("excalibur-backup")
    archiveVersion.set(version.toString())
}
