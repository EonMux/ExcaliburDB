// excalibur-query (Phase 6) - Query Pipeline
plugins {
    `java-library`
}

group = "com.excaliburdb"
version = "1.0.0-SNAPSHOT"

dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")

    // Phase 1 (Core primitives - TermExtractor for query text processing)
    implementation(project(":excalibur-core"))

    // Configuration module
    implementation(project(":excalibur-config"))

    // Phase 2 (Storage Engine - needed for WriteLog access)
    implementation(project(":excalibur-store"))

    // Phase 3, 4, 5 dependencies
    implementation(project(":excalibur-filter"))
    implementation(project(":excalibur-vector"))
    implementation(project(":excalibur-shard"))

    // Phase 7 (LLM Client - for relevancy scoring)
    implementation(project(":excalibur-llm"))

    // Phase 8 dependency for document visibility tracking
    implementation(project(":excalibur-ingest"))

    // Metrics module for JMX observability
    implementation(project(":excalibur-metrics"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    jvmArgs("--enable-preview")
}
