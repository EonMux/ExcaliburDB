// Root build configuration
plugins {
    `java-library`
    jacoco
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
}

// Maven Central namespaces are claimed by domain, and eonmux.com is the one this project can
// prove. The sources sit under com.eonmux.excalibur; the coordinate stops at com.eonmux because
// every artifact id already begins with excalibur-, and com.eonmux.excalibur:excalibur-store
// would say it twice.
group = "com.eonmux"
version = "1.0.0-SNAPSHOT"

/** Stated once; every published POM's url and scm entries read from here. */
val PROJECT_URL = "https://github.com/EonMux/ExcaliburDB"

// Java 21 configuration
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// JaCoCo configuration for coverage reporting
jacoco {
    toolVersion = "0.8.11"
}

// Common configuration for all submodules
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    // Declared once here rather than per module. Ten modules had drifted to "com.excaliburdb",
    // so the same build published two different coordinate namespaces depending on which module
    // you asked for, and USAGE.md's dependency block resolved for some of them and not others.
    group = rootProject.group
    version = rootProject.version

    // Paired with the --enable-preview below. Without a pinned toolchain, --enable-preview is
    // compiled against whatever JDK happens to be on the machine, and preview APIs are not
    // stable across releases -- java.lang.foreign in particular changed between 21 and 22.
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    repositories {
        mavenCentral()
    }

    // Publishing, declared once for every module an embedder can depend on.
    //
    // Presence in this map is what makes a module publishable, and the description is the one
    // the POM carries. Five modules used to apply `maven-publish` and declare no publication, so
    // they owned a publishToMavenLocal task that installed nothing while looking installable in a
    // task listing; excalibur-store, excalibur-query and excalibur-vector -- the three an
    // embedded query cannot do without -- did not apply the plugin at all, so the set that did
    // install could not be assembled into a working dependency.
    //
    // Absent by intent: excalibur-bench is a JMH harness whose entry points are benchmark runs,
    // excalibur-integration and excalibur-test-bootstrap exist only for the test classpath, and
    // excalibur-fsck and excalibur-backup are commands run against a stopped data directory
    // rather than libraries anything would compile against, and excalibur-coverage is the
    // build's own coverage reporting, run by the tasks at the end of this file.
    val publishedModules = mapOf(
        "excalibur-config" to "Configuration loading and validation",
        "excalibur-metrics" to "Metrics registry and instrumentation",
        "excalibur-core" to "Core primitives: memory-mapped I/O, serialization and utilities",
        "excalibur-test" to "Shared test fixtures and harnesses",
        "excalibur-store" to "Storage engine: segments, write-ahead log and catalog",
        "excalibur-filter" to "Probabilistic membership structures",
        "excalibur-vector" to "Vector index: HNSW graph and product quantization",
        "excalibur-graph" to "Memory-mapped CSR adjacency alongside the vector index",
        "excalibur-retrieval" to "Hybrid ranking: lexical, dense, fusion and full-precision rerank",
        "excalibur-tenancy" to "Per-namespace index isolation, lifecycle and eviction",
        "excalibur-planner" to "Deterministic query classification and coreference",
        "excalibur-shard" to "Index orchestration and background maintenance",
        "excalibur-query" to "Query pipeline: planning, execution and result aggregation",
        "excalibur-llm" to "OpenAI-compatible client for embeddings and relevancy scoring",
        "excalibur-ingest" to "Ingestion pipeline: chunking, indexing and erasure",
        "excalibur-api" to "HTTP API surface, server bootstrap and the in-process embedded node"
    )

    publishedModules[project.name]?.let { moduleDescription ->
        apply(plugin = "maven-publish")

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])

                    // The shadow plugin adds its fat jar to the java component as a second
                    // runtime variant, and excalibur-api gives that jar an empty classifier so
                    // the launch commands and the Dockerfile can name excalibur.jar. Two
                    // artifacts of the same extension and classifier is not a publication Maven
                    // can express, so `publishToMavenLocal` - a documented command - failed on
                    // that module alone. Dropped rather than renamed: a library consumer wants
                    // the module's own classes and resolves the rest through its POM, and
                    // renaming the file would break the commands that name it.
                    afterEvaluate {
                        configurations.findByName("shadowRuntimeElements")?.let { shadow ->
                            (components["java"] as AdhocComponentWithVariants)
                                .withVariantsFromConfiguration(shadow) { skip() }
                        }
                    }

                    pom {
                        name.set(project.name)
                        description.set(moduleDescription)
                        url.set(PROJECT_URL)

                        licenses {
                            license {
                                // Matches the LICENSE file at the repository root. The two are
                                // the project's licence stated twice; they must not drift.
                                name.set("Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                distribution.set("repo")
                            }
                        }

                        developers {
                            developer {
                                // No email: the field is optional, and the address here was a
                                // reserved example domain, so it was never reachable by anyone
                                // who read it off a published artifact.
                                id.set("aeslampanah")
                                name.set("Amir Eslampanah")
                            }
                        }

                        scm {
                            url.set(PROJECT_URL)
                            connection.set("scm:git:$PROJECT_URL.git")
                            developerConnection.set(
                                "scm:git:git@github.com:EonMux/ExcaliburDB.git")
                        }
                    }
                }
            }
        }
    }

    dependencies {
        // SLF4J API for logging
        implementation("org.slf4j:slf4j-api:2.0.16")

        // Logback for structured logging (production)
        // Note: slf4j-simple is removed to avoid binding conflict with logback-classic
        runtimeOnly("ch.qos.logback:logback-classic:1.5.12")

        // Logstash encoder for JSON structured logging
        runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.0")

        // Test dependencies
        testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.3")
        testImplementation("org.assertj:assertj-core:3.26.3")

        // Initializes java.lang.foreign on one thread before the first test runs. Two threads
        // reaching it first at the same instant deadlock the JVM on class-initialization
        // monitors, which no timeout can break because such a thread cannot be interrupted -
        // the build hangs with no failing test to point at. This is not about parallel test
        // execution: components across storage, query, retrieval, graph and shard hand work to
        // virtual threads, so one sequential test is already enough to race. Every module gets
        // it; the module itself cannot depend on its own output.
        if (project.name != "excalibur-test-bootstrap") {
            testRuntimeOnly(project(":excalibur-test-bootstrap"))
        }

        // Declared once. Six modules pinned Mockito individually and two of them had drifted to a
        // different version, so the same mock could behave differently depending on which module's
        // tests you were reading.
        testImplementation("org.mockito:mockito-core:5.15.2")
        testImplementation("org.mockito:mockito-junit-jupiter:5.15.2")
    }

    // Configure Java compilation for performance
    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.add("--enable-preview")
        // Fork compilation for better parallelism
        options.isFork = true
        options.forkOptions.memoryMaximumSize = "2g"
    }

    // Javadoc has to be told about preview the same way javac is. Without it every file that
    // touches the Panama FFM API - which is most of the storage and filter code - fails with
    // "Arena is a preview API and is disabled by default", and the task has no output at all.
    // -source rather than --release: the doclet needs the language level, and --release would
    // hide the preview classes it is being asked to document.
    tasks.withType<Javadoc> {
        val options = options as StandardJavadocDocletOptions
        options.encoding = "UTF-8"
        options.source = "21"
        options.addBooleanOption("-enable-preview", true)
        // A missing @param on a private helper is not worth failing a build over, and the
        // preview machinery emits notes of its own that nothing can silence.
        options.addStringOption("Xdoclint:none", "-quiet")
    }

    // Configure test execution for speed and reliability
    tasks.withType<Test> {
        useJUnitPlatform {
            // Exclude slow/benchmark tests from regular builds
            excludeTags("slow", "benchmark")
        }

        // JVM arguments for test execution
        jvmArgs(
            "--enable-preview",
            "--enable-native-access=ALL-UNNAMED",
            "-Xmx2g",
            "-XX:+UseG1GC"
        )

        // Test output configuration
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showCauses = true
            showStackTraces = true
        }

        // Ensure test output directories exist before tests run
        doFirst {
            mkdir(temporaryDir)
            val testResultsDir = layout.buildDirectory.dir("test-results/test").get().asFile
            mkdir(testResultsDir)
        }

        // Parallel test execution within module
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        setForkEvery(100)
    }

    // The counterpart to that exclusion, in every module rather than in one.
    //
    // `test` excludes the `slow` and `benchmark` tags because those tests take minutes, which
    // leaves them running nowhere at all unless something asks for them by name. This is that
    // something. It was registered in excalibur-bench alone, and a Test task only ever runs its
    // own project's classes, so the tags outside that module -- the Cuckoo filter's false
    // positive rate at a million items, HNSW recall against brute force -- were assertions about
    // this system's stated guarantees that no task in the build could execute.
    tasks.register<Test>("benchmarkTest") {
        group = "verification"
        description = "Runs the tag-excluded gate tests in this module (slow: takes minutes)"
        val testSourceSet = project.the<SourceSetContainer>()["test"]
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform {
            // Exclusion beats inclusion, so the block above has to be cleared here or this task
            // would select nothing -- which is how these gates came to never run.
            excludeTags = LinkedHashSet()
            includeTags("slow", "benchmark")
        }
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }

        // No coverage agent. The root build applies jacoco to every subproject, and the plugin
        // attaches its agent to every Test task it finds -- including this one, whose entire
        // output is a measurement of how fast the code runs.
        //
        // It is not a uniform tax that a target could simply be set below. JaCoCo counts through
        // a boolean[] of probes per class, shared by every thread executing that class, so a hot
        // loop run on twenty-four threads turns into twenty-four cores writing the same few cache
        // lines. Measured on the point-lookup benchmark's own workload: 6.98 M ops/sec
        // single-threaded and 37.40 M on twenty-four threads without the agent, against 5.61 M
        // and 1.31 M with it. The scaling gate therefore read 0.23x - it was measuring the
        // instrumentation contending with itself, and would have gone on reporting a concurrency
        // defect in the storage engine that the engine does not have.
        extensions.configure<JacocoTaskExtension> {
            isEnabled = false
        }

        // Benchmarks measure a moving system; never reuse a previous run's verdict.
        outputs.upToDateWhen { false }
    }

    // Source sets configuration
    sourceSets {
        main {
            java {
                srcDir("src/main/java")
            }
        }
        test {
            java {
                srcDir("src/test/java")
            }
        }
    }

    // JaCoCo test report configuration
    tasks.test {
        finalizedBy(tasks.jacocoTestReport)
    }

    tasks.jacocoTestReport {
        dependsOn(tasks.test)

        // Same exclusions as the aggregate: JMH's generated harness classes are machine-written
        // and never unit-tested by design, so counting them measures code generation. The
        // hand-written @Benchmark bodies beside them are excluded for the same reason -- see
        // the aggregate task below for why, and for what was pulled out of them first.
        classDirectories.setFrom(
            fileTree(layout.buildDirectory.dir("classes/java/main")) {
                exclude(
                    "**/jmh_generated/**",
                    "**/generated/**",
                    "**/excalibur/bench/benchmark/**")
            })

        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }

    // Per-module coverage floors, enforced as part of `check`.
    //
    // Empty, and meant to stay that way: every module is held to the project's 0.80 bar. An
    // entry here lowers that bar for one module, so each one is a debt with a name, and it is
    // deleted the moment the module clears 0.80 -- a floor left where coverage used to be lets
    // it fall back there unnoticed, which is the one thing a floor exists to prevent.
    //
    // Measured with generated code excluded; see the exclusions above for which, and why.
    val coverageFloors = mapOf<String, String>()

    tasks.jacocoTestCoverageVerification {
        dependsOn(tasks.test)
        // classDirectories below reads compileJava's output. `dependsOn(test)` normally implies
        // that, but `./gradlew build -x test` severs the implication and Gradle then refuses the
        // whole build for an undeclared task dependency. That command is documented in the README
        // and in USAGE.md, so the dependency is declared outright rather than left to be inferred
        // from a task that may not run.
        dependsOn(tasks.named("classes"))

        // Skipping the tests skips the evidence. Verifying with no execution data reports 0% for
        // every module and fails the build for a reason that has nothing to do with the code, so
        // `-x test` degrades to "not verified" rather than to "failed".
        onlyIf {
            layout.buildDirectory.file("jacoco/test.exec").get().asFile.exists()
        }

        classDirectories.setFrom(
            fileTree(layout.buildDirectory.dir("classes/java/main")) {
                exclude(
                    "**/jmh_generated/**",
                    "**/generated/**",
                    "**/excalibur/bench/benchmark/**")
            })

        violationRules {
            rule {
                limit {
                    minimum = (coverageFloors[project.name] ?: "0.80").toBigDecimal()
                }
            }
        }
    }

    // Wired into the standard lifecycle, so `./gradlew build` actually enforces it.
    tasks.named("check") {
        dependsOn(tasks.jacocoTestCoverageVerification)
    }
}

