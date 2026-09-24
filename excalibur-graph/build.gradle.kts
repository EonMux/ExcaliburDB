// excalibur-graph - mmap'd CSR adjacency alongside the vector index
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

    // Frontier sets during multi-hop expansion. A hop's frontier is a dense-ish set of int32
    // entity ids, which is exactly what Roaring compresses and iterates without boxing.
    api("org.roaringbitmap:RoaringBitmap:1.6.20")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    // A misconfiguration the build can only report, not refuse, is reported as a warning, and
    // the tests that pin it read the events logback captured.
    testImplementation("ch.qos.logback:logback-classic:1.5.12")
}
