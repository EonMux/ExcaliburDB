// excalibur-core (Phase 1) - Core Primitives
plugins {
    `java`
}

group = "com.excaliburdb"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

// Enable Panama FFM preview features
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("--enable-preview")
}

tasks.jar {
    archiveBaseName.set("excalibur-core")
    archiveVersion.set(version.toString())
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
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
