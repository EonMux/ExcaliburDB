// excalibur-bench (Phase 10) - Benchmarks
plugins {
    `java-library`
    application
}


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
    jvmArgs("--enable-preview")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("--add-modules=jdk.incubator.vector")
}

// Runs the throughput and FP-rate gates that the root build excludes from `test` via
// excludeTags("slow", "benchmark"). Without this task those tests never execute at all,
// so the targets they assert are never enforced anywhere.
tasks.register<Test>("benchmarkTest") {
    group = "verification"
    description = "Runs the tag-excluded benchmark gate tests (slow: takes minutes)"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        // The root build applies excludeTags("slow", "benchmark") to every Test task.
        // Exclusion beats inclusion, so it has to be cleared here or this task would
        // select nothing at all - which is how these gates came to never run.
        excludeTags = LinkedHashSet()
        includeTags("slow", "benchmark")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    jvmArgs("--enable-preview")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("--add-modules=jdk.incubator.vector")
    jvmArgs("-Xmx2g")

    // No coverage agent. The root build applies jacoco to every subproject, and the plugin
    // attaches its agent to every Test task it finds - including this one, which is the one
    // task in the build whose entire output is a measurement of how fast the code runs.
    //
    // It is not a uniform tax that a target could simply be set below. JaCoCo counts through a
    // boolean[] of probes per class, shared by every thread executing that class, so a hot loop
    // run on twenty-four threads turns into twenty-four cores writing the same few cache lines.
    // Measured on this benchmark's own workload: 6.98 M ops/sec single-threaded and 37.40 M on
    // twenty-four threads without the agent, against 5.61 M and 1.31 M with it. The scaling
    // gate therefore read 0.23x - it was measuring the instrumentation contending with itself,
    // and would have gone on reporting a concurrency defect in the storage engine that the
    // engine does not have.
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }

    // Benchmarks measure a moving system; never reuse a previous run's verdict.
    outputs.upToDateWhen { false }
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

// Task to run the JMH benchmark for Distance Vector API comparison (Phase 10)
tasks.register<JavaExec>("runDistanceVectorApiBenchmark") {
    group = "benchmark"
    description = "Runs the JMH benchmark comparing scalar vs Vector API distance computation"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.DistanceVectorApiBenchmarkRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector",
        "-Xms2g",
        "-Xmx2g"
    )
}

// Task to run benchmarks with structured result capture (Phase 10)
tasks.register<JavaExec>("runBenchmarksWithCapture") {
    group = "benchmark"
    description = "Runs all benchmarks and captures results in JSON, Markdown, CSV, and text formats"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.BenchmarkResultsCaptureRunner")
    jvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms2g",
        "-Xmx2g"
    )
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
    mainClass.set("excalibur.bench.calibration.GraphCalibrationRunner")
    jvmArgs = listOf("--enable-preview", "--enable-native-access=ALL-UNNAMED", "-Xmx4g")
    if (project.hasProperty("calibrationArgs")) {
        args = (project.property("calibrationArgs") as String).split(" ")
    }
}

tasks.register<JavaExec>("runRetrievalEvaluation") {
    group = "verification"
    description = "Indexes a corpus on the native (or lucene, or both) engine and scores the retrieval strategies over it, with the index size beside the quality"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("excalibur.bench.retrieval.CorpusEvaluationRunner")
    jvmArgs = listOf("--enable-preview", "--enable-native-access=ALL-UNNAMED", "-Xmx6g")
    if (project.hasProperty("evalArgs")) {
        args = (project.property("evalArgs") as String).split(" ")
    }
}
