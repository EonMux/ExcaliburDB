// excalibur-ingest (Phase 8) - Ingestion Pipeline
plugins {
    `java-library`
}


dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // Phase 1 (Core primitives - TermExtractor for text processing)
    implementation(project(":excalibur-core"))

    // Phase 2, 3, 4, 5, 7 dependencies
    implementation(project(":excalibur-store"))
    implementation(project(":excalibur-filter"))
    implementation(project(":excalibur-graph"))
    implementation(project(":excalibur-vector"))
    implementation(project(":excalibur-metrics"))
    implementation(project(":excalibur-shard"))
    implementation(project(":excalibur-llm"))
}
