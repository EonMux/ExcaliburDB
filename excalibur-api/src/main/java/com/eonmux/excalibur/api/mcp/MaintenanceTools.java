package com.eonmux.excalibur.api.mcp;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The tools that look after the node rather than its corpus: reclaiming space, reading back
 * what each source was registered as, and saying which build is answering and how it is.
 *
 * <p>Kept apart from {@link McpTools} because none of them searches, supplies or erases anything,
 * and each reads or starts one piece of node housekeeping. {@link McpTools} names them and
 * dispatches to them, so the catalogue a model reads is still one list.</p>
 */
final class MaintenanceTools {

    private MaintenanceTools() {
    }

    /**
     * Adds these tools to the catalogue.
     *
     * @param tools The catalogue being built
     */
    static void addTo(List<Object> tools) {
        tools.add(ToolSchema.tool(McpTools.LIST_SOURCES,
            "Read back what each source was registered as: its 'source_type' ('filesystem' or "
            + "'http'), the namespace its facts go to, and in 'config' the settings it was "
            + "registered with - root path or base URL, depth, filters. Use it to learn what a "
            + "'source_id' points at before re-crawling, reindexing or erasing it. 'registered' "
            + "false means the node keeps the registration on file but no longer crawls it, "
            + "because its target is outside what the node now allows; fix that on the node, "
            + "not by registering it again. A 404 for a named source means nothing is "
            + "registered under that id. How a source's runs went is "
            + McpTools.INGESTION_STATUS + "'s answer, not this one's.",
            ToolSchema.object(
                ToolSchema.optional("source_id", "string",
                    "Read back only this source. Omit for every source.", null))));

        tools.add(ToolSchema.tool(McpTools.COMPACT,
            "Reclaim the space that erased and replaced documents still occupy, now rather than "
            + "at the next background sweep. Nothing searchable changes: those documents are "
            + "already invisible to search, and this only frees their bytes. It answers at "
            + "once with 'state' 'running' and does the work behind the reply, which on a large "
            + "corpus is minutes of disk I/O - poll " + McpTools.COMPACTION_STATUS + " for the "
            + "outcome, and " + McpTools.CANCEL_COMPACTION + " stops it. A 409 means one is "
            + "already running. The usual time to call it is after " + McpTools.ERASE_DOCUMENTS
            + " removed a large part of the corpus.",
            ToolSchema.object(
                ToolSchema.optional("force", "boolean",
                    "true to rewrite every segment whether or not it is due, which is what "
                    + "reclaims everything after a large erasure; omit or false to do only the "
                    + "work the background sweeps would have done anyway.", null))));

        tools.add(ToolSchema.tool(McpTools.COMPACTION_STATUS,
            "How the most recent compaction went. 'state' is 'idle' when none has run since the "
            + "node started, 'running' while one runs, 'completed', 'cancelled' when "
            + McpTools.CANCEL_COMPACTION + " stopped it - keeping what it had reclaimed - or "
            + "'failed', with 'error' saying why. Counts say how many segments were considered, "
            + "compacted, skipped and failed; a completed run with failed segments reclaimed "
            + "the rest. 'lexical' reports the keyword index's half separately.",
            ToolSchema.object()));

        tools.add(ToolSchema.tool(McpTools.NODE_STATUS,
            "Which build of the node is answering and how it is. 'version' names the build and "
            + "says how long it has been up - a short 'uptimeMs' means it restarted. 'stats' is "
            + "the node's own account of itself: 'coverage' below 1 means part of the corpus is "
            + "not yet searchable, 'segments' says how much is still indexing, 'breakers' "
            + "reads 'OPEN' for a model role that has been failing and is being skipped - an "
            + "open 'embedding' breaker is why searches answer by keyword only - and 'graph', "
            + "when the node builds one, counts builds and erasures still owed. Use it to "
            + "explain a degraded or incomplete answer, or before a long ingestion or build.",
            ToolSchema.object()));
    }

    /**
     * Reads back one source's registration, or every source's.
     *
     * @param client The node
     * @param args   The call's arguments
     * @return The node's answer
     */
    static ExcaliburClient.Response listSources(ExcaliburClient client, Map<String, Object> args)
            throws IOException, InterruptedException {
        String sourceId = ToolArguments.optionalString(args, "source_id");
        return client.get("/v1/ingest", sourceId != null ? "source_id" : null, sourceId);
    }

    /**
     * Starts a compaction.
     *
     * @param client The node
     * @param args   The call's arguments
     * @return The node's answer
     */
    static ExcaliburClient.Response compact(ExcaliburClient client, Map<String, Object> args)
            throws IOException, InterruptedException {
        Map<String, Object> body = Json.object();
        // Omitted rather than defaulted, as every optional setting is, so the node's own default
        // applies.
        Boolean force = ToolArguments.optionalBoolean(args, "force");
        if (force != null) {
            body.put("force", force);
        }
        return client.post("/v1/compact", Json.write(body), null);
    }

    /**
     * Reads back the most recent compaction.
     *
     * @param client The node
     * @return The node's answer
     */
    static ExcaliburClient.Response compactionStatus(ExcaliburClient client)
            throws IOException, InterruptedException {
        return client.get("/v1/compact/status", Map.of(), null);
    }

    /**
     * Reads which build is answering and the node's counters, as one answer.
     *
     * <p>Two reads rather than one route because the node serves them apart: {@code /v1/version}
     * is the build and its uptime, {@code /v1/stats} everything the node counts. A failure of
     * either is answered as that failure, so a model is never handed half a status as though it
     * were the whole of one.</p>
     *
     * @param client The node
     * @return {@code {"version": ..., "stats": ...}}, or the first read the node refused
     */
    static ExcaliburClient.Response nodeStatus(ExcaliburClient client)
            throws IOException, InterruptedException {
        ExcaliburClient.Response version = client.get("/v1/version", Map.of(), null);
        if (!version.isSuccess()) {
            return version;
        }
        ExcaliburClient.Response stats = client.get("/v1/stats", Map.of(), null);
        if (!stats.isSuccess()) {
            return stats;
        }
        Map<String, Object> status = Json.object();
        status.put("version", Json.parse(version.body()));
        status.put("stats", Json.parse(stats.body()));
        return new ExcaliburClient.Response(stats.status(), Json.write(status));
    }
}
