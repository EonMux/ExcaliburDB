// excalibur-bench (Phase 10) - Benchmarks
plugins {
    `java-library`
    application
}

group = "com.excaliburdb"
version = "1.0.0-SNAPSHOT"

application {
    mainClass.set("excalibur.bench.BenchmarkRunner")
    applicationDefaultJvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms1g",
        "-Xmx1g"
    )
}

dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // JMH for benchmarks
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    // Phase 1 core dependency for memory-mapped I/O
    implementation(project(":excalibur-core"))

    // Phase 2 dependency for Segment benchmarks
    implementation(project(":excalibur-store"))

    // Phase 3 dependency for Cuckoo Filter benchmarks
    implementation(project(":excalibur-filter"))
}

// Enable Panama FFM preview features
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("--enable-preview")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    jvmArgs("--enable-preview")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Task to run the JMH benchmark for Segment
tasks.register<JavaExec>("runBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for Segment lookups"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.BenchmarkRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms1g",
        "-Xmx1g"
    )
}

// Task to run the JMH benchmark for Cuckoo Filter
tasks.register<JavaExec>("runCuckooFilterBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for Cuckoo Filter lookup throughput at various load factors"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.CuckooFilterBenchmarkRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms2g",
        "-Xmx2g"
    )
}

// Task to run the JMH benchmark for Cuckoo Filter memory-mapped I/O
tasks.register<JavaExec>("runCuckooFilterMemoryMappedBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for Cuckoo Filter memory-mapped read/write throughput"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.CuckooFilterMemoryMappedBenchmarkRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms4g",
        "-Xmx4g"
    )
}

// Task to run the JMH benchmark for Cuckoo Filter delete throughput
tasks.register<JavaExec>("runCuckooFilterDeleteBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for Cuckoo Filter delete throughput at various load factors"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.CuckooFilterDeleteBenchmarkRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms2g",
        "-Xmx2g"
    )
}
