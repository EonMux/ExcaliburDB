plugins {
    `java-library`
}



repositories {
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Exposed in public signatures (TestAssertionMessages, QueryResult.fromQueryResponse),
    // so consumers need it on their compile classpath.
    api(project(":excalibur-query"))

    // JUnit Platform API for FailureReportingExtension (exposed via ExtensionContext in
    // the extension's public callbacks, so it leaks into consumers' compile classpath)
    api("org.junit.jupiter:junit-jupiter-api:5.11.3")
    implementation("org.junit.platform:junit-platform-commons:1.11.3")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
    
    // JMH benchmarks
    testImplementation("org.openjdk.jmh:jmh-core:1.37")
    testRuntimeOnly("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    
    // JSON for test data
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.1")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.1")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

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
