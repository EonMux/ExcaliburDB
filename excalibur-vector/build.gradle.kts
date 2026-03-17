// excalibur-vector (Phase 4) - Vector Index
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

    // Phase 1 dependency
    implementation(project(":excalibur-core"))
    // Phase 3 dependency
    implementation(project(":excalibur-filter"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar {
    archiveBaseName.set("excalibur-vector")
    archiveVersion.set(version.toString())
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    jvmArgs("--enable-preview")
}
