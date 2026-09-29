package com.eonmux.excalibur.api.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.eonmux.excalibur.graph.browse.EdgeDirection;
import com.eonmux.excalibur.retrieval.pipeline.TraversalDirection;
import com.eonmux.excalibur.tenancy.NamespaceId;

/**
 * What a model can do with an ExcaliburDB node, and how each of those maps onto its HTTP API.
 *
 * <h2>The loop, and why it has to close</h2>
 * <p>Point the node at documents - a directory, a website, or text held in the call itself -
 * watch it index them, search them, turn what was extracted into a graph, then ask the graph
 * questions. A model handed only the last of those can query a corpus somebody else prepared;
 * handed all of them it can prepare one, which is the difference between a retrieval endpoint
 * and something an agent can actually be pointed at.</p>
 *
 * <p>Preparing one means being able to get it wrong and put it right. A registration is a
 * standing decision - the source is crawled now and re-crawled on a schedule for as long as it
 * stays registered - and until a model could undo one, a source registered against the wrong
 * root was permanent: re-registering the id is refused with 409, {@link #ADD_DOCUMENTS} refuses
 * an id a crawl holds, and nothing here removed it. {@link #DEREGISTER_SOURCE} is the way back
 * and {@link #REINDEX_SOURCE} is the way forward, and without both the ingestion half of this
 * catalogue is a one-way door.</p>
 *
 * <h2>Why both searches</h2>
 * <p>{@link #SEARCH_DOCUMENTS} and {@link #SEARCH_GRAPH} answer different questions and neither
 * subsumes the other. The first names the chunks that match and needs nothing but an
 * ingestion run; the second returns entities and the facts connecting them, and needs a graph to
 * have been built out of extracted triples - which requires a chat model and
 * {@code excalibur.graph.extraction-enabled}, off by default. A tool set with only the graph
 * search leaves a model on an ordinary node with no way to reach the corpus at all.</p>
 *
 * <h2>The destructive tools, and what guards them</h2>
 * <p>Two tools destroy data: {@link #ERASE_DOCUMENTS}, over {@code POST /v1/erasure}, and
 * {@link #DELETE_NAMESPACE}, over {@code DELETE /v1/graph/namespaces/{namespace}}. They are the
 * calls whose cost of being made wrongly is not bounded by retrying, and they are here anyway
 * because an agent operating a corpus on somebody's behalf is exactly who will be asked to forget
 * a document or offboard a tenant - and one that cannot has to hand the request to a person with
 * a shell, which is no safer, only slower.</p>
 *
 * <p>What stands between a model and an accidental erasure is the same on both. Each requires a
 * {@code confirm} argument that repeats, exactly, what is about to be destroyed - the
 * {@code source_id}, or the namespace - checked here before anything is sent, so a model that
 * reached for the wrong tool with the right identifier, or filled the identifier in from a
 * default, is refused rather than obeyed. Neither falls back on the configured default namespace,
 * and each description says the call is irreversible and is to be made only when the user has
 * explicitly asked for it. No other tool takes a confirmation, so its presence is itself the
 * signal that a call destroys something.</p>
 *
 * <p>{@code POST /v1/graph/erasure} is still not a tool. It erases a person from the graph by
 * name or entity id, which means choosing among people who share a name - a judgement a model
 * would make on the evidence of the graph it is erasing from. Erasing a document already
 * withdraws every fact extracted from it, which is the erasure a model is asked for.</p>
 *
 * <p>Nor is retracting an asserted fact. {@link #ASSERT_GRAPH_FACTS} is here because stating a
 * fact is additive and, until a build, invisible; {@code DELETE /v1/graph/facts} is not, because
 * a retraction is recorded as a graph erasure - irreversible once compacted - and matching by
 * subject, relation and object retracts every asserted statement of that triple, including ones a
 * person made. It stays with the erasure routes.</p>
 *
 * <p>{@link #DEREGISTER_SOURCE} and the {@code cancel_*} tools destroy nothing. Deregistering
 * keeps every document the source contributed, and a cancelled run keeps what it had already
 * done, so none of them needs a confirmation; their descriptions say what they keep, because a
 * model that thinks a call erases will not reach for it and one that thinks it changes nothing
 * will reach for it too readily.</p>
 *
 * <h2>Descriptions are the interface</h2>
 * <p>A tool's description is not documentation, it is the only thing the model reads before
 * deciding whether to call it. The ones here say what the call returns, what has to have happened
 * first, and - for the graph query - that declining to answer is a result and not a failure, which
 * is the distinction a model will otherwise paper over by guessing.</p>
 *
 * <p>Which is why a wrong one is a defect and not a typo. This catalogue told a model that
 * reusing a {@code source_id} re-indexes the same source; it does not, it is answered 409, and
 * a model acting on that would retry the one call guaranteed to fail and never find the one
 * that works.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable over a thread-safe client.</p>
 *
 * @author ExcaliburDB
 */
public final class McpTools {

    /** Search the indexed documents. */
    public static final String SEARCH_DOCUMENTS = "search_documents";

    /** Ask the graph a question. */
    public static final String SEARCH_GRAPH = "search_graph";

    /** Publish the staged facts as the served graph. */
    public static final String BUILD_GRAPH = "build_graph";

    /** Report what the graph build is doing, and what the last one did. */
    public static final String GRAPH_BUILD_STATUS = "graph_build_status";

    /** Register a directory and start indexing it. */
    public static final String INGEST_DOCUMENTS = "ingest_documents";

    /** Index documents supplied in the call itself. */
    public static final String ADD_DOCUMENTS = "add_documents";

    /** Report indexing progress. */
    public static final String INGESTION_STATUS = "ingestion_status";

    /** Register a website and start crawling it. */
    public static final String CRAWL_WEBSITE = "crawl_website";

    /** Stop crawling a source, keeping the documents it indexed. */
    public static final String DEREGISTER_SOURCE = "deregister_source";

    /** Re-crawl a registered source so the index follows what changed at it. */
    public static final String REINDEX_SOURCE = "reindex_source";

    /** Report how a re-crawl is going. */
    public static final String REINDEX_STATUS = "reindex_status";

    /** Erase documents from the corpus and the graph. Destructive. */
    public static final String ERASE_DOCUMENTS = "erase_documents";

    /** List the documents the node holds, a page at a time. */
    public static final String LIST_DOCUMENTS = "list_documents";

    /** Read the text of chunks by their identifiers. */
    public static final String FETCH_DOCUMENTS = "fetch_documents";

    /** List the graph namespaces the node holds, a page at a time. */
    public static final String LIST_NAMESPACES = "list_namespaces";

    /** Create a graph namespace. */
    public static final String CREATE_NAMESPACE = "create_namespace";

    /** Remove a graph namespace entirely. Destructive. */
    public static final String DELETE_NAMESPACE = "delete_namespace";

    /** List the queries in flight. */
    public static final String LIST_QUERIES = "list_queries";

    /** Cancel a query in flight. */
    public static final String CANCEL_QUERY = "cancel_query";

    /** Stop a source's ingestion run, keeping the source registered. */
    public static final String CANCEL_INGESTION = "cancel_ingestion";

    /** Stop a source's re-crawl. */
    public static final String CANCEL_REINDEX = "cancel_reindex";

    /** Stop the compaction in progress. */
    public static final String CANCEL_COMPACTION = "cancel_compaction";

    /** Reclaim the space erased and replaced documents still hold. */
    public static final String COMPACT = "compact";

    /** Read back how the most recent compaction went. */
    public static final String COMPACTION_STATUS = "compaction_status";

    /** Read back what each source was registered as. */
    public static final String LIST_SOURCES = "list_sources";

    /** Stop the graph build in progress. */
    public static final String CANCEL_GRAPH_BUILD = "cancel_graph_build";

    /** Read one document's chunks, in the order it was chunked. */
    public static final String READ_DOCUMENT = "read_document";

    /** Read back where an erasure stands. */
    public static final String ERASURE_STATUS = "erasure_status";

    /** Read which build of the node is answering and its own counters. */
    public static final String NODE_STATUS = "node_status";

    /** The argument a destructive tool requires, repeating what it destroys. */
    static final String CONFIRM = "confirm";

    /**
     * What an id the node issued looks like - a query's, or an erasure's receipt. Both are UUIDs,
     * and each goes into a path, so nothing that could name another path segment is let through.
     */
    private static final Pattern ISSUED_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    /** Find entities in the served graph by name, and learn their ids. */
    public static final String FIND_GRAPH_ENTITIES = "find_graph_entities";

    /** One entity of the served graph, with its facts counted by relation. */
    public static final String GRAPH_ENTITY = "graph_entity";

    /** List the facts the served graph holds about one entity. */
    public static final String GRAPH_ENTITY_EDGES = "graph_entity_edges";

    /** List the relations the served graph's facts use, with how many use each. */
    public static final String LIST_GRAPH_RELATIONS = "list_graph_relations";

    /** State facts for the graph to hold after its next build. */
    public static final String ASSERT_GRAPH_FACTS = "assert_graph_facts";

    private final ExcaliburClient client;

