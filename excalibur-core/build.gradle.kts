// excalibur-core (Phase 1) - Core Primitives
plugins {
    `java-library`
    `maven-publish`
}


// Java 21 configuration

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
                        // Matches the LICENSE file at the repository root. The two are the
                        // project's licence stated twice; they must not be allowed to drift.
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                
                developers {
                    developer {
                        // No email: the field is optional, and the address here was
                        // "amir.eslampanah@example.com", which is a reserved example domain and
                        // so was never reachable by anyone who read it off a published artifact.
                        id.set("aeslampanah")
                        name.set("Amir Eslampanah")
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
