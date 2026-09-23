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
}
