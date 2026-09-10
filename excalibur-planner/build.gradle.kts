// excalibur-planner - deterministic query classification and coreference, outside the model
plugins {
    `java-library`
}



repositories {
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.16")
    api(project(":excalibur-core"))
    // Temporal plans carry a valid-time selector, which is defined once, in the graph module.
    api(project(":excalibur-graph"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}