// Aggregate coverage report task
tasks.register<JacocoReport>("jacocoAggregateReport") {
    group = "verification"
    description = "Generates aggregate code coverage report for all subprojects"

    // Every module, derived from the build rather than listed. The previous hard-coded list of
    // four went stale the moment a module was added, and a coverage report that silently omits
    // most of the codebase is worse than none: it reports a number people trust.
    val covered = subprojects

    dependsOn(covered.map { it.tasks.named("test") })
    // Same reason as the verification task: classDirectories reads each module's compiled
    // output, and the test dependency alone does not survive `-x test`.
    dependsOn(covered.map { it.tasks.named("classes") })

    // Resolved lazily. The previous version called File.exists() while configuring, before any
    // test had run, so on a clean build every path was skipped and the report came out empty --
    // and empty renders as a valid report, not as an error.
    executionData.from(covered.map { project ->
        project.layout.buildDirectory.file("jacoco/test.exec")
    })
    sourceDirectories.from(covered.map { project ->
        project.layout.projectDirectory.dir("src/main/java")
    })
    // JMH writes a generated harness class per benchmark method. It is machine-written, it is
    // never unit-tested by design, and in this repo it is 58% of all bytecode -- including it
    // drags the aggregate from the high seventies to 35% and makes the number describe code
    // generation rather than the project.
    //
    // excalibur/bench/benchmark holds the hand-written half of the same harness: @Benchmark
    // bodies, @Setup fixtures and @State classes. They are established by being run -- a trial
    // builds hundred-thousand-vector indexes and multi-gigabyte mapped files over minutes --
    // and a unit test of one would either not run it or not be a unit test. What they used to
    // contain that is ordinary code (the scalar distance kernels, brute-force ground truth and
    // recall, false-positive accumulation, latency percentiles) now lives in
    // com.eonmux.excalibur.bench.kernel, which is counted here and tested directly. Only the measurement
    // loop is excluded, and only where it cannot be separated from the thing being measured.
    classDirectories.from(covered.map { project ->
        project.fileTree(project.layout.buildDirectory.dir("classes/java/main")) {
            exclude(
                "**/jmh_generated/**",
                "**/generated/**",
                "**/excalibur/bench/benchmark/**")
        }
    })

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
        html.outputLocation.set(
            rootProject.layout.buildDirectory.dir("reports/jacoco/aggregate/html"))
        xml.outputLocation.set(
            rootProject.layout.buildDirectory.file("reports/jacoco/aggregate/coverage.xml"))
    }
}

