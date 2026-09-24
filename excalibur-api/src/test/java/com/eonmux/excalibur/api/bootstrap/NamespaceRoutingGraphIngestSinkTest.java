package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.graph.LlmRelationExtractor;
import com.eonmux.excalibur.ingest.graph.RelationExtractor;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The write side of the namespace header.
 *
 * <p>{@code X-Excalibur-Namespace} was honoured by every graph route and by nothing that writes:
 * ingestion staged every document into the shared tenant, so a named namespace was permanently
 * empty and could only ever decline a query. These tests are the claim that a source registered
 * against a namespace puts its facts in that namespace - and, just as much, that a source
 * registered without one still puts them in the shared namespace, because a single-tenant node
 * must not acquire multi-tenancy by accident.</p>
 */
@DisplayName("Routing staged facts to a source's namespace")
class NamespaceRoutingGraphIngestSinkTest {

    private static Config config() {
        return new Config(new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0")));
    }

    private static GraphTenants tenants(Path root, int maxOpen) throws IOException {
        return new GraphTenants(root, config(), MappingBudget.detect(), maxOpen);
    }

    /** An extractor that finds one fact in every document. */
    private static RelationExtractor oneFact() {
        return (documentKey, text) -> List.of(ExtractedTriple.alwaysValid(
            "Ada", "knows", "Babbage", Provenance.of(SourceType.DOCUMENT, 1, 0.9, 1)));
    }

    /** A registry of source ids and the namespaces they were registered in. */
    private static NamespaceRoutingGraphIngestSink.SourceNamespaces registered(
            Map<String, NamespaceId> bySourceId) {
        Map<String, NamespaceId> copy = new ConcurrentHashMap<>(bySourceId);
        return sourceId -> Optional.ofNullable(copy.get(sourceId));
    }

    /** How many facts a namespace has staged. */
    private static long staged(GraphTenants tenants, NamespaceId namespace) {
        try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(namespace)) {
            return lease.resource().staging().stagedCount();
        }
    }

    @Test
    @DisplayName("stages a document into the namespace its source was registered in")
    void stagesIntoTheSourcesNamespace(@TempDir Path root) throws IOException {
        NamespaceId acme = NamespaceId.of("acme");
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", acme)), oneFact());

            sink.onDocumentPublished("docs", "docs:ada", "Ada knows Babbage.");

            assertThat(staged(tenants, acme))
                .as("nothing could put a fact into a named namespace before this: the header "
                    + "opened one and ingestion staged everything into the shared tenant")
                .isEqualTo(1);
            assertThat(tenants.shared().staging().stagedCount())
                .as("one tenant's facts must not appear in the index every tenant reads")
                .isZero();
            assertThat(sink.factsStaged()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a document stages into the namespace of the source that ingested it, "
        + "whichever registered source its key begins with")
    void theIngestingSourceOwnsADocument(@TempDir Path root) throws IOException {
        // A source id may contain a colon, so the key team:wiki:home.md is document
        // wiki:home.md of source team as readily as a document of team:wiki. Only the source
        // ingestion names can say which.
        NamespaceId team = NamespaceId.of("team");
        NamespaceId wiki = NamespaceId.of("wiki");
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("team", team, "team:wiki", wiki)),
                (documentKey, text) -> List.of(ExtractedTriple.alwaysValid("Ada", "knows",
                    "Babbage", Provenance.of(SourceType.DOCUMENT,
                        LlmRelationExtractor.sourceIdOf(documentKey), 0.9, 1))));

            sink.onDocumentPublished("team", "team:wiki:home.md", "Ada knows Babbage.");
            sink.onDocumentPublished("team:wiki", "team:wiki:faq.md", "Ada knows Babbage.");

            assertThat(staged(tenants, team))
                .as("wiki:home.md of source team belongs in the namespace team was registered "
                    + "in, however much of its key the longer source id matches")
                .isEqualTo(1);
            assertThat(staged(tenants, wiki)).isEqualTo(1);

            sink.onDocumentRemoved("team", "team:wiki:home.md");

