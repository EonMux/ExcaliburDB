// excalibur-core (Phase 1) - Core Primitives
plugins {
    `java-library`
    `maven-publish`
}

group = "io.excalibur"
version = "1.0.0-SNAPSHOT"

// Java 21 configuration
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // SLF4J for logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation(project(":excalibur-metrics"))
    implementation(project(":excalibur-config"))
    
    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.15.2")
    
    // JMH benchmarks
    testImplementation("org.openjdk.jmh:jmh-core:1.37")
    testRuntimeOnly("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

// Enable Panama FFM preview features
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Enable preview features for ExcaliburDB
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

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

// Publishing configuration
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            
            pom {
                name.set("ExcaliburDB Core")
                description.set("Core primitives for ExcaliburDB - Memory-mapped I/O, serialization, and utilities")
                url.set("https://github.com/aeslampanah/excaliburdb")
                
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                
                developers {
                    developer {
                        id.set("aeslampanah")
                        name.set("Amir Eslampanah")
                        email.set("amir.eslampanah@example.com")
                    }
                }
                
                scm {
                    url.set("https://github.com/aeslampanah/excaliburdb")
                    connection.set("scm:git:https://github.com/aeslampanah/excaliburdb.git")
                    developerConnection.set("scm:git:git@github.com:aeslampanah/excaliburdb.git")
                }
            }
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
            "Implementation-URL" to "https://github.com/aeslampanah/excaliburdb"
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