// Task to generate the custom HTML summary report
tasks.register<JavaExec>("generateCoverageSummary") {
    group = "verification"
    description = "Generates a consolidated HTML coverage summary report"

    dependsOn("jacocoAggregateReport")

    classpath = project(":excalibur-coverage").the<SourceSetContainer>()["main"].runtimeClasspath

    mainClass.set("com.eonmux.excalibur.coverage.CoverageReportGenerator")

    // The module list comes from the build, for the same reason the aggregate report's does: a
    // list compiled into the generator named four modules of twenty-one, so `./gradlew coverage`
    // ran every module's tests and then printed a summary describing a fifth of the repository.
    args(
        listOf(
            rootProject.projectDir.absolutePath,
            rootProject.layout.buildDirectory.file("reports/jacoco/COVERAGE_SUMMARY.html")
                .get().asFile.absolutePath
        ) + subprojects.map { it.name }.sorted()
    )
}

// Every module's tag-excluded gates, in one command.
//
// Derived from the build rather than listed, for the reason the coverage task below is: a list
// goes stale the moment a module is added, and a gate run that silently skips a module reports
// a pass over assertions it never executed.
tasks.register("benchmarkTest") {
    group = "verification"
    description = "Runs every module's slow and benchmark gate tests (takes minutes)"
    subprojects.map { it.name }.sorted().forEach { projectName ->
        dependsOn(":${projectName}:benchmarkTest")
    }
}

