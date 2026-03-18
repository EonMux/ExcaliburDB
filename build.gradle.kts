// Root build configuration
plugins {
    `java-library`
}

group = "io.excalibur"
version = "1.0.0-SNAPSHOT"

// Java 21 configuration
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Common configuration for all submodules
subprojects {
    apply(plugin = "java-library")

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

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showCauses = true
            showStackTraces = true
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.add("--enable-preview")
    }

    // Enable Panama FFM preview features
    tasks.withType<JavaCompile> {
        options.compilerArgs.add("--enable-preview")
    }

    // Enable preview features for excalibur-test
    subprojects {
        if (name == "excalibur-test") {
            tasks.withType<JavaCompile> {
                options.compilerArgs.add("--enable-preview")
            }
        }
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
}
