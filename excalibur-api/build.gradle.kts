// excalibur-api (Phase 9) - API Surface
plugins {
    `java-library`
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.excaliburdb"
version = "1.0.0-SNAPSHOT"

// Application plugin configuration
application {
    mainClass.set("excalibur.api.ExcaliburApplication")
}

dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")

    // Phase 2, 5, 6, 7, 8 dependencies
    implementation(project(":excalibur-store"))
    implementation(project(":excalibur-shard"))
    implementation(project(":excalibur-query"))
    implementation(project(":excalibur-llm"))
    implementation(project(":excalibur-ingest"))
    implementation(project(":excalibur-metrics"))
    implementation(project(":excalibur-config"))
    implementation(project(":excalibur-vector"))
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

// Shadow JAR configuration for fat JAR packaging
tasks.shadowJar {
    archiveBaseName.set("excalibur")
    archiveVersion.set("")
    archiveClassifier.set("")

    // Merge service files
    mergeServiceFiles()

    // Exclude unnecessary files
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")

    // Enable preview features for Java 21
    manifest {
        attributes["Main-Class"] = "excalibur.api.ExcaliburApplication"
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
}

// Make the build task depend on shadowJar
tasks.build {
    dependsOn(tasks.shadowJar)
}

// Run configuration for development
tasks.withType<JavaExec> {
    jvmArgs(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED"
    )
}
