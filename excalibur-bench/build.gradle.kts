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

    // Apache Commons Math3 for JMH statistical summaries
    implementation("org.apache.commons:commons-math3:3.6.1")

    // Phase 1 core dependency for memory-mapped I/O
    implementation(project(":excalibur-core"))

    // Phase 2 dependency for Segment benchmarks
    implementation(project(":excalibur-store"))

    // Phase 3 dependency for Cuckoo Filter benchmarks
    implementation(project(":excalibur-filter"))

    // Phase 4 dependency for Vector Distance benchmarks
    implementation(project(":excalibur-vector"))

    // Phase 5 dependency for Shard (VisibilityTracker)
    implementation(project(":excalibur-shard"))

    // Phase 6 dependency for Tail Scan benchmarks
    implementation(project(":excalibur-query"))

    // Phase 8 dependency for Ingestion (DocumentVisibilityChecker)
    implementation(project(":excalibur-ingest"))
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
    jvmArgs("--add-modules=jdk.incubator.vector")
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

// Task to run the JMH benchmark for Cuckoo Filter false positive rate measurement
tasks.register<JavaExec>("runCuckooFilterFPRateBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for Cuckoo Filter actual false positive rate measurement (target: <=0.1%)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.CuckooFilterFPRateBenchmarkRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms2g",
        "-Xmx2g"
    )
}

// Task to run the JMH benchmark for Vector Distance functions
tasks.register<JavaExec>("runDistanceBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for Vector Distance functions (Cosine, L2, Inner Product)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.DistanceBenchmarkRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector",
        "-Xms2g",
        "-Xmx2g"
    )
}

// Task to run the JMH benchmark for HNSW recall@10 validation
tasks.register<JavaExec>("runHnswRecallBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for HNSW recall@10 validation (target: >=99%)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.HnswRecallBenchmarkRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms4g",
        "-Xmx4g"
    )
}

// Task to run the JMH benchmark for MappedFile read/write throughput
tasks.register<JavaExec>("runMappedFileBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for MappedFile read/write throughput (Phase 1)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.MappedFileBenchmarkRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms2g",
        "-Xmx2g"
    )
}

// Task to run the JMH benchmark for Hash function speed (Phase 1 requirement)
tasks.register<JavaExec>("runHashBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for XxHash64 hash function speed"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.HashBenchmarkRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms1g",
        "-Xmx1g"
    )
}

// Task to run the JMH benchmark for Tail Scan throughput (Phase 6)
tasks.register<JavaExec>("runTailScanBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for tail scan brute-force throughput at varying tail sizes"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.TailScanBenchmarkRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms2g",
        "-Xmx2g"
    )
}

// Task to run the JMH benchmark for end-to-end query latency (Phase 10)
tasks.register<JavaExec>("runEndToEndQueryBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for end-to-end query latency p50/p99"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.EndToEndQueryBenchmarkRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms2g",
        "-Xmx2g"
    )
}

// Task to run the JMH benchmark for Vector Layout (Phase 10)
tasks.register<JavaExec>("runVectorLayoutBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for Vector Layout operations (SoA vs AoS)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.VectorLayoutBenchmarkRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector",
        "-Xms2g",
        "-Xmx2g"
    )
}

// Task to run the JMH benchmark for Point Lookup throughput (Phase 10 - Feature 97)
tasks.register<JavaExec>("runPointLookupBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for point lookup throughput (target: >=1B ops/sec)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.PointLookupBenchmarkRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms2g",
        "-Xmx2g",
        "-XX:+UseTransparentHugePages"
    )
}