    /**
     * Creates the tool set over a node.
     *
     * @param client The node to call
     */
    public McpTools(ExcaliburClient client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    /**
     * The catalogue, as MCP {@code tools/list} returns it.
     *
     * @return One descriptor per tool, each with its name, description, input schema and the
     *         behaviour hints {@link ToolHints} declares for it
     */
    public static List<Object> catalogue() {
        List<Object> tools = new ArrayList<>();

        tools.add(ToolSchema.tool(SEARCH_DOCUMENTS,
            "Search the documents this node has indexed and return the matching passages, best "
            + "first. Each result carries the passage in 'text', its 'document_id', and a score; "
            + "'text' is null when the node no longer holds that chunk, and 'text_truncated' is "
            + "true when the passage was clipped to the node's per-result limit - quote such a "
            + "result as a fragment rather than as the whole passage. This is the plain "
            + "retrieval call: it needs only that "
            + "ingest_documents has run, and it does not require build_graph. Prefer it when the question is answered "
            + "by the text of a document; prefer search_graph when the question is about how "
            + "entities relate to each other. Read 'coverage' in the reply: it says what fraction "
            + "of the index was actually searched, and a value below 1 means segments were still "
            + "indexing or the deadline cut the search short, so the result may be incomplete. "
            + "'timed_out' and 'cancelled' say which. Use the order of the results, not the "
            + "'score' on them: 'retrieval' says which signals answered - 'vector' scores are "
            + "similarities, 'lexical' and 'hybrid' scores are reciprocal-rank sums near zero - "
            + "and when 'llm_scored' is true a relevancy model has reordered the results, and a "
            + "score is its judgement between 0 and 1 for each result it judged. These share no "
            + "scale, so a score is comparable only with others from the same reply.",
            ToolSchema.object(
                ToolSchema.required("query", "string",
                    "What to search for, in natural language. The node matches it by meaning "
                    + "when it has an embedding model, by keyword (BM25) when it has a lexical "
                    + "index, and fuses the two when it has both; 'retrieval' in the reply says "
                    + "which answered."),
                ToolSchema.optional("top_k", "integer",
                    "How many passages to return. Omit for the server's default.", null),
                ToolSchema.optional("offset", "integer",
                    "How many results to skip before this page, for the next page of the same "
                    + "search: pass the reply's page.next_offset, which is null when there is "
                    + "no further page. offset plus top_k may not exceed 1000. Omit for the "
                    + "first page.", null),
                ToolSchema.optional("timeout_ms", "integer",
                    "Longest the search may take, in milliseconds. A search that runs out "
                    + "returns what it found so far with 'timed_out' true and 'coverage' below "
                    + "1, rather than failing. The node caps it (60000 by default) and refuses "
                    + "more. Omit for the server's default.", null),
                ToolSchema.optional("snippet", "boolean",
                    "Return the part of each passage the query matched, with 'highlights' "
                    + "marking the matched terms, instead of the passage from its start. Useful "
                    + "when passages are long and the match is deep inside them.", null),
                ToolSchema.optional("snippet_chars", "integer",
                    "Width of each snippet in characters, from 16 up to the server's per-result "
                    + "limit. Only with snippet true. Omit for the server's default.", null),
                ToolSchema.optional("explain", "boolean",
                    "Say why each passage was returned: its 'explain' gives every signal that "
                    + "found it with that signal's own rank and score, and the query terms the "
                    + "passage contains; the reply's 'query_expansion' says whether the query "
                    + "was widened. Useful for judging a surprising result, or why an expected "
                    + "one is missing.", null),
                ToolSchema.optional("threshold", "number",
                    "Lowest meaning-similarity, from 0 to 1, a passage may have and still be "
                    + "returned. It applies to the meaning signal only, before any fusion, so "
                    + "it does not filter keyword-only matches and is not on the scale of a "
                    + "'hybrid' score. Omit for the server's configured floor.", null),
                ToolSchema.optional("llm_threshold", "number",
                    "Lowest relevance, from 0 to 1, a passage may be judged to have by the "
                    + "node's relevancy model and still be returned. It applies only when the "
                    + "reply says 'llm_scored' true; a node with no relevancy model names it in "
                    + "'ignored_fields'. Omit for the server's configured bar.", null),
                ToolSchema.optional("source_ids", "array",
                    "Search only the documents of these sources, by the 'source_id' they "
                    + "were ingested under, as " + INGESTION_STATUS + " and " + LIST_DOCUMENTS
                    + " report it; at least one when given. A source named here "
                    + "that the node does not hold contributes nothing. Omit to search every "
                    + "source.", "string"))));

        tools.add(ToolSchema.tool(SEARCH_GRAPH,
            "Answer a question from the knowledge graph built out of the ingested documents. "
            + "Returns entities and facts to ground an answer on, not prose. Read the 'status' "
            + "field: 'answered' carries the evidence in 'context' along with a confidence score, "
            + "and for a 'how many', 'first' or 'latest' question an 'aggregate' object with the "
            + "value the graph computed - use that number rather than counting the facts "
            + "yourself; 'needs_input' means the question did not say which entity it meant - a "
            + "pronoun with nothing to refer to, or a name several entities share - and carries "
            + "a 'question' to put to the user and the 'candidates' to pass back as "
            + "context_entities with the same query; 'declined' means the graph does not hold "
            + "enough to answer, which is a result and not an error - report that rather than "
            + "guessing. Requires build_graph to have been run at least once.",
            ToolSchema.object(
                ToolSchema.required("query", "string",
                    "The question, in natural language."),
                ContextEntities.property(),
                ToolSchema.optional("as_of", "string",
                    "Answer as the facts stood at this point in valid time: an ISO-8601 date "
                    + "('2019-03-01'), an ISO-8601 instant ('2019-03-01T14:00:00Z'), or 'all' "
                    + "for the whole history including facts that have since been superseded. "
                    + "Omit to answer from what holds now, or from a date mentioned in the "
                    + "question itself.", null),
                ToolSchema.optionalChoice("direction",
                    "Which way along a fact to read from the entities the question names: "
                    + "'forward' for the facts they are the subject of (what a company "
                    + "acquired), 'reverse' for the facts they are the object of (who acquired "
                    + "it), 'both' for either. Omit for both, which is right unless the "
                    + "direction of the relation is the point of the question.",
                    directionNames()),
                ToolSchema.optional("timeout_ms", "integer",
                    "Longest the question may take, in milliseconds. A question that runs out "
                    + "is judged on what was gathered by then and answered with 'timed_out' "
                    + "true, so an 'answered' or 'declined' reply with it set may be missing what "
                    + "more time would have found. The node caps it (60000 by default) and "
                    + "refuses more. Omit for the server's default.", null),
                ToolSchema.optional("namespace", "string",
                    "Namespace to query. Omit for the configured default, or the shared one.",
                    null))));

        tools.add(ToolSchema.tool(BUILD_GRAPH,
            "Turn the facts ingestion has extracted into the graph that search_graph reads. The "
            + "server does this by itself once ingestion has been quiet for a while (a minute by "
            + "default); call this to publish what was ingested now. Until a build has run, "
            + "search_graph reports that no graph is being served. This call only starts the "
            + "build and returns immediately - on a real corpus the build takes minutes - so "
            + "poll " + GRAPH_BUILD_STATUS + " until its state is no longer 'running' to learn "
            + "what it published. Only one build runs at a time; starting a second while one is "
            + "in progress is refused.",
            ToolSchema.object(
                ToolSchema.optional("namespace", "string",
                    "Namespace to build. Omit for the configured default, or the shared one. "
                    + "A namespace holds the facts extracted from the sources registered in "
                    + "it, so build the one you ingested into.", null))));

        tools.add(ToolSchema.tool(GRAPH_BUILD_STATUS,
            "What the graph build is doing, and what the last one did. 'state' is 'idle', "
            + "'running', 'completed', 'cancelled' or 'failed'. A completed build reports how "
            + "many edges, entities and relations were published, and 'staged_facts': when that "
            + "is 0 nothing was extracted, which usually means ingestion has not finished or the "
            + "server has extraction disabled. 'withheld_facts' counts staged facts left out "
            + "because an erasure is still owed for them; they are never published, and that is "
            + "not an error. These describe the last build " + BUILD_GRAPH + " started; a "
            + "cancelled build published nothing and the namespace is still serving the graph "
            + "it had. 'freshness.behind_staging' says whether ingested changes are not yet in "
            + "the graph search_graph reads, and 'automatic.state' what the server is doing "
            + "about it by itself: 'current'; 'pending' until it rebuilds; 'running'; "
            + "'backing_off' after a failed build, or erasures overtaking several in a row, and "
            + "'deferred' after " + CANCEL_GRAPH_BUILD + " stopped an automatic build - each "
            + "rebuilds by itself at 'automatic.next_attempt_at_ms'; 'withheld' until an "
            + "interrupted erasure has been applied; or 'paused' or 'disabled' when it will not "
            + "without " + BUILD_GRAPH + ". 'automatic.last_result' "
            + "is how the last automatic build ended: 'completed', 'unchanged', 'failed', "
            + "'cancelled' when " + CANCEL_GRAPH_BUILD + " or a shutdown stopped it, or "
            + "'superseded' when an erasure arrived while it ran - nothing failed, and the server "
            + "builds again without the erased facts, so there is no need to call "
            + BUILD_GRAPH + ".",
            ToolSchema.object(
                ToolSchema.optional("namespace", "string",
                    "Namespace whose build to report on. Omit for the configured default, or "
                    + "the shared one.", null))));

        tools.add(ToolSchema.tool(INGEST_DOCUMENTS,
            "Register a local directory as a source and start indexing the documents in it. "
            + "Returns as soon as the source is registered; indexing continues in the background, "
            + "so poll ingestion_status until its state is 'idle' before calling build_graph. The "
            + "directory must be inside one of the server's configured allowed roots, or the "
            + "request is refused.",
            ToolSchema.object(
                ToolSchema.required("source_id", "string",
                    "A stable name for this source. It must not already be taken: registering "
                    + "an id that is already a registered source is refused with 409, and "
                    + "add_documents refuses one a crawl holds. To pick up changes at the "
                    + "directory afterwards call " + REINDEX_SOURCE + " with this id; to point "
                    + "the same id somewhere else call " + DEREGISTER_SOURCE + " first and then "
                    + "register it again."),
                ToolSchema.required("root_path", "string",
                    "Absolute path of the directory to index."),
                ToolSchema.optional("included_extensions", "array",
                    "File extensions to index, e.g. ['.md', '.txt']. Omit, or send an empty "
                    + "list, to read every file under the root.", "string"),
                ToolSchema.optional("excluded_directories", "array",
                    "Directory names to skip anywhere under the root, e.g. ['node_modules', "
                    + "'target']. Omit to keep the server's default skip list; an empty list "
                    + "clears it, so every directory is walked.", "string"),
                ToolSchema.optional("namespace", "string",
                    "Namespace whose graph this source's extracted facts are staged into, for "
                    + BUILD_GRAPH + " to build from. Fixed at registration. Omit for the "
                    + "configured default, or the shared one.", null),
                ToolSchema.optional("max_depth", "integer",
                    "How many directory levels below the root to descend. Omit for the "
                    + "server's default.", null),
                ToolSchema.optional("follow_symlinks", "boolean",
                    "Whether to descend through symbolic links. A link that leads outside the "
                    + "root is still not followed: where it really points is what is checked. "
                    + "Omit for the server's default, which does not follow them.", null),
                ToolSchema.optional("include_hidden", "boolean",
                    "Whether to index hidden files - those whose name starts with a dot, or "
                    + "that the operating system marks hidden - and descend into hidden "
                    + "directories below the root. Omit for the server's default, which skips "
                    + "both.", null),
                ToolSchema.optional("max_file_bytes", "integer",
                    "Largest single file to index, in bytes, from 1 to 104857600 (100 MiB), "
                    + "the largest document the server indexes. A larger file is never read, so "
                    + "never truncated: it counts as a failed document, and one that grew past "
                    + "the limit keeps the version indexed before. Omit for the server's "
                    + "default, which is that limit.", null),
                ToolSchema.optional("charset", "string",
                    "The encoding the files are written in, for a directory that is not UTF-8, "
                    + "e.g. 'windows-1252', 'ISO-8859-1', 'UTF-16LE'. Applies only to a file "
                    + "with no byte-order mark: a file that starts with one is read in the "
                    + "encoding the mark names, whatever is declared here. The server refuses "
                    + "an encoding it does not know rather than falling back to UTF-8. Omit "
                    + "for UTF-8.", null))));

        tools.add(ToolSchema.tool(ADD_DOCUMENTS,
            "Index documents by sending their text directly, for content you are holding rather "
            + "than a directory on the server. Prefer this over ingest_documents unless the "
            + "content is already in a directory the server is configured to read: a server with "
            + "no allowed roots refuses ingest_documents outright, and this call needs none. The "
            + "documents are indexed before the call returns, so they are searchable immediately "
            + "and there is no status to poll - unlike ingest_documents, which only starts a "
            + "crawl. Sending a 'document_id' that was sent before replaces what is indexed "
            + "under it. Use a 'source_id' that no crawl is using; one that is already a "
            + "registered crawl source is refused, because the next crawl of it would delete "
            + "these documents.",
            ToolSchema.object(
                ToolSchema.required("source_id", "string",
                    "A stable name for this batch's documents, used later to erase or re-index "
                    + "them. Reusing it adds to the same source."),
                ToolSchema.requiredObjectArray("documents",
                    "The documents to index; at most 256 per call.",
                    ToolSchema.required("document_id", "string",
                        "Names the document within its source. Sending the same one again "
                        + "replaces what is indexed under it."),
                    ToolSchema.required("text", "string", "The document's text. Must not be empty."),
                    ToolSchema.optional("content_type", "string",
                        "What the text is. Omit for 'text/plain'. Send 'text/html' or "
                        + "'application/xhtml+xml' for markup: the node then indexes the text the "
                        + "page shows, with tags, scripts and styles stripped, where as plain text "
                        + "the markup itself would be indexed and searched.", null),
                    ToolSchema.optionalInteger("last_modified_millis",
                        "When the document was last written, in milliseconds since the epoch. "
                        + "Facts extracted from it are recorded as stated on that date, which "
                        + "decides which of two documents' conflicting values for the same fact "
                        + "is the newer. Omit when unknown, and they are dated the day they are "
                        + "indexed.", 0)))));

        tools.add(ToolSchema.tool(INGESTION_STATUS,
            "How far indexing has got, for one source or for all of them. 'idle' with no error "
            + "means the run finished and indexed everything it listed. 'idle' with an error "
            + "means the run finished without indexing all of it - 'last_error' says how many "
            + "documents were dropped, and those documents are not searchable; "
            + "'documents_failed' is the same count as a number. 'last_error' also says when "
            + "the crawl could not list the whole source - a page whose links could not be "
            + "read - and names those pages, since what lies only behind them may be missing. "
            + "'entries_skipped' counts documents passed over because they are not text, and "
            + "'entries_disallowed' pages a website's robots.txt asked to be left out; neither "
            + "is an error. 'unconfigured' "
            + "means the server has no ingestion "
            + "pipeline and indexed nothing at all, with 'last_error' naming what has to be set - "
            + "in that case do not call build_graph, because there will be nothing to build.",
            ToolSchema.object(
                ToolSchema.optional("source_id", "string",
                    "Report only this source. Omit for every source.", null))));

        tools.add(ToolSchema.tool(CRAWL_WEBSITE,
            "Register a website as a source and start crawling and indexing it. The crawl stays "
            + "on the starting URL's host and under its path - redirects are followed with the "
            + "same policy re-applied at every hop - and the host must be one the server "
            + "allows, or the request is refused.The site's robots.txt is obeyed unless respect_robots_txt is "
            + "false, and a robots.txt the site cannot serve (a 5xx or no answer) fails the "
            + "crawl with the reason in last_error. Returns as soon as the source is "
            + "registered; the crawl continues in the background, so poll " + INGESTION_STATUS
            + " until its state is 'idle' before calling " + BUILD_GRAPH + ". This is " + INGEST_DOCUMENTS + " for "
            + "content that is on the web rather than in a directory on the server; the two "
            + "take different settings and neither accepts the other's.",
            ToolSchema.object(
                ToolSchema.required("source_id", "string",
                    "A stable name for this source. It must not already be taken: registering "
                    + "an id that is already a registered source is refused with 409. Use "
                    + REINDEX_SOURCE + " to re-crawl it later, and " + DEREGISTER_SOURCE
                    + " to stop crawling it."),
                ToolSchema.required("base_url", "string",
                    "The http or https URL the crawl starts from. Its host is the only host "
                    + "the crawl will fetch from, and only URLs whose path begins with its path "
                    + "are fetched: end it with '/' to confine the crawl to one directory, or "
                    + "give the site root to crawl the whole host."),
                ToolSchema.optional("max_depth", "integer",
                    "How many link hops to follow from the starting URL. Omit for the "
                    + "server's default.", null),
                ToolSchema.optional("max_documents", "integer",
                    "How many pages to fetch before stopping. Omit for the server's default.",
                    null),
                ToolSchema.optional("max_response_bytes", "integer",
                    "Largest single response body to hold, in bytes. Omit for the server's "
                    + "default.", null),
                ToolSchema.optional("timeout_seconds", "integer",
                    "Budget for one request. Omit for the server's default.", null),
                ToolSchema.optional("included_path_patterns", "array",
                    "A URL path must contain one of these substrings to be crawled, e.g. "
                    + "['/docs/']. Plain substrings, not globs or regular expressions. Omit "
                    + "to crawl every path on the host.", "string"),
                ToolSchema.optional("excluded_path_patterns", "array",
                    "A URL path containing one of these substrings is skipped. Supplying this "
                    + "replaces the server's default skip list rather than adding to it, and "
                    + "an empty array clears it.", "string"),
                ToolSchema.optional("namespace", "string",
                    "Namespace whose graph this source's extracted facts are staged into, for "
                    + BUILD_GRAPH + " to build from. Fixed at registration. Omit for the "
                    + "configured default, or the shared one.", null),
                ToolSchema.optional("user_agent", "string",
                    "How the crawl identifies itself to the site. Omit for the server's "
                    + "default.", null),
                ToolSchema.optional("crawl_delay_ms", "integer",
                    "Milliseconds to wait between two requests to the site, from 0 to 60000, "
                    + "for a host that should be crawled more gently than usual. The server "
                    + "has a minimum gap of its own and uses whichever is larger, so this can "
                    + "only slow a crawl down: 0 means no preference, not no pause. Omit for "
                    + "the server's pace.", null),
                ToolSchema.optional("respect_robots_txt", "boolean",
                    "Whether the crawl reads the site's robots.txt and leaves out every URL it "
                    + "disallows for this crawler's user agent, and waits at least its "
                    + "Crawl-delay between requests. On unless set to false; turn it off only "
                    + "for a site whose owner has agreed to be crawled regardless. Disallowed "
                    + "pages are counted as 'entries_disallowed' in " + INGESTION_STATUS + ".",
                    null))));

        tools.add(ToolSchema.tool(REINDEX_SOURCE,
            "Re-crawl a registered source and bring the index back into step with it: new "
            + "documents are indexed, changed ones re-indexed, and ones that have gone from the "
            + "source are removed from the index. This is how a source picks up edits made "
            + "after it was registered - re-registering the same id does not do it, and is "
            + "refused with 409. Returns 202 immediately and works in the background, so poll "
            + REINDEX_STATUS + " until its state is 'idle' before relying on the result. A "
            + "409 means a re-crawl of this source is already running, which is not a failure; "
            + "poll instead. The source's documents stay searchable throughout.",
            ToolSchema.object(
                ToolSchema.required("source_id", "string",
                    "The source to re-crawl, as it was registered."))));

        tools.add(ToolSchema.tool(REINDEX_STATUS,
            "How a re-crawl is going, for one source or for all of them. 'state' is 'crawling', "
            + "'embedding' or 'indexing' while it runs, 'idle' once it finished, 'cancelled' if "
            + "it was stopped on purpose, and 'failed' if it stopped early having indexed "
            + "nothing and confirmed nothing current. An 'idle' run with 'last_error' set "
            + "finished but failed on some documents or could not list the whole source, and "
            + "'last_error' says which. "
            + "'documents_processed' and 'documents_total' count documents; "
            + "'documents_failed' counts those it could not index or check, and "
            + "'documents_unchecked' those whose source could not say whether they changed, "
            + "the first named in 'unchecked_sample' - their indexed versions are kept. "
            + "'documents_kept_unlisted' counts indexed documents the re-crawl did not find "
            + "but kept rather than removed, because pages it could not read - named in "
            + "'last_error' - may be what leads to them. "
            + "'last_error' points at the server's log rather than quoting it. A source that "
            + "has never been re-crawled is a 404, which means nothing has been asked of it "
            + "rather than that it failed.",
            ToolSchema.object(
                ToolSchema.optional("source_id", "string",
                    "Report only this source. Omit for every source.", null))));

        tools.add(ToolSchema.tool(DEREGISTER_SOURCE,
            "Stop crawling a source. Its indexed documents are kept and stay searchable - this "
            + "does not erase anything; " + ERASE_DOCUMENTS + " does, and only when a user has "
            + "asked for that. Use it when a "
            + "source was registered against the wrong target, or should simply no longer be "
            + "followed; afterwards its id is free to register again. It stops the re-crawl "
            + "schedule too, so the index will not follow that source any further: prefer "
            + REINDEX_SOURCE + " when the source is right and you only want it refreshed.",
            ToolSchema.object(
                ToolSchema.required("source_id", "string",
                    "The source to stop crawling, as it was registered."))));

        tools.add(ToolSchema.tool(FIND_GRAPH_ENTITIES,
            "Find entities in the graph " + SEARCH_GRAPH + " reads, by exact name or by the start "
            + "of a name, ignoring case; with neither, list every entity in name order. Each "
            + "result carries the entity's 'id', its 'name', and how many facts it is the "
            + "subject ('out_degree') and object ('in_degree') of. Use it to learn what "
            + "extraction produced, to check how a name was spelt, or to get the id "
            + GRAPH_ENTITY_EDGES + " needs; two entities can share a name ignoring case, and "
            + "they are different entities. Results come a page at a time: when 'next_cursor' "
            + "is not null, call again with it as 'cursor' and the same other arguments for the "
            + "next page. Reads only the graph being served, so it needs " + BUILD_GRAPH
            + " to have run; a 503 means none is being served in that namespace.",
            ToolSchema.object(
                ToolSchema.optional("name", "string",
                    "The whole name to match, ignoring case and surrounding space. Give this "
                    + "or 'prefix', not both.", null),
                ToolSchema.optional("prefix", "string",
                    "Match names starting with this, ignoring case.", null),
                ToolSchema.optionalInteger("limit",
                    "Most entities per page, up to 500. Omit for the server's default of 50.",
                    1),
                ToolSchema.optional("cursor", "string",
                    "The 'next_cursor' of the previous page, passed back unchanged. Omit for "
                    + "the first page.", null),
                ToolSchema.optional("namespace", "string",
                    "Namespace to read. Omit for the configured default, or the shared one.",
                    null))));

        tools.add(ToolSchema.tool(GRAPH_ENTITY,
            "Describe one entity of the served graph, named by the 'id' " + FIND_GRAPH_ENTITIES
            + " returned: its name, how many facts it is the subject ('out_degree') and object "
            + "('in_degree') of, and in 'relations' those counts broken down by relation. Read "
            + "it before " + GRAPH_ENTITY_EDGES + " on an entity with many facts, to page "
            + "through only the relation that matters. An id the graph does not hold is a 404.",
            ToolSchema.object(
                ToolSchema.required("entity_id", "integer",
                    "The entity's 'id', as " + FIND_GRAPH_ENTITIES + " or a fact's subject or "
                    + "object gives it."),
                ToolSchema.optional("namespace", "string",
                    "Namespace to read. Omit for the configured default, or the shared one.",
                    null))));

        tools.add(ToolSchema.tool(GRAPH_ENTITY_EDGES,
            "List the facts the served graph holds about one entity, named by the 'id' "
            + FIND_GRAPH_ENTITIES + " returned: outgoing ones (the entity is the subject) first, "
            + "then incoming ones. Each fact carries its subject and object with their ids, its "
            + "relation, the interval it held over ('valid_from' null for an unknown start, "
            + "'valid_to' null while it still holds), a confidence between 0 and 1, and its "
            + "provenance: 'DOCUMENT' for a fact extracted from an ingested document, "
            + "'USER_ASSERTED' for one stated through " + ASSERT_GRAPH_FACTS + ", which also "
            + "carries its 'fact_id'. Unlike " + SEARCH_GRAPH + " this answers no question and "
            + "ranks nothing: it is the record, all of it, a page at a time - follow "
            + "'next_cursor' as " + FIND_GRAPH_ENTITIES + " does. An id the graph does not "
            + "hold is a 404.",
            ToolSchema.object(
                ToolSchema.required("entity_id", "integer",
                    "The entity's id, from " + FIND_GRAPH_ENTITIES + " or from an earlier "
                    + "listing of edges."),
                ToolSchema.optional("relation", "string",
                    "Only facts with this relation, spelt as extraction spells one: lower case, "
                    + "words joined by underscores ('worked with' is read as 'worked_with'). "
                    + LIST_GRAPH_RELATIONS + " lists the ones the graph holds. Omit for every "
                    + "relation.", null),
                ToolSchema.optionalChoice("direction",
                    "'out' for the facts the entity is the subject of, 'in' for those it is the "
                    + "object of, 'both' for either. Omit for both.", edgeDirectionNames()),
                ToolSchema.optional("as_of", "string",
                    "Only facts that held at this point in valid time: an ISO-8601 date "
                    + "('2019-03-01') or instant ('2019-03-01T14:00:00Z'). Omit, or send 'all', "
                    + "for every fact whenever it held, including ones since superseded.", null),
                ToolSchema.optionalInteger("limit",
                    "Most facts per page, up to 500. Omit for the server's default of 50.", 1),
                ToolSchema.optional("cursor", "string",
                    "The 'next_cursor' of the previous page, passed back unchanged. Omit for "
                    + "the first page.", null),
                ToolSchema.optional("namespace", "string",
                    "Namespace to read. Omit for the configured default, or the shared one.",
                    null))));

        tools.add(ToolSchema.tool(LIST_GRAPH_RELATIONS,
            "List the relations the facts of the served graph use - its vocabulary - in "
            + "alphabetical order, each with its 'relation' label, its 'relation_id' and how "
            + "many facts ('edges') use it. Labels are spelt exactly as the graph stores them, "
            + "which is how " + GRAPH_ENTITY_EDGES + "'s 'relation' filter matches them and how "
            + ASSERT_GRAPH_FACTS + " should name a relation the graph already has, so a new "
            + "fact joins it rather than starting a near-duplicate. Use it to learn what "
            + "extraction called a relationship before filtering by it. Results come a page at "
            + "a time: follow 'next_cursor' as " + FIND_GRAPH_ENTITIES + " does. Reads only the "
            + "graph being served, so it needs " + BUILD_GRAPH + " to have run; a 503 means none "
            + "is being served in that namespace.",
            ToolSchema.object(
                ToolSchema.optionalInteger("limit",
                    "Most relations per page, up to 500. Omit for the server's default of 100.",
                    1),
                ToolSchema.optional("cursor", "string",
                    "The 'next_cursor' of the previous page, passed back unchanged. Omit for "
                    + "the first page.", null),
                ToolSchema.optional("namespace", "string",
                    "Namespace to read. Omit for the configured default, or the shared one.",
                    null))));

        tools.add(ToolSchema.tool(ASSERT_GRAPH_FACTS,
            "State facts for the graph to hold, as subject, relation and object, when you know "
            + "something the ingested documents do not say or say wrongly. Each fact is staged "
            + "with 'USER_ASSERTED' provenance and given a 'fact_id' of its own, which the reply "
            + "lists. Staged is not served: the facts reach " + SEARCH_GRAPH + ", "
            + FIND_GRAPH_ENTITIES + " and " + GRAPH_ENTITY_EDGES + " only once the graph is "
            + "next built, so call " + BUILD_GRAPH + " afterwards when they are needed now. "
            + "Name entities exactly as the graph already names them - check with "
            + FIND_GRAPH_ENTITIES + " - or the fact attaches to a new entity with the other "
            + "spelling. There is no tool to retract a fact; a person does that over the HTTP "
            + "API, so assert only what you are sure of. Any malformed fact refuses the whole "
            + "call, and nothing is staged.",
            ToolSchema.object(
                ToolSchema.requiredObjectArray("facts",
                    "The facts to stage; 1 to 64 per call.",
                    ToolSchema.required("subject", "string",
                        "The entity the fact is about, at most 200 characters."),
                    ToolSchema.required("relation", "string",
                        "How the subject relates to the object, e.g. 'founded' or 'worked with'; "
                        + "stored lower case with spaces as underscores. At most 200 "
                        + "characters."),
                    ToolSchema.required("object", "string",
                        "The entity or value on the other side, at most 200 characters."),
                    ToolSchema.optional("valid_from", "string",
                        "The first day the fact held, as an ISO-8601 date. Omit when not "
                        + "known.", null),
                    ToolSchema.optional("valid_to", "string",
                        "The first day it no longer held, as an ISO-8601 date. Omit while it "
                        + "still holds.", null),
                    ToolSchema.optional("confidence", "number",
                        "How sure the statement is, from 0 to 1. Omit for 1.", null)),
                ToolSchema.optional("namespace", "string",
                    "Namespace to stage into. Omit for the configured default, or the shared "
                    + "one.", null))));

        addReadingTools(tools);
        MaintenanceTools.addTo(tools);
        addStoppingTools(tools);
        addDestructiveTools(tools);
        for (Object described : tools) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tool = (Map<String, Object>) described;
            tool.put("annotations", ToolHints.of(Json.stringAt(tool, "name")));
        }
        return tools;
    }

