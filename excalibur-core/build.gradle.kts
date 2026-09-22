// excalibur-core (Phase 1) - Core Primitives
plugins {
    `java-library`
}


// Java 21 configuration

repositories {
    mavenCentral()
}

dependencies {
    // SLF4J for logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    
    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    
    // JMH benchmarks
    testImplementation("org.openjdk.jmh:jmh-core:1.37")
    testRuntimeOnly("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

// Enable Panama FFM preview features (the toolchain itself is declared once, above)
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("--enable-preview")
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

// Build tasks
tasks.jar {
    archiveBaseName.set("excalibur-core")
    manifest {
        attributes(
            "Implementation-Title" to "ExcaliburDB Core",
            "Implementation-Version" to project.version,
            "Implementation-URL" to "https://github.com/EonMux/ExcaliburDB"
        )
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("slow")
    }
    jvmArgs(
        "--enable-preview",
        "--add-opens", "java.base/java.util.concurrent.atomic=ALL-UNNAMED"
    )
}
