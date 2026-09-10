// excalibur-retrieval - hybrid ranking: lexical, dense, fusion and full-precision rerank
plugins {
    `java-library`
}



repositories {
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.16")
    api(project(":excalibur-core"))
    implementation(project(":excalibur-filter"))
    // Reuses the one definition of each distance metric, and the Vector API detection
    // with its scalar fallback, rather than adding a second cosine to the codebase.
    api(project(":excalibur-vector"))
    // A Graph-RAG answer is a subgraph, so the retrieval outcome is expressed in terms of one.
    api(project(":excalibur-graph"))
    // The plan tells retrieval what to do, so retrieval consumes planner rather than the
    // reverse. Keeps the dependency a DAG: planner -> graph, retrieval -> planner, graph.
    api(project(":excalibur-planner"))
    // Reuses the existing CircuitBreaker rather than writing a second one. excalibur-llm
    // depends only on core and config, so no HTTP client is pulled in by this.
    api(project(":excalibur-llm"))
    implementation(project(":excalibur-config"))

    // Inverted index and BM25. Lucene's scoring has two decades of correctness work behind it and
    // handles the parts that are easy to get subtly wrong -- length normalisation, tokenisation,
    // term statistics across segments. Reimplementing it would be a worse BM25, not a faster one.
    api("org.apache.lucene:lucene-core:10.5.1")

    // A real BPE tokenizer. The serialisation budget is a token budget, and a character or
    // triple count is not a token count -- the two diverge most on exactly the identifier-dense
    // text a knowledge graph produces, which is where overrunning the context costs the most.
    implementation("com.knuddels:jtokkit:1.1.0")
    implementation("org.apache.lucene:lucene-analysis-common:10.5.1")
    implementation("org.apache.lucene:lucene-queryparser:10.5.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}
