// excalibur-ocr - OCR of PDFs in process: a Java port of Indra OCR (see NOTICE)
plugins {
    `java-library`
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Runs Indra's ONNX models: line detection, recognition, reading order and layout. The
    // jar carries the native runtime for every platform it supports, so nothing is installed.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.31.0")
    // Renders a PDF's pages for OCR and reads its text layer, both in pure Java.
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    // Image codecs PDFBox finds through ImageIO. Without them a page's JBIG2 images - common in
    // scanned books and papers - render as nothing, and so does a JPEG Java's own reader
    // rejects (a misplaced JFIF marker, CMYK), which pdfium reads: OCR would see a blank page.
    runtimeOnly("org.apache.pdfbox:jbig2-imageio:3.0.5")
    runtimeOnly("com.twelvemonkeys.imageio:imageio-jpeg:3.15.3")
    // JPEG 2000 (JPXDecode), as scanned journals and books often hold. Licensed under the JJ2000
    // licence and the BSD 3-clause licence with a nuclear disclaimer, not Apache-2.0: see NOTICE.
    runtimeOnly("com.github.jai-imageio:jai-imageio-jpeg2000:1.4.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

// The port's attribution travels with its classes, into every jar built from them.
tasks.jar {
    metaInf {
        from("NOTICE") {
            rename { "NOTICE.excalibur-ocr" }
        }
    }
}

// The parity report compares this port with Indra OCR itself on a folder of PDFs, which the
// build does not have: it runs only when asked, as `parity -PparityDir=<dir>` (see
// ParityReport).
tasks.test {
    useJUnitPlatform {
        excludeTags("parity")
    }
}

tasks.register<Test>("parity") {
    group = "verification"
    description = "Compares this port's markdown with Indra OCR's on -PparityDir=<dir>"
    val testSourceSet = project.the<SourceSetContainer>()["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        excludeTags = LinkedHashSet()
        includeTags("parity")
    }
    systemProperty("excalibur.ocr.parityDir", providers.gradleProperty("parityDir").getOrElse(""))
    // Every core reads a page at once, each holding a rendered page and its crops.
    setJvmArgs(listOf("--enable-preview", "--enable-native-access=ALL-UNNAMED", "-Xmx8g"))
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }
}
