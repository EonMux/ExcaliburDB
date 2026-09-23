package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.llm.client.CircuitBreaker;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.retrieval.build.GraphDirectoryBuilder;

import java.util.Objects;

/**
 * The model clients every graph a node serves reads and builds through: one chat client for the
 * rerank, one embedding client for the question, and one embedding client for the entity vectors
 * a build writes.
 *
 * <h2>Why one per node, not one per graph</h2>
 * <p>A circuit breaker describes an endpoint, and every graph on a node calls the same endpoint
 * with the same key: {@code excalibur.llm.*} is node configuration, not namespace configuration.
 * A client per opened graph gave that one endpoint a breaker per namespace, which is wrong in
 * three ways at once. A provider outage had to fail {@code failure-threshold} calls in every
 * namespace before the node stopped making them. Reopening a graph - after every build, every
 * erasure compaction and every eviction - replaced its breaker with a closed one, so a namespace
 * queried often enough to be evicted and reopened went on calling a dead provider. And there was
 * no single state to report: a breaker per namespace is a label per namespace, which is a metric
 * whose cardinality grows with the tenants. One client per node makes the reported state the state
 * every graph query is actually gated by, and bounds it at one series each.</p>
 *
 * <p>The build's embedding client is the node's for the same reason. It used to be made by each
 * tenant as it opened, which gave the build a breaker per namespace that a reopen reset to closed
 * and that nothing reported - so a build against a dead endpoint paid {@code failure-threshold}
 * failed batches in every namespace, again after every eviction, and published graphs without
 * vectors with nothing but a log line to say why.</p>
 *
 * <h2>Why the build and the question embed through different clients</h2>
 * <p>Both read the same {@code excalibur.llm.*} settings - one base URL, one key, one embedding
 * model - so one client would be defensible if a breaker only described the endpoint. It also
 * decides what stops, and the two stop different things at very different cost. A question
 * embedding is one short text per query, and an open breaker costs that query its exact vector
 * rerank and nothing after it. A build embedding is a bulk batch of entity documents, and an open
 * breaker costs the published graph its vectors until the next build. Sharing one breaker would
 * let a build's batches - the calls most likely to be rate-limited or refused for size - switch
 * off the vector rerank for every query on the node, and let a burst of failed questions strip
 * the vectors from a build that happened to be running. The node already draws the line this way
 * for the corpus: ingestion's embedding client and the graph question's are separate clients over
 * the same settings, each with its own reported breaker. So each role has its own, one per node,
 * reported under its own name.</p>
 *
 * <h2>Created when first needed</h2>
 * <p>Each client comes into existence when it is first needed, not when the node starts: the
 * rerank client with the first graph opened on a node that has a key, the
 * question-embedding client with the first graph built with entity vectors, and the build's with
 * the first build that embeds an entity. Until then the node has no such client, and
 * {@link #rerankBreaker()}, {@link #queryEmbeddingBreaker()} and {@link #buildEmbeddingBreaker()}
 * say so with null - the same statement every other optional breaker makes - rather than
 * reporting a closed breaker in front of calls nothing will make. Once created, a client stays
 * for the life of the node, because it is the node's and not the graph's that caused it.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use. Namespaces open on whichever request first names them, so two can
 * ask for a client at once; both get the same one.</p>
 */
public final class GraphModelClients {

    /** How to reach the model, or null on a node with no API key. */
    private final LLMClientConfig clientConfig;

    private final Object creation = new Object();
    private volatile LLMHttpClient rerankClient;
    private volatile EmbeddingClient queryEmbeddingClient;
    private volatile EmbeddingClient buildEmbeddingClient;

    private GraphModelClients(LLMClientConfig clientConfig) {
        this.clientConfig = clientConfig;
    }

    /**
     * The clients a node with this configuration shares between its graphs.
     *
     * @param config Configuration, read for the LLM settings
     * @return The shared clients; ones that will never create anything on a node without a key
     */
    public static GraphModelClients forConfig(Config config) {
        Objects.requireNonNull(config, "config cannot be null");
        String apiKey = config.llm().getApiKey();
        return new GraphModelClients(apiKey == null || apiKey.isBlank()
            ? null
            : LLMClientConfig.fromConfig(config.llm()));
    }

