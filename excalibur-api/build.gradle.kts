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
    mainClass.set("excalibur.api.ExcaliburApplication")
    applicationDefaultJvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED"
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
        attributes["Main-Class"] = "excalibur.api.ExcaliburApplication"
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }

    // `java -jar` cannot be told to enable preview from the manifest, so the fat JAR is
    // launched through the generated start scripts (which carry the flag) or with the
    // flag supplied explicitly. State that where whoever ships it will read it.
    doLast {
        logger.lifecycle(
            "Built {}. Launch it with: java --enable-preview --enable-native-access=ALL-UNNAMED -jar {}",
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
        "--enable-native-access=ALL-UNNAMED"
    )
}
