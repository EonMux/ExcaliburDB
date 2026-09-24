package com.eonmux.excalibur.api.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.eonmux.excalibur.graph.browse.EdgeDirection;
import com.eonmux.excalibur.retrieval.pipeline.TraversalDirection;

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
 * <h2>What is deliberately not here</h2>
 * <p>Neither erasure route is a tool: not {@code POST /v1/erasure}, which removes a document from
 * the corpus, and not {@code POST /v1/graph/erasure}, which removes facts from the graph. They
 * exist to honour a right-to-be-forgotten request and they destroy data to do it, which makes them
 * the calls whose cost of being made wrongly is not bounded by retrying. A model asked to tidy up a
 * corpus would have every reason to reach for them. They stay behind the HTTP API, where a person
 * issues them.</p>
 *
 * <p>Nor is retracting an asserted fact. {@link #ASSERT_GRAPH_FACTS} is here because stating a
 * fact is additive and, until a build, invisible; {@code DELETE /v1/graph/facts} is not, because
 * a retraction is recorded as a graph erasure - irreversible once compacted - and matching by
 * subject, relation and object retracts every asserted statement of that triple, including ones a
 * person made. It stays with the erasure routes.</p>
 *
 * <p>{@link #DEREGISTER_SOURCE} is not one of them, though it was held back for a while on the
 * same reasoning. It destroys nothing - {@code DELETE /v1/ingest} keeps every document the
 * source contributed - and what it does do, stop the corpus following its source, is exactly
 * what a model has to be able to do to correct its own mistake. Its description says both
 * halves, because a model that thinks it erases the corpus will not reach for it and one that
 * thinks it changes nothing will reach for it too readily.</p>
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

    /** Find entities in the served graph by name, and learn their ids. */
    public static final String FIND_GRAPH_ENTITIES = "find_graph_entities";

    /** List the facts the served graph holds about one entity. */
    public static final String GRAPH_ENTITY_EDGES = "graph_entity_edges";

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
     * @return One descriptor per tool, each with its name, description and input schema
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
                    "How many passages to return. Omit for the server's default.", null))));

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
                ToolSchema.optional("namespace", "string",
                    "Namespace to query. Omit for the configured default, or the shared one.",
                    null))));

        tools.add(ToolSchema.tool(BUILD_GRAPH,
            "Turn the facts ingestion has extracted into the graph that search_graph reads. Run it "
            + "once after ingesting documents and again after ingesting more; until it has run, "
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
            + "server has extraction disabled. A cancelled build published nothing and the "
            + "namespace is still serving the graph it had.",
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
                    + "that the operating system marks hidden. Omit for the server's default, "
                    + "which skips them.", null),
                ToolSchema.optional("max_file_bytes", "integer",
                    "Largest single file to index, in bytes, from 1 to 104857600 (100 MiB), "
                    + "the largest document the server indexes. A larger file is skipped rather "
                    + "than truncated. Omit for the server's default, which is that limit.", null),
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
                    ToolSchema.required("text", "string", "The document's text. Must not be empty.")))));

        tools.add(ToolSchema.tool(INGESTION_STATUS,
            "How far indexing has got, for one source or for all of them. 'idle' with no error "
            + "means the run finished and indexed everything it listed. 'idle' with an error "
            + "means the run finished without indexing all of it - 'last_error' says how many "
            + "documents were dropped, and those documents are not searchable. "
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
            + "on the starting URL's host - redirects are followed with the same host policy "
            + "re-applied at every hop - and the host must be one the server allows, or the "
            + "request is refused. The site's robots.txt is obeyed unless respect_robots_txt is "
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
                    + "the crawl will fetch from."),
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
            + "nothing. An 'idle' run with 'last_error' set finished but failed on some "
            + "documents, and 'last_error' says how many. 'documents_processed' and "
            + "'documents_total' count documents, and "
            + "'last_error' points at the server's log rather than quoting it. A source that "
            + "has never been re-crawled is a 404, which means nothing has been asked of it "
            + "rather than that it failed.",
            ToolSchema.object(
                ToolSchema.optional("source_id", "string",
                    "Report only this source. Omit for every source.", null))));

        tools.add(ToolSchema.tool(DEREGISTER_SOURCE,
            "Stop crawling a source. Its indexed documents are kept and stay searchable - this "
            + "does not erase anything, and there is no tool here that does. Use it when a "
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
                    + "Omit for every relation.", null),
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

        return tools;
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
                case FIND_GRAPH_ENTITIES -> findGraphEntities(args);
                case GRAPH_ENTITY_EDGES -> graphEntityEdges(args);
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
        Integer topK = ToolArguments.wholeNumber(args, "top_k");
        if (topK != null) {
            body.put("top_k", topK);
        }
        return answer(client.post("/v1/query", Json.write(body), null));
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
        body.put("documents", documents);
        return answer(client.post("/v1/documents", Json.write(body), null));
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

    private ToolResult graphEntityEdges(Map<String, Object> args)
            throws IOException, InterruptedException {
        Long entityId = ToolArguments.wholeLong(args, "entity_id");
        if (entityId == null) {
            return ToolResult.error("Argument 'entity_id' is required.");
        }
        if (entityId < 0) {
            return ToolResult.error("Argument 'entity_id' must not be negative, got: "
                + entityId);
        }
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

    private ToolResult assertGraphFacts(Map<String, Object> args)
            throws IOException, InterruptedException {
        // Forwarded as written, as add_documents forwards its documents: the node cleans and
        // bounds each fact exactly as it does an extracted one, and answers the first it refuses
        // naming the fact and the field.
        List<Object> facts = ToolArguments.optionalList(args, "facts");
        if (facts == null || facts.isEmpty()) {
            return ToolResult.error("'facts' is required and must carry at least one fact.");
        }
        Map<String, Object> body = Json.object();
        body.put("facts", facts);
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
     * Copies a whole-number setting into a source configuration, absent staying absent.
     *
     * <p>Absent matters: the node has a configured default for every one of these, and sending
     * a value this adapter invented would quietly overrule whatever the operator set.</p>
     *
     * @param args   The call's arguments
     * @param config The configuration being built
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
     * Copies a true/false setting into a source configuration, absent staying absent.
     *
     * <p>False is forwarded, not dropped: it is a setting the model made, and the node's
     * default is only what applies when none was made.</p>
     *
     * @param args   The call's arguments
     * @param config The configuration being built
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