    /**
     * The tools that read back what the node holds.
     *
     * @param tools The catalogue being built
     */
    private static void addReadingTools(List<Object> tools) {
        tools.add(ToolSchema.tool(LIST_DOCUMENTS,
            "List the documents this node holds, a page at a time, with each one's 'source_id', "
            + "'document_id' and the 'chunk_ids' its text was split into - the identifiers "
            + FETCH_DOCUMENTS + " reads and " + SEARCH_DOCUMENTS + " answers with. Includes "
            + "everything ingested before the node last restarted. Pass the reply's "
            + "'next_cursor' back as 'cursor' for the next page, and stop when it is null; "
            + "'total' says how many there are in all. A 404 means the node keeps no document "
            + "registry, not that it is empty.",
            ToolSchema.object(
                ToolSchema.optional("source_id", "string",
                    "List only this source's documents. Omit for every source.", null),
                ToolSchema.optional("limit", "integer",
                    "Most documents per page, from 1 to 1000. Omit for the server's default "
                    + "of 100.", null),
                ToolSchema.optional("cursor", "string",
                    "The previous page's 'next_cursor', to continue from it. Opaque: pass it "
                    + "back exactly as received. Omit for the first page.", null))));

        tools.add(ToolSchema.tool(FETCH_DOCUMENTS,
            "Read the text of chunks by their identifiers, for results kept from an earlier "
            + SEARCH_DOCUMENTS + " or chunk ids from " + LIST_DOCUMENTS + ". Every identifier "
            + "asked for comes back, in the order asked; 'text' is null for one the node no "
            + "longer holds, and 'documents_not_found' counts them. 'text_truncated' true means "
            + "the text was clipped to the server's per-result limit.",
            ToolSchema.object(
                ToolSchema.requiredArray("document_ids",
                    "Chunk identifiers, as search results and chunk_ids give them, e.g. "
                    + "'notes:handbook.md:chunk:2'. At most 1000, none repeated.", "string"))));

        tools.add(ToolSchema.tool(LIST_NAMESPACES,
            "List the graph namespaces this node holds, a page at a time, with whether each is "
            + "open, whether a graph has been built in it, how many facts it has staged and how "
            + "many erasure obligations it still owes. 'shared' is the namespace every call "
            + "without one uses. Pass 'next_cursor' back as 'cursor' for the next page, and stop "
            + "when it is null; 'total' counts them all.",
            ToolSchema.object(
                ToolSchema.optional("limit", "integer",
                    "Most namespaces per page, from 1 to 1000. Omit for the server's default "
                    + "of 100.", null),
                ToolSchema.optional("cursor", "string",
                    "The previous page's 'next_cursor', to continue from it. Omit for the "
                    + "first page.", null))));

        tools.add(ToolSchema.tool(CREATE_NAMESPACE,
            "Create a graph namespace, before ingesting into it. A node configured to require "
            + "explicit creation refuses to ingest into, build, assert facts into or record "
            + "erasures in a namespace nobody created, with a 404 'namespace_not_found'; this "
            + "is how one is created there. Elsewhere naming a namespace creates it, and this "
            + "only does so ahead of time. Answers 201 when it created the namespace and 200 "
            + "when it already existed. A 429 'namespace_limit_reached' means the node holds as "
            + "many as it is allowed.",
            ToolSchema.object(
                ToolSchema.required("namespace", "string",
                    "The namespace to create: lowercase letters, digits, '-' and '_', starting "
                    + "with a letter or digit, at most 64 characters."))));

        tools.add(ToolSchema.tool(READ_DOCUMENT,
            "Read one document whole: its chunks in the order it was chunked, each with its "
            + "'position', 'chunk_id' and 'text', plus the document's 'chunk_count', length, "
            + "hash and when it was indexed. Name it by the 'source_id' and 'document_id' "
            + LIST_DOCUMENTS + " reports - a document id is unique only within its source. "
            + "Pages by chunks: pass 'next_cursor' back as 'cursor' until it is null. "
            + "'text_truncated' true means a chunk's text was clipped to the server's per-result "
            + "limit. A 404 means the node holds no such document.",
            ToolSchema.object(
                ToolSchema.required("source_id", "string",
                    "The source the document belongs to."),
                ToolSchema.required("document_id", "string",
                    "The document, as it was ingested under the source, e.g. 'guide.md'."),
                ToolSchema.optional("include_text", "boolean",
                    "Omit, or send true, for each chunk's text; false for the chunk ids alone, "
                    + "which pages further.", null),
                ToolSchema.optional("limit", "integer",
                    "Most chunks per page. Omit for the server's default.", null),
                ToolSchema.optional("cursor", "string",
                    "The previous page's 'next_cursor', passed back unchanged. Omit for the "
                    + "first page.", null))));

        tools.add(ToolSchema.tool(ERASURE_STATUS,
            "Read back where an erasure made by " + ERASE_DOCUMENTS + " stands, by the "
            + "'request_id' its reply carried. 'state' is 'discharged' when every part the node "
            + "tracks is done; 'applied' when nothing is searchable or served any more and the "
            + "index has still to reclaim the space; 'pending' while the graph still owes "
            + "withdrawals; 'incomplete' when some documents could not be removed and the "
            + "erasure has to be repeated for them - that is final for this id, so follow the "
            + "repeat's own 'request_id'. 'remaining' says what is still owed. It "
            + "names no documents. A 404 means the node keeps no receipt with that id - only "
            + "the most recent erasures are kept.",
            ToolSchema.object(
                ToolSchema.required("request_id", "string",
                    "The 'request_id' " + ERASE_DOCUMENTS + " answered with."))));
    }

