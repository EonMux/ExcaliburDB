// excalibur-query - Query Pipeline
plugins {
    `java-library`
}


dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // Core primitives: chunk identifiers, payload codec, structured logging
    implementation(project(":excalibur-core"))

    // Configuration module
    implementation(project(":excalibur-config"))

    // Storage engine (WriteLog access)
    implementation(project(":excalibur-store"))

    // Probabilistic structures, vector index, and index orchestration
    implementation(project(":excalibur-filter"))
    implementation(project(":excalibur-vector"))
    implementation(project(":excalibur-shard"))

    // LLM client (relevancy scoring)
    implementation(project(":excalibur-llm"))

    // Ingestion (document visibility tracking)
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
