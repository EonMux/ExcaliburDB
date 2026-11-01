// excalibur-ingest (Phase 8) - Ingestion Pipeline
plugins {
    `java-library`
}


dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // Core primitives: chunk identifiers, payload codec, hashing, stop signals
    implementation(project(":excalibur-core"))

    // A registration names the namespace its documents belong to, and NamespaceId is what makes
    // that name safe to turn into a directory. api rather than implementation: the namespace is
    // part of SourceDescriptor's public shape, so anything that reads a descriptor needs the
    // type on its compile classpath.
    api(project(":excalibur-tenancy"))

    // Phase 2, 3, 4, 5, 7 dependencies
    implementation(project(":excalibur-store"))
    implementation(project(":excalibur-filter"))
    // api rather than implementation: ChunkLexicalIndex.open takes a CompactionPolicy and
    // RelationExtractor deals in ExtractedTriple, both of which are graph types on the public
    // shape of this module, so a consumer of either needs them on its compile classpath.
    api(project(":excalibur-graph"))
    implementation(project(":excalibur-vector"))
    implementation(project(":excalibur-metrics"))
    implementation(project(":excalibur-shard"))
    implementation(project(":excalibur-llm"))
    // Lucene BM25 over ingested chunks. Retrieval owns the Lucene wrapper; ingestion
    // writes into it as documents are stored.
    api(project(":excalibur-retrieval"))
    // HTML and XHTML documents are indexed by the text they show. Lucene's HTML char filter
    // parses markup to the specification, drops script and style bodies and decodes character
    // references, which a pattern over the raw page does not.
    implementation("org.apache.lucene:lucene-analysis-common:10.5.1")
    // The built-in PDF reader: a PDF's text layer, read in process. Pure Java, so a node needs
    // nothing installed to index a PDF that already carries its text.
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    // OCR of scanned pages, in process: the Java port of Indra OCR
    // (excalibur.ingest.pdf-reader=indra). api rather than implementation: IndraPdfReader is
    // built from an IndraOcr, so a caller that wires one needs the type.
    api(project(":excalibur-ocr"))
}
