// excalibur-api (Phase 9) - API Surface
plugins {
    `java-library`
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}


// Application plugin configuration.
//
// The module is compiled with --enable-preview (see the root build), which stamps every
// class file as a preview class. A preview class file is rejected by a JVM that was not
// started with --enable-preview, so the flag is not optional at runtime: without it the
// generated start scripts and the fat JAR fail immediately with UnsupportedClassVersionError.
// applicationDefaultJvmArgs is what puts the flag into bin/excalibur-api, bin/excalibur-api.bat
// and the shadow start scripts.
application {
    mainClass.set("com.eonmux.excalibur.api.ExcaliburApplication")
    // The two preview flags are required to start at all. The rest are the flags that decide
    // whether the first failure in production is diagnosable: a memory-mapped engine that runs
    // out of heap leaves no artefact unless it is asked for one, and a JVM that survives an
    // OutOfMemoryError in a worker thread keeps answering /health while doing no work.
    applicationDefaultJvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        // Without it the SIMD distance kernels fall back to scalar arithmetic silently: a node
        // that works and is several times slower at every search. The container passes it, so
        // omitting it here would make the start scripts a different node from the image.
        "--add-modules=jdk.incubator.vector",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=.",
        "-XX:+ExitOnOutOfMemoryError"
    )
}

dependencies {
    // The graph-RAG path: planning, traversal, ranking, serialisation and abstention.
    implementation(project(":excalibur-retrieval"))
    implementation(project(":excalibur-tenancy"))
    implementation(project(":excalibur-graph"))
    implementation(project(":excalibur-planner"))

    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // The storage engine logs through java.util.logging. Without this bridge those records go
    // to the JDK's own handler instead of the configured appender: they miss the JSON format
    // every other line has, and anything below INFO is dropped before the log level is even
    // consulted. Installed by ExcaliburApplication.
    implementation("org.slf4j:jul-to-slf4j:2.0.16")

    // Logback for structured logging
    runtimeOnly("ch.qos.logback:logback-classic:1.5.12")

    // Logstash encoder for JSON structured logging
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.0")

    // Test dependencies
    // A real JSON parser for tests only. Asserting on substrings of a hand-built response cannot
    // tell valid JSON from invalid -- it is how an unquoted string value passed review here.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    // The audit trail is a contract about what reaches the log - its logger name, and the
    // fields the shipped JSON encoder writes - so its tests read the events logback produced
    // and encode them the way logback.xml does.
    testImplementation("ch.qos.logback:logback-classic:1.5.12")
    testImplementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Phase 1 (core primitives) - for memory segment abstraction
    implementation(project(":excalibur-core"))

    // Phase 2, 3, 4, 5, 6, 7, 8 dependencies
    implementation(project(":excalibur-store"))
    implementation(project(":excalibur-filter"))
    implementation(project(":excalibur-shard"))
    implementation(project(":excalibur-query"))
    implementation(project(":excalibur-llm"))
    implementation(project(":excalibur-ingest"))
    implementation(project(":excalibur-metrics"))
    implementation(project(":excalibur-config"))
    implementation(project(":excalibur-vector"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar {
    archiveBaseName.set("excalibur-api")
    archiveVersion.set(version.toString())
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    // The node runs with the Vector API resolved - applicationDefaultJvmArgs above, and the
    // container's entrypoint - so its tests do too. Without the module VectorApiSupport falls
    // back to scalar arithmetic without a word, and every search these tests drive through the
    // real engine would exercise a distance kernel production never calls. The sources here do
    // not import the incubator package themselves, so compilation needs no matching flag.
    jvmArgs("--add-modules=jdk.incubator.vector")
    // ConfigKeyInventoryTest holds the README's settings table and the shipped properties
    // template against the keys and defaults the node actually reads. Gradle cannot see a file a
    // test opens for itself, so without these the task stays up to date when one of them changes
    // - and the check is skipped in exactly the case it exists for.
    inputs.file(rootProject.file("README.md"))
        .withPropertyName("readme")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("excalibur.properties.example"))
        .withPropertyName("propertiesTemplate")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // HealthcheckScriptTest runs the container's health check against a live listener, for the
    // same reason: an edit to the script must re-run the test that holds it to the node.
    inputs.file(rootProject.file("docker/excalibur-healthcheck"))
        .withPropertyName("healthcheckScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Shadow JAR configuration for fat JAR packaging
tasks.shadowJar {
    archiveBaseName.set("excalibur")
    archiveVersion.set("")
    archiveClassifier.set("")

    // Merge service files
    mergeServiceFiles()

    // Exclude unnecessary files
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")

    manifest {
        attributes["Main-Class"] = "com.eonmux.excalibur.api.ExcaliburApplication"
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
        // Stamped so a running node can say which build it is. "Which version is on this host"
        // is the first question of an incident and of a rollback, and a jar that cannot answer
        // it makes both guesswork.
        attributes["Implementation-Title"] = "ExcaliburDB"
        attributes["Implementation-Version"] = project.version.toString()
        attributes["Build-Jdk"] = System.getProperty("java.version")
    }

    // `java -jar` cannot be told to enable preview from the manifest, so the fat JAR is
    // launched through the generated start scripts (which carry the flag) or with the
    // flag supplied explicitly. State that where whoever ships it will read it.
    doLast {
        logger.lifecycle(
            "Built {}. Launch it with: java --enable-preview --enable-native-access=ALL-UNNAMED "
                + "--add-modules=jdk.incubator.vector -jar {}",
            archiveFileName.get(), archiveFileName.get()
        )
    }
}

// Make the build task depend on shadowJar
tasks.build {
    dependsOn(tasks.shadowJar)
}

// Run configuration for development
tasks.withType<JavaExec> {
    jvmArgs(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector"
    )
}
