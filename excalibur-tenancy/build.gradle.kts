// excalibur-tenancy - per-namespace index isolation, lifecycle and eviction
plugins {
    `java-library`
}



repositories {
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.16")
    api(project(":excalibur-core"))
    implementation(project(":excalibur-config"))

    // Test-only: the registry is generic, but proving it against a real mapped index is
    // the only way to know the mapping accounting matches what a store actually holds.
    testImplementation(project(":excalibur-graph"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}
