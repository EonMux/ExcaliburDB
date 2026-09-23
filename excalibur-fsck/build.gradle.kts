// excalibur-fsck - offline integrity check and repair for a node's data directory
//
// A module of its own rather than a package inside excalibur-api, for two reasons. The tool has
// to run when nothing is running: if it lived beside the server bootstrap it would share that
// module's dependency graph and its start-up wiring, and the first thing an operator does with
// it is run it on a node that will not start. And it reads formats owned by four different
// modules - store, vector, filter and retrieval - so it belongs where depending on all four is
// the point rather than an accident.
plugins {
    `java-library`
    application
}

// The second entry point of the build, alongside excalibur-api's server. The JVM flags are the
// union of what the modules it reads were compiled with: --enable-preview because store, core
// and filter are preview class files, --enable-native-access because the formats are read
// through memory mappings, and --add-modules=jdk.incubator.vector because excalibur-vector's
// classes import it and would fail to link without it.
application {
    mainClass.set("com.eonmux.excalibur.fsck.ExcaliburFsck")
    applicationName = "excalibur-fsck"
    applicationDefaultJvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector"
    )
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.16")

    // The format owners, as implementation rather than api. excalibur-backup depends on this
    // module for its per-file dispatch, and declares its own dependency on each format module
    // whose classes it names, so none of these is part of what this module exports.
    implementation(project(":excalibur-core"))
    implementation(project(":excalibur-store"))
    implementation(project(":excalibur-vector"))
    implementation(project(":excalibur-filter"))
    implementation(project(":excalibur-retrieval"))
    // The per-tenant graph staging logs live in the data directory too, and this is where their
    // magic numbers and the one public read-only verifier among them are declared.
    implementation(project(":excalibur-graph"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    // A real JSON parser for the --json contract: asserting on substrings cannot tell valid
    // JSON from invalid, which is the only thing that mode promises.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.1")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("--enable-preview")
    options.compilerArgs.add("--add-modules=jdk.incubator.vector")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector"
    )
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions)
        .addStringOption("-add-modules", "jdk.incubator.vector")
}

// Runs the checker against a directory without installing anything. The arguments are the
// command line the installed excalibur-fsck takes - the data directory, then any options - and
// a relative directory resolves against this module, the task's working directory:
//   ./gradlew :excalibur-fsck:fsck --args="/var/lib/excalibur"
//   ./gradlew :excalibur-fsck:fsck --args="/var/lib/excalibur --strict --json"
tasks.register<JavaExec>("fsck") {
    group = "verification"
    description = "Runs the offline data directory integrity checker"
    mainClass.set("com.eonmux.excalibur.fsck.ExcaliburFsck")
    classpath = sourceSets["main"].runtimeClasspath
    // A meaningful exit code is the point of the tool, so the task must not swallow it.
    isIgnoreExitValue = false
}

tasks.withType<JavaExec> {
    jvmArgs(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector"
    )
}
