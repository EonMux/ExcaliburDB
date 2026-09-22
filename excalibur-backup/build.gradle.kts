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

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

// Two flags are not optional. --enable-preview because every class in this build is stamped
// as a preview class file and a JVM without it refuses to load one; --add-modules for the
// incubating Vector API, which excalibur-vector's classes reference and which is not
// resolvable at runtime unless the module is added. --enable-native-access silences the
// restricted-method warning the memory-mapped engine would otherwise print on every run,
// which would otherwise be interleaved with this command's own output.
val runtimeJvmFlags = listOf(
    "--enable-preview",
    "--enable-native-access=ALL-UNNAMED",
    "--add-modules=jdk.incubator.vector"
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