    /**
     * The chat client graph retrieval reranks through, created on first call.
     *
     * @return The client, or null on a node with no API key
     */
    LLMHttpClient rerankClient() {
        if (clientConfig == null) {
            return null;
        }
        LLMHttpClient client = rerankClient;
        if (client == null) {
            synchronized (creation) {
                client = rerankClient;
                if (client == null) {
                    client = new LLMHttpClient(clientConfig);
                    rerankClient = client;
                }
            }
        }
        return client;
    }

    /**
     * The embedding client graph retrieval embeds a question through, created on first call.
     *
     * @return The client, or null on a node with no API key
     */
    EmbeddingClient queryEmbeddingClient() {
        if (clientConfig == null) {
            return null;
        }
        EmbeddingClient client = queryEmbeddingClient;
        if (client == null) {
            synchronized (creation) {
                client = queryEmbeddingClient;
                if (client == null) {
                    client = new EmbeddingClient(clientConfig);
                    queryEmbeddingClient = client;
                }
            }
        }
        return client;
    }

    /**
     * The embedding client graph builds embed entity documents through, created on first call.
     *
     * @return The client, or null on a node with no API key
     */
    EmbeddingClient buildEmbeddingClient() {
        if (clientConfig == null) {
            return null;
        }
        EmbeddingClient client = buildEmbeddingClient;
        if (client == null) {
            synchronized (creation) {
                client = buildEmbeddingClient;
                if (client == null) {
                    client = new EmbeddingClient(clientConfig);
                    buildEmbeddingClient = client;
                }
            }
        }
        return client;
    }

    /**
     * Embeds entity documents for a build, through the node's build embedding client.
     *
     * <p>The client is looked up on each batch rather than when the embedder is handed out, so
     * a tenant can hold an embedder from the moment it opens without that bringing a client -
     * and a breaker to report - into existence before any build has embedded anything.</p>
     *
     * @return The embedder, or null on a node with no API key
     */
    GraphDirectoryBuilder.EntityEmbedder entityEmbedder() {
        if (clientConfig == null) {
            return null;
        }
        return texts -> buildEmbeddingClient().embedBatch(texts);
    }

    /**
     * The breaker in front of the graph rerank, without creating the client.
     *
     * <p>When it is open, graph retrieval keeps the order the stage before the model gave it -
     * the exact vector rerank where the graph has entity vectors, BM25 where it does not - and
     * the route still answers 200.</p>
     *
     * @return The breaker, or null where no graph on this node has reranked through a model
     */
    public CircuitBreaker rerankBreaker() {
        LLMHttpClient client = rerankClient;
        return client == null ? null : client.getCircuitBreaker();
    }

    /**
     * The breaker in front of the question embedding, without creating the client.
     *
     * <p>When it is open, a question carries no embedding and graph retrieval skips the exact
     * vector rerank, ranking by BM25 (and the model rerank, if that breaker is closed).</p>
     *
     * @return The breaker, or null where no graph on this node has entity vectors to rerank
     *         against, or the node has no API key
     */
    public CircuitBreaker queryEmbeddingBreaker() {
        EmbeddingClient client = queryEmbeddingClient;
        return client == null ? null : client.getCircuitBreaker();
    }

    /**
     * The breaker in front of a build's entity embedding, without creating the client.
     *
     * <p>When it is open, a build publishes the entities it could not embed with no vector:
     * they stay traversable and lexically ranked, and a question about them skips the exact
     * vector rerank until a later build embeds them.</p>
     *
     * @return The breaker, or null where no build on this node has embedded an entity, or the
     *         node has no API key
     */
    public CircuitBreaker buildEmbeddingBreaker() {
        EmbeddingClient client = buildEmbeddingClient;
        return client == null ? null : client.getCircuitBreaker();
    }

    @Override
    public String toString() {
        return "GraphModelClients[" + (clientConfig == null ? "no model" : "rerank="
            + (rerankClient == null ? "unused" : rerankClient.getCircuitBreaker().getState())
            + ", queryEmbedding="
            + (queryEmbeddingClient == null
                ? "unused" : queryEmbeddingClient.getCircuitBreaker().getState())
            + ", buildEmbedding="
            + (buildEmbeddingClient == null
                ? "unused" : buildEmbeddingClient.getCircuitBreaker().getState()))
            + "]";
    }
}
