// excalibur-shard - Index Orchestration
plugins {
    `java-library`
}


dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // Core primitives (XxHash64)
    implementation(project(":excalibur-core"))

    // Storage engine, probabilistic structures, and vector index
    implementation(project(":excalibur-store"))
    implementation(project(":excalibur-filter"))
    implementation(project(":excalibur-vector"))

    // Config module for Config.Shard
    implementation(project(":excalibur-config"))

    // Metrics module for JMX observability
    implementation(project(":excalibur-metrics"))
}

// Enable preview features for Java 21
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--enable-preview")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
}
