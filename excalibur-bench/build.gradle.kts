// excalibur-bench (Phase 10) - Benchmarks
plugins {
    `java-library`
    application
}


// The flags every JVM in this module starts with: the runner JVMs the tasks below launch, the
// test JVM, and the forks JMH starts from them. Without --enable-preview no class in this build
// loads, and without --add-modules=jdk.incubator.vector any benchmark touching the Vector API
// dies on its first call - which a pattern-selected run reaches whenever the pattern is broad
// enough. The runners hand the same list to their forks through ForkedJvm.REQUIRED_FLAGS.
val benchmarkJvmFlags = listOf(
    "--enable-preview",
    "--enable-native-access=ALL-UNNAMED",
    "--add-modules=jdk.incubator.vector"
)

application {
    mainClass.set("com.eonmux.excalibur.bench.BenchmarkRunner")
    applicationDefaultJvmArgs = benchmarkJvmFlags + listOf("-Xms1g", "-Xmx1g")
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
    implementation(project(":excalibur-retrieval"))

    // Graph-route calibration opens a real graph directory and fits abstention against it. It goes
    // through GraphRagFactory rather than assembling a pipeline of its own: thresholds fitted to a
    // pipeline wired differently from the served one are thresholds for a node nobody runs, and
    // the wiring is exactly the kind of thing that drifts silently between two copies.
    implementation(project(":excalibur-api"))
    implementation(project(":excalibur-config"))
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
    jvmArgs(benchmarkJvmFlags)
}

// Every benchmark in the module, or the ones one selector names (see BenchmarkSelection)
tasks.register<JavaExec>("runBenchmark") {
    group = "benchmark"
    description = "Runs every JMH benchmark in the module, or those one argument selects (segment, cuckoo, cuckoo-insert)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.BenchmarkRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xms1g", "-Xmx1g")
}

// Task to run the JMH benchmark for Cuckoo Filter
tasks.register<JavaExec>("runCuckooFilterBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for Cuckoo Filter lookup throughput at various load factors"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.CuckooFilterBenchmarkRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xms2g", "-Xmx2g")
}

// Task to run the JMH benchmark for Cuckoo Filter memory-mapped I/O
tasks.register<JavaExec>("runCuckooFilterMemoryMappedBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for Cuckoo Filter memory-mapped read/write throughput"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.CuckooFilterMemoryMappedBenchmarkRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xms4g", "-Xmx4g")
}

// Task to run the JMH benchmark for Cuckoo Filter delete throughput
tasks.register<JavaExec>("runCuckooFilterDeleteBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for Cuckoo Filter delete throughput at various load factors"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.CuckooFilterDeleteBenchmarkRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xms2g", "-Xmx2g")
}

// Task to run the JMH benchmark for Cuckoo Filter false positive rate measurement
tasks.register<JavaExec>("runCuckooFilterFPRateBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for Cuckoo Filter actual false positive rate measurement (target: <=0.1%)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.CuckooFilterFPRateBenchmarkRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xms2g", "-Xmx2g")
}

// Task to run the JMH benchmark for Vector Distance functions
tasks.register<JavaExec>("runDistanceBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for Vector Distance functions (Cosine, L2, Inner Product)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.DistanceBenchmarkRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xms2g", "-Xmx2g")
}

// Task to run the JMH benchmark for HNSW recall@10 validation
tasks.register<JavaExec>("runHnswRecallBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for HNSW recall@10 validation (target: >=99%)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.HnswRecallBenchmarkRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xms4g", "-Xmx4g")
}

// Task to run the JMH benchmark for MappedFile read/write throughput
tasks.register<JavaExec>("runMappedFileBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for MappedFile read/write throughput (Phase 1)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.MappedFileBenchmarkRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xms2g", "-Xmx2g")
}

// Task to run the JMH benchmark for Hash function speed (Phase 1 requirement)
tasks.register<JavaExec>("runHashBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for XxHash64 hash function speed"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.HashBenchmarkRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xms1g", "-Xmx1g")
}

// Task to run the JMH benchmark for Tail Scan throughput (Phase 6)
tasks.register<JavaExec>("runTailScanBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for tail scan brute-force throughput at varying tail sizes"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.TailScanBenchmarkRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xms2g", "-Xmx2g")
}

// Task to run the JMH benchmark for end-to-end query latency (Phase 10)
tasks.register<JavaExec>("runEndToEndQueryBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for end-to-end query latency p50/p99"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.EndToEndQueryBenchmarkRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xms2g", "-Xmx2g")
}

// Task to run the JMH benchmark for Point Lookup throughput (Phase 10 - Feature 97)
tasks.register<JavaExec>("runPointLookupBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark for point lookup throughput (target: >=10M ops/sec)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.PointLookupBenchmarkRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xms2g", "-Xmx2g", "-XX:+UseTransparentHugePages")
}

// Task to run the JMH benchmark for Distance Vector API comparison (Phase 10)
tasks.register<JavaExec>("runDistanceVectorApiBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark comparing scalar vs Vector API distance computation"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.DistanceVectorApiBenchmarkRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xms2g", "-Xmx2g")
}

// Task to run benchmarks with structured result capture (Phase 10)
tasks.register<JavaExec>("runBenchmarksWithCapture") {
    group = "benchmark"
    description = "Runs all benchmarks and captures results in JSON, Markdown, CSV, and text formats"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.BenchmarkResultsCaptureRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xms2g", "-Xmx2g")
    val baseArgs = if (project.hasProperty("benchmarkPattern")) {
        listOf("--capture", "--pattern", project.property("benchmarkPattern") as String)
    } else {
        listOf("--capture")
    }
    args = if (project.hasProperty("outputDir")) {
        baseArgs + listOf("--output", project.property("outputDir") as String)
    } else {
        baseArgs
    }
}

// Scores the retrieval strategies over a real corpus. Deliberately not JMH: the thing being
// measured is retrieval quality against judged queries, with latency beside it, not a microbenchmark.
//
// Measures the engine a node ships with unless the last argument says otherwise:
//   -PevalArgs="<corpusDir> <indexDir> <qrels> 10 - - both"   ('-' skips a positional)
// Fits the graph route's abstention thresholds and confidence temperature against judged
// questions, and prints the configuration lines a node needs. Deliberately not JMH, and
// deliberately not a server route: the labels have to come from a query set whose answers are
// known, and a running node cannot manufacture one for itself.
//
//   -PcalibrationArgs="<graphDir> <judgedFile> 0.9 excalibur.properties"
tasks.register<JavaExec>("runGraphCalibration") {
    group = "verification"
    description = "Fits the graph route's abstention thresholds and confidence temperature against a judged question set"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.calibration.GraphCalibrationRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xmx4g")
    if (project.hasProperty("calibrationArgs")) {
        args = (project.property("calibrationArgs") as String).split(" ")
    }
}

tasks.register<JavaExec>("runRetrievalEvaluation") {
    group = "verification"
    description = "Indexes a corpus on the native (or lucene, or both) engine and scores the retrieval strategies over it, with the index size beside the quality"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.eonmux.excalibur.bench.retrieval.CorpusEvaluationRunner")
    jvmArgs = benchmarkJvmFlags + listOf("-Xmx6g")
    if (project.hasProperty("evalArgs")) {
        args = (project.property("evalArgs") as String).split(" ")
    }
}
