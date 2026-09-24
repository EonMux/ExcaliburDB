// excalibur-llm - LLM Client
plugins {
    `java-library`
}


dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // Core primitives
    implementation(project(":excalibur-core"))

    // Configuration module for Config.Llm integration
    implementation(project(":excalibur-config"))
    testImplementation(project(":excalibur-config"))
}