    /**
     * The tools that stop work in progress. None destroys anything.
     *
     * @param tools The catalogue being built
     */
    private static void addStoppingTools(List<Object> tools) {
        tools.add(ToolSchema.tool(LIST_QUERIES,
            "List the searches running on the node right now, longest-running first, with the "
            + "'query_id' " + CANCEL_QUERY + " takes and how long each has run.",
            ToolSchema.object()));

        tools.add(ToolSchema.tool(CANCEL_QUERY,
            "Cancel a search that is still running. The search answers with no results and "
            + "'cancelled' true. 'cancelled' false in this reply means it had already finished "
            + "or been cancelled; a 404 means no running query has that id. Find the id with "
            + LIST_QUERIES + ".",
            ToolSchema.object(
                ToolSchema.required("query_id", "string",
                    "The query's id, as " + LIST_QUERIES + " reports it."))));

        tools.add(ToolSchema.tool(CANCEL_INGESTION,
            "Stop a source's ingestion run in flight - a crawl that is too slow or hammering a "
            + "site. The source stays registered and keeps every document already indexed; it "
            + "is crawled again at its next scheduled interval, and " + DEREGISTER_SOURCE
            + " is how to stop that too. The run stops at its next checkpoint, so poll "
            + INGESTION_STATUS + " for when it has. 'stopped' false means nothing was running.",
            ToolSchema.object(
                ToolSchema.required("source_id", "string",
                    "The source whose run to stop, as it was registered."))));

        tools.add(ToolSchema.tool(CANCEL_REINDEX,
            "Stop a source's re-crawl started by " + REINDEX_SOURCE + ". It stops at the next "
            + "document boundary and keeps every document it had already re-indexed; the rest "
            + "stay on their previous version. Poll " + REINDEX_STATUS + " for when it has "
            + "ended. 'cancelled' false means no re-crawl of that source was running.",
            ToolSchema.object(
                ToolSchema.required("source_id", "string",
                    "The source whose re-crawl to stop, as it was registered."))));

        tools.add(ToolSchema.tool(CANCEL_COMPACTION,
            "Stop the compaction an operator started, which rewrites the index and can saturate "
            + "the disk for minutes. It stops at the next segment boundary and keeps what it had "
            + "already reclaimed; nothing is lost. The background sweeps carry on. 'cancelled' "
            + "false means none was running.",
            ToolSchema.object()));

        tools.add(ToolSchema.tool(CANCEL_GRAPH_BUILD,
            "Stop the namespace's graph build in progress, whether " + BUILD_GRAPH + " started "
            + "it or the server did by itself to keep the graph current. A stopped build "
            + "publishes nothing: the namespace keeps serving the graph it had, and the staged "
            + "facts are kept for the next build. 'explicit' and 'automatic' in the reply say "
            + "which build was asked to stop, and 'cancelled' false means none was running. A "
            + "stopped automatic build is not restarted at once: " + GRAPH_BUILD_STATUS
            + " reports 'automatic.state' 'deferred' until 'automatic.next_attempt_at_ms', when "
            + "the server builds the staged changes by itself; " + BUILD_GRAPH + " builds them "
            + "sooner. Poll " + GRAPH_BUILD_STATUS + " until 'state', for a build "
            + BUILD_GRAPH + " started, or 'automatic.last_result', for the server's own, reads "
            + "'cancelled'.",
            ToolSchema.object(
                ToolSchema.optional("namespace", "string",
                    "Namespace whose build to stop. Omit for the configured default, or the "
                    + "shared one.", null))));
    }

