// excalibur-api (Phase 9) - API Surface
plugins {
    `java`
}

group = "com.excaliburdb"
version = "1.0.0-SNAPSHOT"

dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // Phase 6, 7, 8 dependencies
    implementation(project(":excalibur-query"))
    implementation(project(":excalibur-llm"))
    implementation(project(":excalibur-ingest"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar {
    archiveBaseName.set("excalibur-api")
    archiveVersion.set(version.toString())
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
