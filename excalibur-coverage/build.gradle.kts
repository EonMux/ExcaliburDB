// excalibur-coverage - the build's coverage summary and gap report.
//
// Build tooling, not a library: the root project's `generateCoverageSummary` and `coverageGaps`
// tasks run these classes against every module's JaCoCo XML. Nothing depends on this module and
// it is not published, so none of it reaches a jar a node or an embedder runs.
plugins {
    `java-library`
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
}
