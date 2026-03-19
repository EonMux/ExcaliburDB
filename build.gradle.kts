// Root build configuration
plugins {
    `java-library`
    jacoco
}

group = "io.excalibur"
version = "1.0.0-SNAPSHOT"

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

    repositories {
        mavenCentral()
    }

    dependencies {
        // Runtime dependencies
        runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
        runtimeOnly("ch.qos.logback:logback-classic:1.5.12")

        // Test dependencies
        testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.3")
        testImplementation("org.assertj:assertj-core:3.26.3")
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

        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }

    // JaCoCo coverage verification (80% minimum per project constraint)
    tasks.jacocoTestCoverageVerification {
        violationRules {
            rule {
                limit {
                    minimum = "0.80".toBigDecimal()
                }
            }
        }
    }
}

// Aggregate coverage report task
tasks.register<JacocoReport>("jacocoAggregateReport") {
    group = "verification"
    description = "Generates aggregate code coverage report for all subprojects"

    val targetProjects = listOf(
        "excalibur-core",
        "excalibur-store",
        "excalibur-filter",
        "excalibur-vector"
    )

    // Collect execution data from all target projects
    val executionFiles = files().apply {
        targetProjects.forEach { projectName ->
            val execFile = file("${projectName}/build/jacoco/test.exec")
            if (execFile.exists()) {
                from(execFile)
            }
        }
    }
    executionData(executionFiles)

    // Collect source directories from all target projects
    targetProjects.forEach { projectName ->
        val sourceDir = file("${projectName}/src/main/java")
        if (sourceDir.exists()) {
            sourceDirectories.from(sourceDir)
        }
        val classDir = file("${projectName}/build/classes/java/main")
        if (classDir.exists()) {
            classDirectories.from(classDir)
        }
    }

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
        html.outputLocation.set(file("${rootProject.buildDir}/reports/jacoco/aggregate/html"))
        xml.outputLocation.set(file("${rootProject.buildDir}/reports/jacoco/aggregate/coverage.xml"))
    }

    // Ensure tests run before generating aggregate report
    dependsOn(targetProjects.map { ":${it}:test" })
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
        "${rootProject.buildDir}/reports/jacoco/COVERAGE_SUMMARY.html"
    )

    // Run after core module is compiled
    dependsOn(":excalibur-core:classes")
}

// Combined coverage task that runs everything
tasks.register("coverage") {
    group = "verification"
    description = "Runs all tests and generates complete coverage reports (HTML + XML)"

    val targetProjects = listOf(
        "excalibur-core",
        "excalibur-store",
        "excalibur-filter",
        "excalibur-vector"
    )

    targetProjects.forEach { projectName ->
        dependsOn(":${projectName}:test")
        dependsOn(":${projectName}:jacocoTestReport")
    }

    dependsOn("jacocoAggregateReport")
    dependsOn("generateCoverageSummary")

    doLast {
        println("\n" + "=".repeat(60))
        println("  Coverage Report Generation Complete!")
        println("=".repeat(60))
        println("\nReport locations:")
        println("  Summary:    ${rootProject.buildDir}/reports/jacoco/COVERAGE_SUMMARY.html")
        println("  Aggregate:  ${rootProject.buildDir}/reports/jacoco/aggregate/html/index.html")
        println("  XML:        ${rootProject.buildDir}/reports/jacoco/aggregate/coverage.xml")
        println("")

        targetProjects.forEach { projectName ->
            println("  $projectName:")
            println("    HTML: ${projectName}/build/reports/jacoco/test/html/index.html")
            println("    XML:  ${projectName}/build/reports/jacoco/test/jacocoTestReport.xml")
        }
    }
}

// Task to analyze coverage gaps and identify missing test areas
tasks.register<JavaExec>("coverageGaps") {
    group = "verification"
    description = "Analyzes coverage reports to identify missing test coverage areas"

    val targetProjects = listOf(
        "excalibur-core",
        "excalibur-store",
        "excalibur-filter",
        "excalibur-vector"
    )

    // Ensure tests have run and coverage reports exist
    targetProjects.forEach { projectName ->
        dependsOn(":${projectName}:jacocoTestReport")
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
