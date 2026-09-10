// Root build configuration
plugins {
    `java-library`
    jacoco
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
}

group = "io.excalibur"
version = "1.0.0-SNAPSHOT"

/** Stated once; every published POM's url and scm entries read from here. */
val PROJECT_URL = "https://github.com/aeslampanah/excaliburdb"

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
    // which matches no package in the source, so the same build published two different
    // coordinate namespaces depending on which module you asked for.
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
    // and excalibur-integration has no main sources to publish.
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
        "excalibur-api" to "HTTP API surface and server bootstrap"
    )

    publishedModules[project.name]?.let { moduleDescription ->
        apply(plugin = "maven-publish")

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])

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
                                "scm:git:git@github.com:aeslampanah/excaliburdb.git")
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

        // Same exclusion as the aggregate: JMH's generated harness classes are machine-written
        // and never unit-tested by design, so counting them measures code generation.
        classDirectories.setFrom(
            fileTree(layout.buildDirectory.dir("classes/java/main")) {
                exclude("**/jmh_generated/**", "**/generated/**")
            })

        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }

    // Coverage floors, enforced as part of `check`.
    //
    // The project target is 80%, and eleven modules meet it. The six below are held at their
    // measured level instead, so this is a ratchet rather than a lowered bar: coverage cannot
    // slide backwards, and each floor is a visible debt to be raised towards 0.80 rather than a
    // number chosen to make the build pass. Previously this rule declared 80% and was wired into
    // nothing, so it never ran and the figure was aspirational.
    //
    // Each floor sits about a point under the module's measured coverage. The headroom is for
    // the concurrency tests, whose branch mix varies slightly between runs; without it the
    // build fails intermittently for no reason anyone can act on.
    //
    // excalibur-bench is the outlier and will stay one. Most of what it contains is JMH state
    // classes, @Benchmark methods and runner mains, which exist to be executed by a benchmark
    // run lasting minutes to hours -- not by `check`. Its validators, report writers and result
    // parsing are ordinary code and are tested; the harness around them is deliberately not,
    // for the same reason jmh_generated is excluded below.
    //
    // Measured with generated code excluded; JMH's harness classes are machine-written and
    // counting them measures code generation rather than testing.
    val coverageFloors = mapOf(
        "excalibur-vector" to "0.79",
        "excalibur-test" to "0.78",
        "excalibur-api" to "0.74",
        "excalibur-shard" to "0.70",
        "excalibur-ingest" to "0.67",
        "excalibur-query" to "0.67",
        "excalibur-bench" to "0.38"
    )

    tasks.jacocoTestCoverageVerification {
        dependsOn(tasks.test)
        // classDirectories below reads compileJava's output. `dependsOn(test)` normally implies
        // that, but `./gradlew build -x test` severs the implication and Gradle then refuses the
        // whole build for an undeclared task dependency. That command is documented in the README
        // and in USAGE.md, and analyze-flaky-tests.sh runs it, so the dependency is declared
        // outright rather than left to be inferred from a task that may not run.
        dependsOn(tasks.named("classes"))

        // Skipping the tests skips the evidence. Verifying with no execution data reports 0% for
        // every module and fails the build for a reason that has nothing to do with the code, so
        // `-x test` degrades to "not verified" rather than to "failed".
        onlyIf {
            layout.buildDirectory.file("jacoco/test.exec").get().asFile.exists()
        }

        classDirectories.setFrom(
            fileTree(layout.buildDirectory.dir("classes/java/main")) {
                exclude("**/jmh_generated/**", "**/generated/**")
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
    classDirectories.from(covered.map { project ->
        project.fileTree(project.layout.buildDirectory.dir("classes/java/main")) {
            exclude("**/jmh_generated/**", "**/generated/**")
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

    classpath = files("excalibur-core/build/classes/java/main")
    classpath = classpath.plus(files("excalibur-core/build/resources/main"))

    mainClass.set("io.excalibur.core.report.CoverageReportGenerator")

    args(
        rootProject.projectDir.absolutePath,
        rootProject.layout.buildDirectory.file("reports/jacoco/COVERAGE_SUMMARY.html").get().asFile.absolutePath
    )

    // Run after core module is compiled
    dependsOn(":excalibur-core:classes")
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

    classpath = files("excalibur-core/build/classes/java/main")
    classpath = classpath.plus(files("excalibur-core/build/resources/main"))

    mainClass.set("io.excalibur.core.report.CoverageGapAnalyzer")

    args(rootProject.projectDir.absolutePath)

    // Run after core module is compiled
    dependsOn(":excalibur-core:classes")

    doFirst {
        println("\n" + "=".repeat(70))
        println("  Analyzing Coverage Gaps...")
        println("=".repeat(70) + "\n")
    }
}