// Per-module reports plus the aggregate. The floors are enforced by `check` as well, so this
// task is for reading the numbers rather than for gating on them.
//
// Every module, derived from the build. This listed four of eighteen, so it printed "Coverage
// Report Generation Complete" over a run that had measured less than a quarter of the codebase
// -- the same defect the aggregate report above was already fixed for, left in the task people
// actually invoke.
tasks.register("coverage") {
    group = "verification"
    description = "Runs tests, generates coverage reports, and enforces each module's coverage floor"

    val targetProjects = subprojects.map { it.name }.sorted()

    targetProjects.forEach { projectName ->
        dependsOn(":${projectName}:test")
        dependsOn(":${projectName}:jacocoTestReport")
        // Enforce this module's floor -- 0.80 unless the coverageFloors map holds it lower.
        dependsOn(":${projectName}:jacocoTestCoverageVerification")
    }

    dependsOn("jacocoAggregateReport")
    dependsOn("generateCoverageSummary")

    doLast {
        println("\n" + "=".repeat(60))
        println("  Coverage Report Generation Complete!")
        println("=".repeat(60))
        println("\nReport locations:")
        println("  Summary:    ${rootProject.layout.buildDirectory.get()}/reports/jacoco/COVERAGE_SUMMARY.html")
        println("  Aggregate:  ${rootProject.layout.buildDirectory.get()}/reports/jacoco/aggregate/html/index.html")
        println("  XML:        ${rootProject.layout.buildDirectory.get()}/reports/jacoco/aggregate/coverage.xml")
        println("")

        targetProjects.forEach { projectName ->
            println("  $projectName:")
            println("    HTML: ${projectName}/build/reports/jacoco/test/html/index.html")
            println("    XML:  ${projectName}/build/reports/jacoco/test/jacocoTestReport.xml")
        }
    }
}

// Task to analyze coverage gaps and identify missing test areas.
//
// Across every module, for the same reason as `coverage` above: a gap report that only reads four
// modules' XML names the gaps in those four and stays silent about the rest, which reads as "the
// rest have none".
tasks.register<JavaExec>("coverageGaps") {
    group = "verification"
    description = "Analyzes coverage reports to identify missing test coverage areas"

    // Ensure tests have run and coverage reports exist
    subprojects.forEach { project ->
        dependsOn(":${project.name}:jacocoTestReport")
    }

    classpath = project(":excalibur-coverage").the<SourceSetContainer>()["main"].runtimeClasspath

    mainClass.set("com.eonmux.excalibur.coverage.CoverageGapAnalyzer")

    // Named rather than discovered, so a module whose tests have not run is reported as a gap
    // instead of quietly missing from the analysis.
    args(listOf(rootProject.projectDir.absolutePath) + subprojects.map { it.name }.sorted())

    doFirst {
        println("\n" + "=".repeat(70))
        println("  Analyzing Coverage Gaps...")
        println("=".repeat(70) + "\n")
    }
}
