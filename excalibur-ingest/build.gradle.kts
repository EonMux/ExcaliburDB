// excalibur-ingest (Phase 8) - Ingestion Pipeline
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

    // Phase 2, 3, 4, 5, 7 dependencies
    implementation(project(":excalibur-store"))
    implementation(project(":excalibur-filter"))
    implementation(project(":excalibur-vector"))
    implementation(project(":excalibur-shard"))
    implementation(project(":excalibur-llm"))
}
