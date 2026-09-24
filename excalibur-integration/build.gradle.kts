// excalibur-integration (Phase 10) - Integration Tests
plugins {
    `java`
}


dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // All phases dependencies
    implementation(project(":excalibur-core"))
    implementation(project(":excalibur-config"))
    implementation(project(":excalibur-store"))
    implementation(project(":excalibur-filter"))
    implementation(project(":excalibur-vector"))
    implementation(project(":excalibur-shard"))
    implementation(project(":excalibur-query"))
    implementation(project(":excalibur-llm"))
    implementation(project(":excalibur-ingest"))
    implementation(project(":excalibur-api"))

    // Test utilities (FailureCategorizer, FailureReportingExtension)
    testImplementation(project(":excalibur-test"))
}

// The embedded example in USAGE.md, compiled and run from an embedder's position.
//
// USAGE.md tells an embedder that the one coordinate it lists is the dependency the example
// needs. Inside `test` that claim is unverifiable: this module depends on every library module
// directly, so the example would compile here whatever excalibur-api exposes. This source set
// sees only excalibur-api, through the same variants a consumer of the published POM resolves -
// its `api` dependencies at compile time, its `implementation` ones at run time - so a type the
// example needs that excalibur-api does not hand on fails this compile first.
val embeddedExample: SourceSet by sourceSets.creating

dependencies {
    // Exactly the block USAGE.md shows; a change to one is a change to the other.
    "embeddedExampleImplementation"(project(":excalibur-api"))

    "embeddedExampleImplementation"("org.junit.jupiter:junit-jupiter:5.11.3")
    "embeddedExampleImplementation"("org.assertj:assertj-core:3.26.3")
    "embeddedExampleRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.11.3")
}

val embeddedExampleTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs USAGE.md's embedded example against only the dependencies it documents"
    testClassesDirs = embeddedExample.output.classesDirs
    classpath = embeddedExample.runtimeClasspath
    // The same flags as `test`, for the same reasons.
    jvmArgs("--add-modules=jdk.incubator.vector")

    // The documentation test reads USAGE.md and the example's source, neither of which Gradle
    // can see through the classpath; without these the task stays up to date when either
    // changes, which is the one case the test exists for.
    val usage = rootProject.file("USAGE.md")
    val exampleSource = file(
        "src/embeddedExample/java/com/eonmux/excalibur/integration/EmbeddedUsageExampleTest.java")
    inputs.file(usage).withPropertyName("usage").withPathSensitivity(PathSensitivity.RELATIVE)
    // The source too, not only its classes: a comment in the example is part of what the
    // documentation reproduces, and changes no class file.
    inputs.file(exampleSource).withPropertyName("exampleSource")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("excalibur.usage", usage.absolutePath)
    systemProperty("excalibur.embeddedExampleSource", exampleSource.absolutePath)
}

tasks.named("check") {
    dependsOn(embeddedExampleTest)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("--enable-preview")
}

tasks.jar {
    archiveBaseName.set("excalibur-integration")
    archiveVersion.set(version.toString())
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    // The same flags the node starts with. --enable-native-access because the engine maps its
    // files through Panama; --add-modules because without the Vector API resolved, the
    // distance kernels fall back to scalar arithmetic silently, and an end-to-end suite would
    // then prove the search path production does not take. No source here imports the
    // incubator package, so the compiler needs no matching flag.
    jvmArgs(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector"
    )
}