    /**
     * The tools that destroy data, each gated on a confirmation that repeats what it destroys.
     *
     * @param tools The catalogue being built
     */
    private static void addDestructiveTools(List<Object> tools) {
        tools.add(ToolSchema.tool(ERASE_DOCUMENTS,
            "Permanently erase documents: their text and vectors leave the corpus, they stop "
            + "matching searches, and every fact extracted from them is withdrawn from the "
            + "graph. Irreversible - there is nothing to restore from. Call it only when the user "
            + "has explicitly asked for these documents to be erased; to refresh changed "
            + "content use " + REINDEX_SOURCE + ", and to stop following a source use "
            + DEREGISTER_SOURCE + ", neither of which erases anything. Name the documents in "
            + "'document_ids', or erase everything the source holds with scope 'source', which "
            + "also deregisters it and erases at most 1000 documents per call - repeat until "
            + "'documents_remaining' is 0. 'documents_not_found' counts ids the node never held. "
            + "A 202 means the corpus half is done and the graph half is still queued; "
            + "'graph_erasure_complete' says when it is not, and " + ERASURE_STATUS + " with "
            + "the reply's 'request_id' says when it has finished.",
            ToolSchema.object(
                ToolSchema.required("source_id", "string",
                    "The source the documents belong to."),
                ToolSchema.optional("document_ids", "array",
                    "The documents to erase, by the document_id they were ingested under (not "
                    + "chunk ids), at most 1000. Omit only with scope 'source'.", "string"),
                ToolSchema.optionalChoice("scope",
                    "'source' to erase every document the source holds. Never combined with "
                    + "document_ids.", List.of("source")),
                ToolSchema.optional("compact", "boolean",
                    "Rewrite the index before answering, so the text is off disk now rather "
                    + "than at the next scheduled compaction. Slower. Omit to leave that to the "
                    + "schedule.", null),
                ToolSchema.required(CONFIRM, "string",
                    "Repeat the source_id exactly, to confirm this irreversible erasure. A "
                    + "value that differs refuses the call and nothing is erased."))));

        tools.add(ToolSchema.tool(DELETE_NAMESPACE,
            "Permanently remove a graph namespace: its graph, every fact staged in it and its "
            + "log of erasures still owed. Irreversible - there is nothing to restore from. Call "
            + "it only when the user has explicitly asked for this namespace to be removed. It "
            + "never falls back on a default namespace: name it. The reply accounts for what was "
            + "destroyed. 'shared' cannot be removed (409), and a namespace that does not exist "
            + "is a 404. The documents in the corpus are not touched - " + ERASE_DOCUMENTS
            + " erases those.",
            ToolSchema.object(
                ToolSchema.required("namespace", "string",
                    "The namespace to remove."),
                ToolSchema.required(CONFIRM, "string",
                    "Repeat the namespace exactly, to confirm this irreversible removal. A value "
                    + "that differs refuses the call and nothing is removed."))));
    }

