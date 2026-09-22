// Deliberately a leaf. Every module's test runtime depends on this one, so anything it pulls
// in lands on all twenty test classpaths; excalibur-core is the only dependency it needs, and
// the launcher SPI is compile-only because a JUnit run supplies its own.
dependencies {
    api(project(":excalibur-core"))
    compileOnly("org.junit.platform:junit-platform-launcher:1.11.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.junit.platform:junit-platform-launcher:1.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}