            assertThat(staged(tenants, team))
                .as("its removal withdraws from the same namespace")
                .isZero();
            assertThat(staged(tenants, wiki))
                .as("and leaves the other source's namespace alone")
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a namespace the node refuses stages nothing, and owes no withdrawal")
    void aRefusedNamespaceStagesNothing(@TempDir Path root) throws IOException {
        // A source registered into a namespace that was dropped since, on a node that does not
        // create namespaces on demand: nothing is staged there, so nothing is owed there either.
        NamespaceId gone = NamespaceId.of("gone");
        Map<String, String> props = new HashMap<>(Map.of(
            "excalibur.graph.ram-buffer-mb", "4.0",
            "excalibur.graph.require-explicit-namespaces", "true"));
        try (GraphTenants tenants = new GraphTenants(root, new Config(props),
                MappingBudget.detect(), 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", gone)), oneFact());

            sink.onDocumentPublished("docs", "docs:ada", "Ada knows Babbage.");
            sink.onDocumentRemoved("docs", "docs:ada");

            assertThat(sink.factsStaged()).isZero();
            assertThat(sink.tenantFailures()).isEqualTo(2);
            assertThat(tenants.unrecordedWithdrawals())
                .as("a withdrawal owed to a namespace that does not exist would be retried by "
                    + "every sweep for the life of the process")
                .isZero();
            assertThat(root.resolve("gone")).doesNotExist();
        }
    }

    @Test
    @DisplayName("stages a document's facts as stated on the document's own date")
    void carriesTheDocumentDate(@TempDir Path root) throws IOException {
        // The date is what orders two documents' values of a single-valued relation; lost on the
        // way to the tenant, every fact would be ordered by the day it was crawled instead.
        NamespaceId acme = NamespaceId.of("acme");
        java.time.LocalDate written = java.time.LocalDate.of(2021, 5, 4);
        long lastModified = written.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
            .toEpochMilli();
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", acme)), oneFact());

            sink.onDocumentPublished("docs", "docs:ada", "Ada knows Babbage.", lastModified);

            try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(acme)) {
                java.util.List<Integer> stated = new java.util.ArrayList<>();
                lease.resource().staging().forEachStaged(
                    (s, r, o, from, to, statedOn, provenance) -> stated.add(statedOn));
                assertThat(stated).containsExactly(Math.toIntExact(written.toEpochDay()));
            }
        }
    }

    @Test
    @DisplayName("stages a source registered without a namespace into the shared one")
    void stagesASourceWithNoNamespaceIntoShared(@TempDir Path root) throws IOException {
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", NamespaceId.SHARED)), oneFact());

            sink.onDocumentPublished("docs", "docs:ada", "Ada knows Babbage.");

            assertThat(tenants.shared().staging().stagedCount())
                .as("a node that never sees the header must behave exactly as it did before "
                    + "ingestion learned about namespaces")
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("keeps two tenants' facts apart")
    void keepsTenantsApart(@TempDir Path root) throws IOException {
        NamespaceId acme = NamespaceId.of("acme");
        NamespaceId beta = NamespaceId.of("beta");
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("acme-docs", acme, "beta-docs", beta)), oneFact());

            sink.onDocumentPublished("acme-docs", "acme-docs:ada", "Ada knows Babbage.");
            sink.onDocumentPublished("beta-docs", "beta-docs:ada", "Ada knows Babbage.");
            sink.onDocumentPublished("beta-docs", "beta-docs:grace", "Ada knows Babbage.");

            assertThat(staged(tenants, acme)).isEqualTo(1);
            assertThat(staged(tenants, beta)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("declines to stage a document no registered source owns")
    void declinesADocumentWithNoSource(@TempDir Path root) throws IOException {
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of()), oneFact());

            sink.onDocumentPublished("deregistered", "deregistered:ada", "Ada knows Babbage.");

            assertThat(tenants.shared().staging().stagedCount())
                .as("a source deregistered mid-crawl has no namespace to name, and the shared "
                    + "one is read by every tenant: losing one document's facts is the lesser "
                    + "failure, and it is counted rather than silent")
                .isZero();
            assertThat(sink.unresolvedDocuments()).isEqualTo(1);
            assertThat(sink.documentsSeen()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("finds the source of a document id that contains colons")
    void findsTheSourceOfAColonBearingDocumentId(@TempDir Path root) throws IOException {
        NamespaceId acme = NamespaceId.of("acme");
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("site", acme)), oneFact());

            // An HTTP source's document ids are absolute URLs, so the key cannot be split on
            // its last colon and a source id is not guaranteed to be free of them either.
            sink.onDocumentPublished("site", "site:https://example.com/a", "Ada knows Babbage.");

            assertThat(staged(tenants, acme)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("releases each tenant, so the bound on open graphs still holds")
    void releasesEachTenant(@TempDir Path root) throws IOException {
        try (GraphTenants tenants = tenants(root, 2)) {
            Map<String, NamespaceId> sources = new HashMap<>();
            for (int i = 0; i < 6; i++) {
                sources.put("source-" + i, NamespaceId.of("tenant-" + i));
            }
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(sources), oneFact());

            for (int i = 0; i < 6; i++) {
                sink.onDocumentPublished("source-" + i, "source-" + i + ":ada",
                    "Ada knows Babbage.");
            }

            assertThat(tenants.openCount())
                .as("a lease held past the staging call would pin every namespace ingestion had "
                    + "ever touched against the eviction that keeps this process inside its "
                    + "mapping budget")
                .isLessThanOrEqualTo(2);
            assertThat(tenants.evictionCount()).isPositive();
            assertThat(sink.factsStaged()).isEqualTo(6);
            assertThat(staged(tenants, NamespaceId.of("tenant-0")))
                .as("an evicted tenant's staged facts are on disk, not lost with the eviction")
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an extractor that throws costs the facts, not the document")
    void anExtractorThatThrowsDoesNotPropagate(@TempDir Path root) throws IOException {
        NamespaceId acme = NamespaceId.of("acme");
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", acme)),
                (documentKey, text) -> {
                    throw new IllegalStateException("the model is refusing calls");
                });

            assertThatCode(() -> sink.onDocumentPublished("docs", "docs:ada", "Ada knows Babbage."))
                .as("the document is published and counted by the time this runs, so throwing "
                    + "would report a loss that did not happen")
                .doesNotThrowAnyException();
            assertThat(staged(tenants, acme)).isZero();
            assertThat(sink.extractionFailures()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a document with no facts opens no namespace's graph")
    void noFactsBorrowsNothing(@TempDir Path root) throws IOException {
        // With extraction disabled every document finds nothing. Borrowing anyway opened the
        // namespace and could evict another tenant's graph from under the query path, per
        // document published, to stage nothing.
        NamespaceId acme = NamespaceId.of("acme");
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", acme)), RelationExtractor.none());

            sink.onDocumentPublished("docs", "docs:ada", "Ada knows Babbage.");

            assertThat(tenants.openNamespaces()).doesNotContain(acme);
            assertThat(sink.documentsSeen()).isEqualTo(1);
            assertThat(sink.factsStaged()).isZero();
        }
    }

    @Test
    @DisplayName("a document with no key or no text is not a document")
    void ignoresAnEmptyDocument(@TempDir Path root) throws IOException {
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", NamespaceId.SHARED)), oneFact());

            sink.onDocumentPublished("docs", null, "Ada knows Babbage.");
            sink.onDocumentPublished("docs", "docs:ada", null);
            sink.onDocumentPublished("docs", "docs:ada", "   ");
            sink.onDocumentPublished("docs", "   ", "Ada knows Babbage.");

            assertThat(sink.documentsSeen()).isZero();
            assertThat(tenants.shared().staging().stagedCount()).isZero();
        }
    }

    /**
     * Finds "Ada works-at X" in a text naming X, with the provenance the LLM extractor gives a
     * document; finds nothing in a text that says "nothing", and fails on one that says "fail".
     */
    private static RelationExtractor employer() {
        return (documentKey, text) -> {
            if (text.equals("fail")) {
                throw new IllegalStateException("the model is refusing calls");
            }
            if (text.equals("nothing")) {
                return List.of();
            }
            return List.of(ExtractedTriple.alwaysValid("Ada", "works-at", text,
                Provenance.of(SourceType.DOCUMENT, LlmRelationExtractor.sourceIdOf(documentKey),
                    0.9, 1)));
        };
    }

    /** The objects of the facts a namespace has staged, in staging order. */
    private static List<String> employers(GraphTenants tenants, NamespaceId namespace)
            throws IOException {
        try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(namespace)) {
            var staging = lease.resource().staging();
            Map<Integer, String> names = new HashMap<>();
            for (String label : List.of("Acme", "Globex", "Initech")) {
                names.put(staging.entityIdOf(label), label);
            }
            List<String> objects = new java.util.ArrayList<>();
            staging.forEach((subject, relation, object, from, to, provenance) ->
                objects.add(names.get(object)));
            return objects;
        }
    }

    @Test
    @DisplayName("a replaced document's earlier facts are withdrawn from its namespace")
    void aReplacementWithdrawsTheEarlierVersion(@TempDir Path root) throws IOException {
        NamespaceId acme = NamespaceId.of("acme");
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", acme)), employer());
            sink.onDocumentPublished("docs", "docs:ada", "Acme", 1L);
            sink.onDocumentPublished("docs", "docs:other", "Initech", 1L);

            sink.onDocumentReplaced("docs", "docs:ada", "Globex", 2L);

            assertThat(employers(tenants, acme))
                .as("the next build must not publish where the document used to say Ada worked")
                .containsExactly("Initech", "Globex");
            assertThat(sink.recordsWithdrawn()).isPositive();
            assertThat(sink.withdrawalFailures()).isZero();
            assertThat(sink.toString()).contains("withdrawn").contains("0 withdrawal failures");
        }
    }

    @Test
    @DisplayName("a replacement withdraws even when the new version yields no facts")
    void aReplacementWithNoFactsStillWithdraws(@TempDir Path root) throws IOException {
        NamespaceId acme = NamespaceId.of("acme");
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", acme)), employer());
            sink.onDocumentPublished("docs", "docs:ada", "Acme", 1L);
            sink.onDocumentPublished("docs", "docs:bob", "Initech", 1L);

            sink.onDocumentReplaced("docs", "docs:ada", "nothing", 2L);
            sink.onDocumentReplaced("docs", "docs:bob", "fail", 2L);

            assertThat(employers(tenants, acme))
                .as("what the earlier versions said is not true of the published documents")
                .isEmpty();
            assertThat(sink.extractionFailures()).isEqualTo(1);
            assertThat(sink.factsStaged()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a replacement is ignored without a key, and declined without a namespace")
    void aReplacementWithoutAKeyOrNamespace(@TempDir Path root) throws IOException {
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", NamespaceId.SHARED)), employer());

            sink.onDocumentReplaced("docs", null, "Acme", 1L);
            sink.onDocumentReplaced("docs", " ", "Acme", 1L);
            assertThat(sink.documentsSeen()).isZero();

            sink.onDocumentReplaced("gone", "gone:ada", "Acme", 1L);
            assertThat(sink.unresolvedDocuments()).isEqualTo(1);
            assertThat(tenants.shared().staging().stagedCount()).isZero();
        }
    }

    @Test
    @DisplayName("a removed document's facts are withdrawn from its namespace, and only its own")
    void aRemovalWithdrawsTheDocument(@TempDir Path root) throws IOException {
        NamespaceId acme = NamespaceId.of("acme");
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", acme)), employer());
            sink.onDocumentPublished("docs", "docs:ada", "Acme", 1L);
            sink.onDocumentPublished("docs", "docs:other", "Initech", 1L);

            sink.onDocumentRemoved("docs", "docs:ada");

            assertThat(employers(tenants, acme))
                .as("the next build must not publish what a document the source dropped said")
                .containsExactly("Initech");
            assertThat(sink.recordsWithdrawn()).isPositive();
            assertThat(sink.withdrawalFailures()).isZero();
            assertThat(sink.documentsSeen())
                .as("a removal is not a published document offered to the graph")
                .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a removal is ignored without a key, and declined without a namespace")
    void aRemovalWithoutAKeyOrNamespace(@TempDir Path root) throws IOException {
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", NamespaceId.SHARED)), employer());
            sink.onDocumentPublished("docs", "docs:ada", "Acme", 1L);

            sink.onDocumentRemoved("docs", null);
            sink.onDocumentRemoved("docs", " ");
            assertThat(sink.unresolvedDocuments()).isZero();

            sink.onDocumentRemoved("gone", "gone:ada");
            assertThat(sink.unresolvedDocuments()).isEqualTo(1);
            assertThat(tenants.shared().staging().stagedCount())
                .as("a removal no namespace owns must not withdraw from the shared one")
                .isEqualTo(1);
        }
    }

    /**
     * Makes a namespace's staging refuse every erase until the returned path is removed.
     *
     * <p>A real refusal rather than a mocked one: an erase writes its rewrite beside the log it
     * replaces, and a directory standing there fails it without touching the store.</p>
     */
    private static Path refuseErasures(Path root, NamespaceId namespace) throws IOException {
        Path blocker = root.resolve(namespace.value()).resolve("graph-staging")
            .resolve(com.eonmux.excalibur.graph.staging.GraphStagingStore.TRIPLE_LOG + ".erasing");
        java.nio.file.Files.createDirectories(blocker.resolve("held"));
        return blocker;
    }

    private static void removeBlocker(Path blocker) throws IOException {
        java.nio.file.Files.delete(blocker.resolve("held"));
        java.nio.file.Files.delete(blocker);
    }

    @Test
    @DisplayName("a removal the staging refused is withdrawn by a later sweep")
    void aRefusedRemovalIsWithdrawnBySweep(@TempDir Path root) throws IOException {
        NamespaceId acme = NamespaceId.of("acme");
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", acme)), employer());
            sink.onDocumentPublished("docs", "docs:ada", "Acme", 1L);
            sink.onDocumentPublished("docs", "docs:other", "Initech", 1L);
            Path blocker = refuseErasures(root, acme);

            sink.onDocumentRemoved("docs", "docs:ada");
            assertThat(sink.withdrawalFailures()).isEqualTo(1);
            assertThat(employers(tenants, acme)).containsExactly("Acme", "Initech");

            removeBlocker(blocker);
            tenants.compactDueErasures();

            assertThat(employers(tenants, acme))
                .as("nothing reports a removed document again, so the sweep is what withdraws it")
                .containsExactly("Initech");
        }
    }

    @Test
    @DisplayName("a build makes an owed withdrawal before it publishes staging")
    void aBuildWithdrawsWhatIsOwedFirst(@TempDir Path root) throws IOException {
        NamespaceId acme = NamespaceId.of("acme");
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", acme)), employer());
            sink.onDocumentPublished("docs", "docs:ada", "Acme", 1L);
            sink.onDocumentPublished("docs", "docs:other", "Initech", 1L);
            Path blocker = refuseErasures(root, acme);
            sink.onDocumentRemoved("docs", "docs:ada");
            removeBlocker(blocker);

            tenants.build(acme, () -> false);

            assertThat(employers(tenants, acme))
                .as("the build must not publish what a removed document said")
                .containsExactly("Initech");
        }
    }

    @Test
    @DisplayName("a refused replacement stages nothing new, and a restart still withdraws the old")
    void aRefusedReplacementIsWithdrawnAfterARestart(@TempDir Path root) throws IOException {
        NamespaceId acme = NamespaceId.of("acme");
        Path blocker;
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", acme)), employer());
            sink.onDocumentPublished("docs", "docs:ada", "Acme", 1L);
            blocker = refuseErasures(root, acme);

            sink.onDocumentReplaced("docs", "docs:ada", "Globex", 2L);

            assertThat(employers(tenants, acme))
                .as("the new version would be taken by the owed withdrawal, so it is not staged")
                .containsExactly("Acme");
        }
        removeBlocker(blocker);

        try (GraphTenants tenants = tenants(root, 4)) {
            assertThat(employers(tenants, acme))
                .as("made when the namespace opened again, from the record on disk")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("a removal in a namespace that will not open is withdrawn once it does")
    void aRemovalInANamespaceThatWillNotOpen(@TempDir Path root) throws IOException {
        NamespaceId acme = NamespaceId.of("acme");
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", acme)), employer());
            sink.onDocumentPublished("docs", "docs:ada", "Acme", 1L);
            sink.onDocumentPublished("docs", "docs:other", "Initech", 1L);
            tenants.invalidate(acme);
            // A record of owed withdrawals the tenant cannot open fails the whole open.
            Path record = root.resolve("acme").resolve("graph-staging")
                .resolve(com.eonmux.excalibur.ingest.graph.StagedWithdrawals.LOG_FILE);
            java.nio.file.Files.delete(record);
            java.nio.file.Files.createDirectories(record.resolve("held"));

            sink.onDocumentRemoved("docs", "docs:ada");

            assertThat(sink.tenantFailures()).isEqualTo(1);
            assertThat(tenants.unrecordedWithdrawals()).isEqualTo(1);
            tenants.compactDueErasures();
            assertThat(tenants.unrecordedWithdrawals()).as("still will not open").isEqualTo(1);

            java.nio.file.Files.delete(record.resolve("held"));
            java.nio.file.Files.delete(record);
            tenants.compactDueErasures();

            assertThat(tenants.unrecordedWithdrawals()).isZero();
            assertThat(employers(tenants, acme)).containsExactly("Initech");
        }
    }

    @Test
    @DisplayName("a withdrawal owed while the namespace was opened by another request is handed "
        + "to it at once")
    void anOwedWithdrawalReachesAResidentTenant(@TempDir Path root) throws IOException {
        NamespaceId acme = NamespaceId.of("acme");
        try (GraphTenants tenants = tenants(root, 4)) {
            NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of("docs", acme)), employer());
            sink.onDocumentPublished("docs", "docs:ada", "Acme", 1L);

            tenants.oweWithdrawal(acme, "docs:ada");

            assertThat(tenants.unrecordedWithdrawals()).isZero();
            assertThat(employers(tenants, acme)).isEmpty();
        }
    }

    @Test
    @DisplayName("a withdrawal owed in a namespace since dropped is forgotten")
    void anOwedWithdrawalInADroppedNamespace(@TempDir Path root) throws IOException {
        NamespaceId acme = NamespaceId.of("acme");
        NamespaceId gone = NamespaceId.of("gone");
        try (GraphTenants tenants = tenants(root, 4)) {
            tenants.borrow(acme).close();
            tenants.invalidate(acme);
            tenants.oweWithdrawal(acme, "docs:ada");
            tenants.oweWithdrawal(gone, "docs:bob");
            assertThat(tenants.unrecordedWithdrawals()).isEqualTo(2);

            tenants.drop(acme);
            assertThat(tenants.unrecordedWithdrawals())
                .as("its staging went with it")
                .isEqualTo(1);
            tenants.compactDueErasures();

            assertThat(tenants.unrecordedWithdrawals())
                .as("a namespace with no directory has nothing staged to withdraw")
                .isZero();
            assertThat(tenants.namespacesOnDisk())
                .as("and the sweep did not create one to find that out")
                .doesNotContain(gone);
        }
    }

    @Test
    @DisplayName("refuses to be built without the three things it routes with")
    void refusesToBeBuiltIncomplete(@TempDir Path root) throws IOException {
        try (GraphTenants tenants = tenants(root, 2)) {
            assertThatThrownBy(() -> new NamespaceRoutingGraphIngestSink(
                null, registered(Map.of()), oneFact()))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new NamespaceRoutingGraphIngestSink(
                tenants, null, oneFact()))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new NamespaceRoutingGraphIngestSink(
                tenants, registered(Map.of()), null))
                .isInstanceOf(NullPointerException.class);
        }
    }
}