    /**
     * Runs one tool.
     *
     * @param name      The tool name
     * @param arguments Its arguments, as the client supplied them
     * @return What to hand back to the model
     */
    public ToolResult call(String name, Map<String, Object> arguments) {
        Map<String, Object> args = arguments != null ? arguments : Map.of();
        try {
            return switch (name) {
                case SEARCH_DOCUMENTS -> searchDocuments(args);
                case SEARCH_GRAPH -> searchGraph(args);
                case BUILD_GRAPH -> buildGraph(args);
                case GRAPH_BUILD_STATUS -> graphBuildStatus(args);
                case INGEST_DOCUMENTS -> ingestDocuments(args);
                case ADD_DOCUMENTS -> addDocuments(args);
                case INGESTION_STATUS -> ingestionStatus(args);
                case CRAWL_WEBSITE -> crawlWebsite(args);
                case REINDEX_SOURCE -> reindexSource(args);
                case REINDEX_STATUS -> reindexStatus(args);
                case DEREGISTER_SOURCE -> deregisterSource(args);
                case LIST_DOCUMENTS -> listDocuments(args);
                case FETCH_DOCUMENTS -> fetchDocuments(args);
                case LIST_NAMESPACES -> listNamespaces(args);
                case CREATE_NAMESPACE -> createNamespace(args);
                case LIST_QUERIES -> answer(client.get("/v1/query/status", Map.of(), null));
                case READ_DOCUMENT -> readDocument(args);
                case ERASURE_STATUS -> erasureStatus(args);
                case CANCEL_QUERY -> cancelQuery(args);
                case CANCEL_INGESTION -> stopSource("/v1/ingest/run", args);
                case CANCEL_REINDEX -> stopSource("/v1/reindex", args);
                case CANCEL_COMPACTION -> answer(client.delete("/v1/compact", null));
                case COMPACT -> answer(MaintenanceTools.compact(client, args));
                case COMPACTION_STATUS -> answer(MaintenanceTools.compactionStatus(client));
                case NODE_STATUS -> answer(MaintenanceTools.nodeStatus(client));
                case LIST_SOURCES -> answer(MaintenanceTools.listSources(client, args));
                case CANCEL_GRAPH_BUILD -> answer(client.delete("/v1/graph/build", scope(args)));
                case ERASE_DOCUMENTS -> eraseDocuments(args);
                case DELETE_NAMESPACE -> deleteNamespace(args);
                case FIND_GRAPH_ENTITIES -> findGraphEntities(args);
                case GRAPH_ENTITY -> graphEntity(args);
                case GRAPH_ENTITY_EDGES -> graphEntityEdges(args);
                case LIST_GRAPH_RELATIONS -> listGraphRelations(args);
                case ASSERT_GRAPH_FACTS -> assertGraphFacts(args);
                default -> ToolResult.error("No such tool: " + name);
            };
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            // By far the most common failure, and the one whose message has to name the address:
            // the node is not running, or is not where this server was told to look.
            return ToolResult.error("Could not reach ExcaliburDB at " + client.baseUri()
                + " (" + e + "). Check the node is running and EXCALIBUR_URL points at it.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while calling ExcaliburDB.");
        }
    }

    private ToolResult searchDocuments(Map<String, Object> args)
            throws IOException, InterruptedException {
        Map<String, Object> body = Json.object();
        body.put("query", ToolArguments.requiredString(args, "query"));
        // Always on for this caller. A model cannot resolve a chunk identifier against the store
        // the corpus was ingested from - it has no access to it - so a search that answered with
        // identifiers alone would give it nothing it could read. HTTP clients still opt in.
        body.put("include_text", true);
        // Omitted rather than defaulted here: the node has a configured default, and sending one
        // of this adapter's own would quietly override whatever the operator set.
        putWholeNumber(args, body, "top_k");
        putWholeNumber(args, body, "offset");
        putWholeNumber(args, body, "timeout_ms");
        putFlag(args, body, "snippet");
        putWholeNumber(args, body, "snippet_chars");
        putFlag(args, body, "explain");
        putNumber(args, body, "threshold");
        putNumber(args, body, "llm_threshold");
        putStrings(args, body, "source_ids");
        return answer(client.post("/v1/query", Json.write(body), null));
    }

    private ToolResult listDocuments(Map<String, Object> args)
            throws IOException, InterruptedException {
        return answer(client.get("/v1/documents/list", page(args, "source_id"), null));
    }

    private ToolResult fetchDocuments(Map<String, Object> args)
            throws IOException, InterruptedException {
        // Refused here for the reason add_documents refuses an empty batch: the node would say
        // the same thing one round trip later.
        List<String> ids = ToolArguments.optionalStringList(args, "document_ids");
        if (ids == null || ids.isEmpty()) {
            return ToolResult.error("'document_ids' is required and must name at least one chunk.");
        }
        Map<String, Object> body = Json.object();
        body.put("document_ids", ids);
        return answer(client.post("/v1/documents/fetch", Json.write(body), null));
    }

    private ToolResult listNamespaces(Map<String, Object> args)
            throws IOException, InterruptedException {
        return answer(client.get("/v1/graph/namespaces", page(args, null), null));
    }

    private ToolResult createNamespace(Map<String, Object> args)
            throws IOException, InterruptedException {
        return answer(client.put(namespacePath(namedNamespace(args)), null));
    }

    private ToolResult cancelQuery(Map<String, Object> args)
            throws IOException, InterruptedException {
        String queryId = issuedId(args, "query_id", LIST_QUERIES);
        return answer(client.delete("/v1/query/" + queryId, null));
    }

    private ToolResult readDocument(Map<String, Object> args)
            throws IOException, InterruptedException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("source_id", ToolArguments.requiredString(args, "source_id"));
        query.put("document_id", ToolArguments.requiredString(args, "document_id"));
        // On unless the model turns it off, for search_documents' reason: an id is nothing a
        // model can read, so a read of a document that answered ids alone would answer nothing.
        Boolean includeText = ToolArguments.optionalBoolean(args, "include_text");
        query.put("include_text", String.valueOf(includeText == null || includeText));
        query.putAll(page(args, null));
        return answer(client.get("/v1/documents/list", query, null));
    }

    private ToolResult erasureStatus(Map<String, Object> args)
            throws IOException, InterruptedException {
        String requestId = issuedId(args, "request_id", ERASE_DOCUMENTS);
        return answer(client.get("/v1/erasure/" + requestId, Map.of(), null));
    }

    /**
     * Reads an id the node issued, which the call puts into a path.
     *
     * @param args     The call's arguments
     * @param field    The argument holding it
     * @param issuedBy The tool whose reply carries it, for the message
     * @return The id
     * @throws IllegalArgumentException if it is absent or could name a different path
     */
    private static String issuedId(Map<String, Object> args, String field, String issuedBy) {
        String id = ToolArguments.requiredString(args, field);
        if (!ISSUED_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Argument '" + field + "' is not an id the node "
                + "issued, got: " + id + ". Use one " + issuedBy + " reported.");
        }
        return id;
    }

    /**
     * Asks a source's run to stop, on the route that names the source in its body.
     *
     * @param path The route: the ingestion run's or the re-crawl's
     * @param args The call's arguments
     * @return What the node said
     */
    private ToolResult stopSource(String path, Map<String, Object> args)
            throws IOException, InterruptedException {
        Map<String, Object> body = Json.object();
        body.put("source_id", ToolArguments.requiredString(args, "source_id"));
        return answer(client.delete(path, Json.write(body), null));
    }

    private ToolResult eraseDocuments(Map<String, Object> args)
            throws IOException, InterruptedException {
        String sourceId = ToolArguments.requiredString(args, "source_id");
        List<String> documentIds = ToolArguments.optionalStringList(args, "document_ids");
        String scope = ToolArguments.optionalString(args, "scope");
        if (scope != null && !"source".equals(scope)) {
            throw new IllegalArgumentException(
                "Argument 'scope' can only be 'source', got: " + scope);
        }
        boolean wholeSource = scope != null;
        // Checked before the confirmation, so a call that could not have been sent is told what
        // to fix rather than only that the confirmation was wrong.
        if (wholeSource && documentIds != null) {
            throw new IllegalArgumentException("Send either 'document_ids' or scope 'source', "
                + "not both: they erase different amounts.");
        }
        if (!wholeSource && (documentIds == null || documentIds.isEmpty())) {
            throw new IllegalArgumentException("Name the documents to erase in 'document_ids', "
                + "or send scope 'source' to erase everything the source holds.");
        }
        requireConfirmation(args, sourceId, "source_id");

        Map<String, Object> body = Json.object();
        body.put("source_id", sourceId);
        if (wholeSource) {
            body.put("scope", scope);
        } else {
            body.put("document_ids", documentIds);
        }
        putFlag(args, body, "compact");
        return answer(client.post("/v1/erasure", Json.write(body), null));
    }

    private ToolResult deleteNamespace(Map<String, Object> args)
            throws IOException, InterruptedException {
        String namespace = namedNamespace(args);
        requireConfirmation(args, namespace, "namespace");
        return answer(client.delete(namespacePath(namespace), null));
    }

    /**
     * Refuses a destructive call whose confirmation does not repeat what it destroys.
     *
     * <p>The refusal says what {@code confirm} has to repeat without quoting the value. Quoted,
     * it turns the refusal into the next call's argument: a model that sent no confirmation,
     * or the wrong one, could copy it across and retry without the user ever being asked, which
     * is the step the confirmation is there to put back in.</p>
     *
     * @param args     The call's arguments
     * @param expected What {@code confirm} has to say
     * @param field    The argument it repeats, for the message
     * @throws IllegalArgumentException if it is absent or says anything else
     */
    private static void requireConfirmation(Map<String, Object> args, String expected,
                                            String field) {
        if (!expected.equals(ToolArguments.optionalString(args, CONFIRM))) {
            throw new IllegalArgumentException("This call is irreversible, so '" + CONFIRM
                + "' must repeat this call's " + field + " exactly, as the user gave it. "
                + "Nothing was sent. Make this call only if the user explicitly asked for it; "
                + "if they did not, ask them rather than retrying.");
        }
    }

    /**
     * Reads the namespace a namespace-management call names, which it must name itself.
     *
     * <p>Never the configured default: a drop that fell back on one would remove a namespace
     * the model never mentioned. Checked against the node's own rule because it goes into a
     * path, where {@code ..} or a separator would name a different route; {@code shared} is
     * passed through for the node to answer, since the rule refuses it only so no tenant can
     * claim it.</p>
     *
     * @param args The call's arguments
     * @return The namespace
     * @throws IllegalArgumentException if it is absent or is not a namespace name
     */
    private static String namedNamespace(Map<String, Object> args) {
        String namespace = ToolArguments.requiredString(args, "namespace");
        if (!NamespaceId.SHARED.value().equals(namespace)) {
            NamespaceId.of(namespace);
        }
        return namespace;
    }

    private static String namespacePath(String namespace) {
        return "/v1/graph/namespaces/" + ExcaliburClient.encode(namespace);
    }

    /**
     * The paging parameters of a listing, absent staying absent.
     *
     * @param args   The call's arguments
     * @param filter A string filter the listing also takes, or null
     * @return The parameters, in the order they are sent
     */
    private static Map<String, String> page(Map<String, Object> args, String filter) {
        Map<String, String> query = new LinkedHashMap<>();
        if (filter != null) {
            query.put(filter, ToolArguments.optionalString(args, filter));
        }
        Integer limit = ToolArguments.wholeNumber(args, "limit");
        query.put("limit", limit != null ? limit.toString() : null);
        query.put("cursor", ToolArguments.optionalString(args, "cursor"));
        return query;
    }

    private ToolResult searchGraph(Map<String, Object> args)
            throws IOException, InterruptedException {
        Map<String, Object> body = Json.object();
        body.put("query", ToolArguments.requiredString(args, "query"));
        List<Object> mentions = ContextEntities.read(args);
        if (mentions != null) {
            body.put(ContextEntities.FIELD, mentions);
        }
        // Omitted rather than defaulted: without one the node answers at the instant the
        // question implies, and sending "today" here would silently overrule that.
        String asOf = ToolArguments.optionalString(args, "as_of");
        if (asOf != null) {
            body.put("as_of", asOf);
        }
        // Checked here as well as by the node, so a misspelt direction is a tool error naming the
        // three it could have been rather than a round trip; omitted when absent, for the same
        // reason as as_of.
        String direction = ToolArguments.optionalString(args, "direction");
        if (direction != null) {
            body.put("direction", TraversalDirection.parse(direction).wireName());
        }
        putWholeNumber(args, body, "timeout_ms");
        return answer(client.post("/v1/graph/query", Json.write(body), scope(args)));
    }

    /** The directions search_graph accepts, as a request spells them. */
    private static List<String> directionNames() {
        List<String> names = new ArrayList<>();
        for (TraversalDirection direction : TraversalDirection.values()) {
            names.add(direction.wireName());
        }
        return names;
    }

    private ToolResult buildGraph(Map<String, Object> args)
            throws IOException, InterruptedException {
        return answer(client.post("/v1/graph/build", "{}", scope(args)));
    }

    private ToolResult graphBuildStatus(Map<String, Object> args)
            throws IOException, InterruptedException {
        return answer(client.get("/v1/graph/build/status", null, null, scope(args)));
    }

    private ToolResult ingestDocuments(Map<String, Object> args)
            throws IOException, InterruptedException {
        ToolArguments.requireKnown(args, argumentsOf(INGEST_DOCUMENTS));
        Map<String, Object> sourceConfig = Json.object();
        sourceConfig.put("source_id", ToolArguments.requiredString(args, "source_id"));
        sourceConfig.put("root_path", ToolArguments.requiredString(args, "root_path"));
        putStrings(args, sourceConfig, "included_extensions");
        putStrings(args, sourceConfig, "excluded_directories");
        putWholeNumber(args, sourceConfig, "max_depth");
        putFlag(args, sourceConfig, "follow_symlinks");
        putFlag(args, sourceConfig, "include_hidden");
        // Read as a long so any whole number reaches the node, which answers one past its
        // ceiling with the 400 that names the limit.
        Long maxFileBytes = ToolArguments.wholeLong(args, "max_file_bytes");
        if (maxFileBytes != null) {
            sourceConfig.put("max_file_bytes", maxFileBytes);
        }
        // Forwarded as named, not checked against this process's charsets: the encodings that
        // matter are the ones the node can decode, and it answers an unknown one with a 400
        // that names the field, which reaches the model as the tool's error.
        String charset = ToolArguments.optionalString(args, "charset");
        if (charset != null) {
            sourceConfig.put("charset", charset);
        }

        Map<String, Object> body = Json.object();
        body.put("source_type", "filesystem");
        body.put("config", sourceConfig);
        return answer(client.post("/v1/ingest", Json.write(body), scope(args)));
    }

    private ToolResult addDocuments(Map<String, Object> args)
            throws IOException, InterruptedException {
        Map<String, Object> body = Json.object();
        body.put("source_id", ToolArguments.requiredString(args, "source_id"));
        // Refused here rather than sent on. The node would answer 400 anyway, and a round trip
        // to be told what this call already knows is a slower way to say the same thing.
        List<Object> documents = ToolArguments.optionalList(args, "documents");
        if (documents == null || documents.isEmpty()) {
            return ToolResult.error(
                "'documents' is required and must carry at least one document.");
        }
        body.put("documents", ToolArguments.declaredElements("documents", documents,
            itemsOf(ADD_DOCUMENTS, "documents")));
        return answer(client.post("/v1/documents", Json.write(body), null));
    }

    /**
     * The argument names a tool's input schema declares.
     *
     * <p>Read from the catalogue rather than listed a second time, so what a tool refuses is
     * exactly what its schema does not offer, and a setting added to the schema is accepted
     * without a second edit.</p>
     *
     * @param tool The tool name
     * @return Its argument names
     * @throws IllegalStateException if the catalogue has no such tool
     */
    static Set<String> argumentsOf(String tool) {
        for (Object described : catalogue()) {
            if (tool.equals(Json.stringAt(described, "name"))) {
                return Set.copyOf(Json.objectAt(Json.objectAt(described, "inputSchema"),
                    "properties").keySet());
            }
        }
        throw new IllegalStateException("The catalogue describes no tool named " + tool);
    }

    /**
     * The schema one element of a tool's array argument is declared with.
     *
     * <p>Read from the catalogue for the reason {@link #argumentsOf} gives: what an element may
     * hold is then exactly what the schema tells the model it may hold.</p>
     *
     * @param tool  The tool name
     * @param field The array argument
     * @return Its {@code items} schema
     * @throws IllegalStateException if the catalogue has no such tool or argument
     */
    static Map<String, Object> itemsOf(String tool, String field) {
        for (Object described : catalogue()) {
            if (tool.equals(Json.stringAt(described, "name"))) {
                Map<String, Object> items = Json.objectAt(Json.objectAt(Json.objectAt(
                    Json.objectAt(described, "inputSchema"), "properties"), field), "items");
                if (items != null) {
                    return items;
                }
            }
        }
        throw new IllegalStateException("The catalogue declares no array of objects " + field
            + " on " + tool);
    }

    private ToolResult ingestionStatus(Map<String, Object> args)
            throws IOException, InterruptedException {
        String sourceId = ToolArguments.optionalString(args, "source_id");
        return answer(client.get("/v1/ingest/status",
            sourceId != null ? "source_id" : null, sourceId));
    }

    /**
     * Registers an HTTP crawl.
     *
     * <p>A tool of its own rather than a mode of {@link #INGEST_DOCUMENTS}, because the node
     * refuses a registration that carries the other type's settings rather than ignoring them.
     * One tool covering both would offer a model, on every call, the other type's settings -
     * each of which makes that call a 400 - and the model would have nothing but the prose to
     * tell it which ones those are.</p>
     */
    private ToolResult crawlWebsite(Map<String, Object> args)
            throws IOException, InterruptedException {
        ToolArguments.requireKnown(args, argumentsOf(CRAWL_WEBSITE));
        Map<String, Object> sourceConfig = Json.object();
        sourceConfig.put("source_id", ToolArguments.requiredString(args, "source_id"));
        sourceConfig.put("base_url", ToolArguments.requiredString(args, "base_url"));
        putWholeNumber(args, sourceConfig, "max_depth");
        putWholeNumber(args, sourceConfig, "max_documents");
        putWholeNumber(args, sourceConfig, "max_response_bytes");
        putWholeNumber(args, sourceConfig, "timeout_seconds");
        putStrings(args, sourceConfig, "included_path_patterns");
        putStrings(args, sourceConfig, "excluded_path_patterns");
        String userAgent = ToolArguments.optionalString(args, "user_agent");
        if (userAgent != null) {
            sourceConfig.put("user_agent", userAgent);
        }
        putWholeNumber(args, sourceConfig, "crawl_delay_ms");
        putFlag(args, sourceConfig, "respect_robots_txt");

        Map<String, Object> body = Json.object();
        body.put("source_type", "http");
        body.put("config", sourceConfig);
        return answer(client.post("/v1/ingest", Json.write(body), scope(args)));
    }

    private ToolResult reindexSource(Map<String, Object> args)
            throws IOException, InterruptedException {
        Map<String, Object> body = Json.object();
        body.put("source_id", ToolArguments.requiredString(args, "source_id"));
        return answer(client.post("/v1/reindex", Json.write(body), null));
    }

    private ToolResult reindexStatus(Map<String, Object> args)
            throws IOException, InterruptedException {
        String sourceId = ToolArguments.optionalString(args, "source_id");
        return answer(client.get("/v1/reindex/status",
            sourceId != null ? "source_id" : null, sourceId));
    }

    private ToolResult deregisterSource(Map<String, Object> args)
            throws IOException, InterruptedException {
        Map<String, Object> body = Json.object();
        body.put("source_id", ToolArguments.requiredString(args, "source_id"));
        return answer(client.delete("/v1/ingest", Json.write(body), null));
    }

    private ToolResult findGraphEntities(Map<String, Object> args)
            throws IOException, InterruptedException {
        String name = ToolArguments.optionalString(args, "name");
        String prefix = ToolArguments.optionalString(args, "prefix");
        if (name != null && prefix != null) {
            return ToolResult.error("Give 'name' or 'prefix', not both.");
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("name", name);
        query.put("prefix", prefix);
        putPage(args, query);
        return answer(client.get("/v1/graph/entities", query, scope(args)));
    }

    private ToolResult graphEntity(Map<String, Object> args)
            throws IOException, InterruptedException {
        long entityId = entityId(args);
        return answer(client.get("/v1/graph/entities/" + entityId, Map.of(), scope(args)));
    }

    private ToolResult graphEntityEdges(Map<String, Object> args)
            throws IOException, InterruptedException {
        long entityId = entityId(args);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("relation", ToolArguments.optionalString(args, "relation"));
        // Checked here as search_graph checks its direction, so a misspelt one is refused naming
        // the three it could have been.
        String direction = ToolArguments.optionalString(args, "direction");
        query.put("direction", direction == null ? null : EdgeDirection.parse(direction).wireName());
        query.put("as_of", ToolArguments.optionalString(args, "as_of"));
        putPage(args, query);
        return answer(client.get("/v1/graph/entities/" + entityId + "/edges", query,
            scope(args)));
    }

    private ToolResult listGraphRelations(Map<String, Object> args)
            throws IOException, InterruptedException {
        Map<String, String> query = new LinkedHashMap<>();
        putPage(args, query);
        return answer(client.get("/v1/graph/relations", query, scope(args)));
    }

    /**
     * Reads the entity id a graph tool puts in its path, as a whole number the node could have
     * issued, so nothing else can reach the path.
     *
     * @throws IllegalArgumentException if it is missing or negative
     */
    private static long entityId(Map<String, Object> args) {
        Long entityId = ToolArguments.wholeLong(args, "entity_id");
        if (entityId == null) {
            throw new IllegalArgumentException("Argument 'entity_id' is required.");
        }
        if (entityId < 0) {
            throw new IllegalArgumentException("Argument 'entity_id' must not be negative, got: "
                + entityId);
        }
        return entityId;
    }

    private ToolResult assertGraphFacts(Map<String, Object> args)
            throws IOException, InterruptedException {
        // Each fact's members are checked against the schema here, so a misspelt one is named in
        // the tool's own terms before anything is sent. What they say is the node's to judge: it
        // cleans and bounds each fact exactly as it does an extracted one, and answers the first
        // it refuses naming the fact and the field.
        List<Object> facts = ToolArguments.optionalList(args, "facts");
        if (facts == null || facts.isEmpty()) {
            return ToolResult.error("'facts' is required and must carry at least one fact.");
        }
        Map<String, Object> body = Json.object();
        body.put("facts", ToolArguments.declaredElements("facts", facts,
            itemsOf(ASSERT_GRAPH_FACTS, "facts")));
        return answer(client.post("/v1/graph/facts", Json.write(body), scope(args)));
    }

    /** The directions graph_entity_edges accepts, as a request spells them. */
    private static List<String> edgeDirectionNames() {
        List<String> names = new ArrayList<>();
        for (EdgeDirection direction : EdgeDirection.values()) {
            names.add(direction.wireName());
        }
        return names;
    }

    /**
     * Copies the paging arguments into a listing's query, absent staying absent.
     *
     * @param args  The call's arguments
     * @param query The query being built
     */
    private static void putPage(Map<String, Object> args, Map<String, String> query) {
        Integer limit = ToolArguments.wholeNumber(args, "limit");
        query.put("limit", limit == null ? null : limit.toString());
        query.put("cursor", ToolArguments.optionalString(args, "cursor"));
    }

    /**
     * Copies a whole-number setting into a request body, absent staying absent.
     *
     * <p>Absent matters: the node has a configured default for every one of these, and sending
     * a value this adapter invented would quietly overrule whatever the operator set.</p>
     *
     * @param args   The call's arguments
     * @param config The body being built
     * @param field  The setting
     */
    private static void putWholeNumber(Map<String, Object> args, Map<String, Object> config,
                                       String field) {
        Integer value = ToolArguments.wholeNumber(args, field);
        if (value != null) {
            config.put(field, value);
        }
    }

    /**
     * Copies a numeric setting that may be fractional into a request body, absent staying
     * absent.
     *
     * @param args   The call's arguments
     * @param config The body being built
     * @param field  The setting
     */
    private static void putNumber(Map<String, Object> args, Map<String, Object> config,
                                  String field) {
        Double value = ToolArguments.number(args, field);
        if (value != null) {
            config.put(field, value);
        }
    }

    /**
     * Copies a true/false setting into a request body, absent staying absent.
     *
     * <p>False is forwarded, not dropped: it is a setting the model made, and the node's
     * default is only what applies when none was made.</p>
     *
     * @param args   The call's arguments
     * @param config The body being built
     * @param field  The setting
     */
    private static void putFlag(Map<String, Object> args, Map<String, Object> config,
                                String field) {
        Boolean value = ToolArguments.optionalBoolean(args, field);
        if (value != null) {
            config.put(field, value);
        }
    }

    /**
     * Copies a list setting into a source configuration, absent staying absent.
     *
     * <p>An empty list is forwarded, not dropped: the node reads it as "clear the default",
     * which is how a caller turns off a crawl's default exclusions or a directory's default
     * skip list. Every list setting goes through here, so a tool never registers a different
     * source than the same body sent over HTTP would.</p>
     *
     * @param args   The call's arguments
     * @param config The configuration being built
     * @param field  The setting
     */
    private static void putStrings(Map<String, Object> args, Map<String, Object> config,
                                   String field) {
        List<String> values = ToolArguments.optionalStringList(args, field);
        if (values != null) {
            config.put(field, values);
        }
    }

    /**
     * Turns a node response into a tool result.
     *
     * <p>A non-2xx is surfaced as an error with the node's own body attached rather than a summary
     * of it: the endpoints already explain what was wrong - which root is not allowed, that no
     * graph has been built - and rewriting that in the client would only lose it.</p>
     *
     * @param response What the node said
     * @return The tool result
     */
    private static ToolResult answer(ExcaliburClient.Response response) {
        if (response.isSuccess()) {
            return ToolResult.ok(response.body());
        }
        return ToolResult.error("ExcaliburDB returned HTTP " + response.status() + ": "
            + response.body());
    }

    /**
     * The namespace a call that takes one is scoped to: the argument, else the configured
     * default. Only the graph tools and source registration call this; every other route serves
     * the whole corpus and refuses a namespace.
     */
    private String scope(Map<String, Object> args) {
        return client.namespaceFor(ToolArguments.optionalString(args, "namespace"));
    }

    /**
     * What a tool call produced.
     *
     * @param text    The text handed to the model
     * @param isError Whether the call failed
     */
    public record ToolResult(String text, boolean isError) {

        /**
         * A successful call.
         *
         * @param text The node's answer
         * @return The result
         */
        public static ToolResult ok(String text) {
            return new ToolResult(text, false);
        }

        /**
         * A failed call.
         *
         * @param text What went wrong
         * @return The result
         */
        public static ToolResult error(String text) {
            return new ToolResult(text, true);
        }
    }
}
