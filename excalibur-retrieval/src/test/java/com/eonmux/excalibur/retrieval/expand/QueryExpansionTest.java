package com.eonmux.excalibur.retrieval.expand;

import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Moving the semantic work from the corpus to the query.
 *
 * <p>The premise these tests exist to protect: a user searching for why a service dies when the
 * machine runs out of RAM should reach a document that says "OOM killer" and "SIGKILL", without
 * either document or query ever having been embedded. That is the whole argument for expanding the
 * query instead of permuting the corpus, and it is cheap only because the query is one sentence.</p>
 */
@DisplayName("Query expansion")
class QueryExpansionTest {

    private static final String OOM_DOC =
        "The kernel OOM killer sent SIGKILL to the process after the cgroup memory limit "
        + "was exceeded and the allocator returned ENOMEM.";
    private static final String TLS_DOC =
        "The TLS handshake failed because the certificate chain could not be verified "
        + "against the configured trust store.";

    /** A query budget longer than any expander here waits, so its own deadline decides. */
    private static final Duration BUDGET = Duration.ofSeconds(30);

    private static LexicalIndex indexed(Path dir) throws Exception {
        LexicalIndex index = LexicalIndex.open(dir, 8.0);
        index.put(1L, OOM_DOC, "corpus:oom.txt:chunk:0");
        index.put(2L, TLS_DOC, "corpus:tls.txt:chunk:0");
        index.refresh();
        return index;
    }

    /** Relative gap between the top two hits: how confidently a ranking discriminates. */
    private static double margin(RankedList list) {
        if (list.documents().size() < 2) {
            return 1.0;
        }
        double top = list.documents().get(0).score();
        double second = list.documents().get(1).score();
        return top <= 0 ? 0.0 : (top - second) / top;
    }

    @Nested
    @DisplayName("the expander")
    class Expanding {

        private LlmQueryExpander expanderReturning(String json) {
            LLMHttpClient client = mock(LLMHttpClient.class);
            when(client.completeWithCircuitBreaker(anyString(), anyString(), any()))
                .thenReturn(json);
            return new LlmQueryExpander(client);
        }

        @Test
        @DisplayName("the user's own query is always first and always outranks an expansion")
        void originalAlwaysLeads() {
            List<SearchFormulation> out = expanderReturning(
                "[{\"q\":\"OOM killer\",\"w\":0.9},{\"q\":\"ENOMEM\",\"w\":0.8}]")
                .expand("why does my service die when the machine runs out of RAM?", BUDGET)
                .formulations();

            assertThat(out.get(0).text()).isEqualTo(
                "why does my service die when the machine runs out of RAM?");
            assertThat(out.get(0).weight()).isEqualTo(SearchFormulation.ORIGINAL_WEIGHT);
            assertThat(out.subList(1, out.size()))
                .as("a guess about vocabulary must not outvote the words actually typed")
                .allSatisfy(f -> assertThat(f.weight()).isLessThan(SearchFormulation.ORIGINAL_WEIGHT));
        }

        @Test
        @DisplayName("the model's own weights order the expansions")
        void modelWeightsAreHonoured() {
            List<SearchFormulation> out = expanderReturning(
                "[{\"q\":\"memory exhaustion\",\"w\":0.6},{\"q\":\"OOM killer\",\"w\":0.9}]")
                .expand("service dies on low memory", BUDGET).formulations();

            assertThat(out).hasSize(3);
            assertThat(out.get(1).text()).isEqualTo("OOM killer");
            assertThat(out.get(1).weight()).isGreaterThan(out.get(2).weight());
        }

        @Test
        @DisplayName("a formulation the model is unsure of is dropped, not searched weakly")
        void weakFormulationsAreDropped() {
            // Measured on a real corpus, the low-confidence tail is where terms describing the
            // document rather than appearing in it collect ("plot summary", "literary
            // analysis"). Each is a clause that matches unrelated documents, so searching it
            // weakly dilutes the query instead of widening it.
            List<SearchFormulation> out = expanderReturning(
                "[{\"q\":\"OOM killer\",\"w\":0.9},{\"q\":\"literary analysis\",\"w\":0.4},"
                + "{\"q\":\"plot summary\",\"w\":0.2}]")
                .expand("service dies on low memory", BUDGET).formulations();

            assertThat(out.stream().map(SearchFormulation::text))
                .containsExactly("service dies on low memory", "OOM killer");
            assertThat(out).allSatisfy(f -> assertThat(f.weight())
                .isGreaterThanOrEqualTo(LlmQueryExpander.MIN_USEFUL_WEIGHT));
        }

        @Test
        @DisplayName("a model that fails costs the expansion, never the search")
        void failureDegradesToTheOriginal() {
            LLMHttpClient broken = mock(LLMHttpClient.class);
            when(broken.completeWithCircuitBreaker(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("circuit open"));

            Expansion out = new LlmQueryExpander(broken).expand("out of memory", BUDGET);

            assertThat(out.formulations()).singleElement()
                .satisfies(f -> assertThat(f.text()).isEqualTo("out of memory"));
            assertThat(out.fellBack())
                .as("the query as typed is a fallback, not the model's judgement")
                .isTrue();
        }

        @Test
        @DisplayName("unparseable or empty model output degrades to the original")
        void garbageDegradesToTheOriginal() {
            for (String junk : new String[] {"", "I think you mean OOM.", "[", "null", "{}"}) {
                Expansion out = expanderReturning(junk).expand("out of memory", BUDGET);
                assertThat(out.formulations())
                    .as("model said: %s", junk)
                    .singleElement()
                    .satisfies(f -> assertThat(f.text()).isEqualTo("out of memory"));
                assertThat(out.fellBack()).as("model said: %s", junk).isTrue();
            }
        }

        @Test
        @DisplayName("an expansion the model answered is not a fallback")
        void anAnsweredExpansionIsNotAFallback() {
            assertThat(expanderReturning("[{\"q\":\"OOM killer\",\"w\":0.9}]")
                .expand("out of memory", BUDGET).fellBack()).isFalse();
        }

        @Test
        @DisplayName("a query budget shorter than the expander's own deadline bounds the call")
        void theQueryBudgetBoundsTheCall() {
            // An expansion that waited its own 5s inside a 2s query would time out the whole
            // query; one bounded by the query's budget falls back and leaves BM25 its time.
            LLMHttpClient slow = mock(LLMHttpClient.class);
            when(slow.completeWithCircuitBreaker(anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(30_000);
                    return "[{\"q\":\"OOM killer\",\"w\":0.9}]";
                });

            long start = System.nanoTime();
            Expansion out = new LlmQueryExpander(slow, 20_000L)
                .expand("out of memory", Duration.ofMillis(200));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(out.fellBack()).isTrue();
            assertThat(elapsedMs)
                .as("the query's budget, not the expander's 20s, decided how long to wait")
                .isLessThan(5_000L);
        }

        @Test
        @DisplayName("a spent budget falls back without calling the model")
        void aSpentBudgetDoesNotCall() {
            LLMHttpClient client = mock(LLMHttpClient.class);

            Expansion out = new LlmQueryExpander(client).expand("out of memory", Duration.ZERO);

            assertThat(out.fellBack()).isTrue();
            assertThat(out.formulations()).containsExactly(
                SearchFormulation.original("out of memory"));
            org.mockito.Mockito.verifyNoInteractions(client);
        }

        @Test
        @DisplayName("a query with no text is not widened and is not a fallback")
        void aBlankQueryIsNotWidened() {
            LLMHttpClient client = mock(LLMHttpClient.class);

            Expansion out = new LlmQueryExpander(client).expand("  ", BUDGET);

            assertThat(out.formulations()).isEmpty();
            assertThat(out.fellBack()).isFalse();
            org.mockito.Mockito.verifyNoInteractions(client);
        }

        @Test
        @DisplayName("markdown fences and truncation are read through, not rejected")
        void toleratesRealisticModelOutput() {
            List<SearchFormulation> out = expanderReturning(
                "```json\n[{\"q\":\"OOM killer\",\"w\":0.9},{\"q\":\"SIGKILL\",\"w\":0.7}")
                .expand("service killed", BUDGET).formulations();

            assertThat(out).hasSize(3);
            assertThat(out.stream().map(SearchFormulation::text))
                .contains("OOM killer", "SIGKILL");
        }

        @Test
        @DisplayName("an over-confident weight is clamped; an under-confident one is dropped")
        void weightsAreClampedThenFiltered() {
            List<SearchFormulation> out = expanderReturning(
                "[{\"q\":\"OOM\",\"w\":5.0},{\"q\":\"ENOMEM\",\"w\":-2}]")
                .expand("memory", BUDGET).formulations();

            // 5.0 means "very sure" and is clamped to the ceiling; -2 is below any usable
            // confidence and is not searched at all.
            assertThat(out.stream().map(SearchFormulation::text))
                .containsExactly("memory", "OOM");
            assertThat(out.get(1).weight())
                .isGreaterThan(0.0).isLessThan(SearchFormulation.ORIGINAL_WEIGHT);
        }

        @Test
        @DisplayName("terms the model sent without a confidence are still searched")
        void unweightedTermsAreStillSearched() {
            // A chat model that emits the terms and forgets the weights is an ordinary
            // formatting failure. Defaulting them beneath the usefulness floor silently
            // discards every one, which turns the deployment whose entire semantic capability
            // is expansion into plain BM25 with nothing said about it.
            List<SearchFormulation> out = expanderReturning(
                "[{\"q\":\"OOM killer\"},{\"q\":\"ENOMEM\"}]")
                .expand("out of memory", BUDGET).formulations();

            assertThat(out.stream().map(SearchFormulation::text))
                .containsExactly("out of memory", "OOM killer", "ENOMEM");
            assertThat(out.subList(1, out.size()))
                .as("an unstated confidence must not outrank a stated one")
                .allSatisfy(f -> assertThat(f.weight())
                    .isLessThan(SearchFormulation.ORIGINAL_WEIGHT));
        }

        @Test
        @DisplayName("a query longer than a few sentences is expanded like any other")
        void aLongQueryIsStillExpanded() {
            // The API accepts a query up to excalibur.api.max-query-chars, and that bound
            // exists precisely to bound the outbound expansion call. A second, lower, silent
            // cap inside the expander means a 600-character query is answered by a different
            // retrieval strategy than a 500-character one, with nothing saying so.
            String longQuery = "why does my service die when the machine runs out of RAM? "
                .repeat(11);
            assertThat(longQuery.length()).isGreaterThan(512);

            List<SearchFormulation> out = expanderReturning(
                "[{\"q\":\"OOM killer\",\"w\":0.9}]").expand(longQuery, BUDGET).formulations();

            assertThat(out.stream().map(SearchFormulation::text)).contains("OOM killer");
        }

        @Test
        @DisplayName("a model that does not answer in time costs the expansion, not the query")
        void aSlowModelIsAbandonedOnItsOwnDeadline() {
            // The client's own timeout is sized for ingestion, where nobody holds a socket
            // open. One expansion call on that budget outlasts every query budget the API
            // allows, so the expansion carries a deadline of its own.
            LLMHttpClient slow = mock(LLMHttpClient.class);
            when(slow.completeWithCircuitBreaker(anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(30_000);
                    return "[{\"q\":\"OOM killer\",\"w\":0.9}]";
                });

            long start = System.nanoTime();
            List<SearchFormulation> out =
                new LlmQueryExpander(slow, 300L).expand("out of memory", BUDGET)
                    .formulations();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(out).singleElement()
                .satisfies(f -> assertThat(f.text()).isEqualTo("out of memory"));
            assertThat(elapsedMs)
                .as("the query's budget is what the caller is holding a socket open for")
                .isLessThan(5_000L);
        }

        @Test
        @DisplayName("a withdrawn request stops the expansion rather than searching on")
        void anInterruptStopsTheSearchItBelongsTo() throws Exception {
            // Swallowing the interrupt lets an abandoned search run its full BM25 pass behind
            // a permit the node's concurrency bound says is free.
            java.util.concurrent.CountDownLatch calling =
                new java.util.concurrent.CountDownLatch(1);
            LLMHttpClient blocking = mock(LLMHttpClient.class);
            when(blocking.completeWithCircuitBreaker(anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    calling.countDown();
                    Thread.sleep(30_000);
                    return "[]";
                });
            java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
            Thread search = new Thread(() -> {
                try {
                    new LlmQueryExpander(blocking, 30_000L).expand("out of memory", BUDGET);
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });

            search.start();
            assertThat(calling.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            search.interrupt();
            search.join(5_000);

            assertThat(search.isAlive())
                .as("the search has to unwind so its permit goes back")
                .isFalse();
            assertThat(thrown.get())
                .as("a withdrawn request is not answered by searching what is left of it")
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        }

        @Test
        @DisplayName("duplicates and echoes of the query are dropped")
        void duplicatesAreDropped() {
            List<SearchFormulation> out = expanderReturning(
                "[{\"q\":\"OOM\",\"w\":0.9},{\"q\":\"oom\",\"w\":0.8},{\"q\":\"memory\",\"w\":0.7}]")
                .expand("memory", BUDGET).formulations();

            assertThat(out.stream().map(f -> f.text().toLowerCase()).distinct().count())
                .as("a duplicate is a second vote for the same evidence")
                .isEqualTo(out.size());
        }
    }

    @Nested
    @DisplayName("retrieval through the expansions")
    class Retrieving {

        @Test
        @DisplayName("a query sharing no words with the document still finds it")
        void bridgesVocabulary(@TempDir Path dir) throws Exception {
            // The question says "runs out of RAM"; the document says "OOM killer" and "ENOMEM".
            // BM25 alone cannot connect them, and no part of this pipeline embedded anything.
            try (LexicalIndex index = indexed(dir)) {
                String question = "why does my service die when the machine runs out of RAM?";

                RankedList plain = new com.eonmux.excalibur.retrieval.lexical.LuceneRanker(index)
                    .rank(RetrievalQuery.ofText(question), 5);

                LLMHttpClient client = mock(LLMHttpClient.class);
                when(client.completeWithCircuitBreaker(anyString(), anyString(), any()))
                    .thenReturn("[{\"q\":\"OOM killer\",\"w\":0.9},{\"q\":\"ENOMEM\",\"w\":0.8},"
                        + "{\"q\":\"cgroup memory limit\",\"w\":0.7}]");
                RankedList expanded = new ExpandedLexicalRanker(index, new LlmQueryExpander(client))
                    .rank(RetrievalQuery.ofText(question), 5);

                // Plain BM25 does return both documents, on incidental overlap ("the", "process"),
                // but it cannot tell them apart: the scores are within a few percent, which is
                // noise rather than a judgement. Expansion is what turns that into a decision.
                double plainMargin = margin(plain);
                double expandedMargin = margin(expanded);

                assertThat(plain.documents()).hasSize(2);
                assertThat(plainMargin)
                    .as("BM25 alone barely separates the right document from the wrong one")
                    .isLessThan(0.10);
                assertThat(expanded.documents().get(0).documentId())
                    .as("expansion reaches the OOM document")
                    .isEqualTo(1L);
                assertThat(expandedMargin)
                    .as("expansion separates them decisively (plain %.3f vs expanded %.3f)",
                        plainMargin, expandedMargin)
                    .isGreaterThan(0.5)
                    .isGreaterThan(plainMargin * 5);
            }
        }

        @Test
        @DisplayName("expansion does not drag in the unrelated document")
        void staysOnTopic(@TempDir Path dir) throws Exception {
            try (LexicalIndex index = indexed(dir)) {
                LLMHttpClient client = mock(LLMHttpClient.class);
                when(client.completeWithCircuitBreaker(anyString(), anyString(), any()))
                    .thenReturn("[{\"q\":\"OOM killer\",\"w\":0.9},{\"q\":\"SIGKILL\",\"w\":0.8}]");

                RankedList out = new ExpandedLexicalRanker(index, new LlmQueryExpander(client))
                    .rank(RetrievalQuery.ofText("process killed for using too much memory"), 5);

                assertThat(out.documents().get(0).documentId()).isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("expansions that agree on a term still cannot outrank the user's own word")
        void agreementCannotOutrankTheUser(@TempDir Path dir) throws Exception {
            // Two surviving formulations sharing one token sum past 1.0, and the shared token
            // then scores above the word the user actually typed. Both documents carry the
            // same length and the same document frequency, so the ranking between them is
            // decided by the weights alone.
            try (LexicalIndex index = LexicalIndex.open(dir, 8.0)) {
                index.put(1L, "alpha filler one two three four", "corpus:alpha.txt:chunk:0");
                index.put(2L, "bravo filler one two three four", "corpus:bravo.txt:chunk:0");
                index.refresh();

                QueryExpander agreeing = QueryExpander.infallible(text -> List.of(
                    SearchFormulation.original(text),
                    new SearchFormulation("bravo charlie", 0.9),
                    new SearchFormulation("bravo delta", 0.9)));

                RankedList out = new ExpandedLexicalRanker(index, agreeing)
                    .rank(RetrievalQuery.ofText("alpha"), 5);

                assertThat(out.documents().get(0).documentId())
                    .as("the word the user typed must still lead a guess two expansions agreed on")
                    .isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("with no expander at all it is plain BM25")
        void identityExpanderIsPlainBm25(@TempDir Path dir) throws Exception {
            try (LexicalIndex index = indexed(dir)) {
                RankedList out = new ExpandedLexicalRanker(index, QueryExpander.identity())
                    .rank(RetrievalQuery.ofText("certificate chain"), 5);

                assertThat(out.documents()).isNotEmpty();
                assertThat(out.documents().get(0).documentId()).isEqualTo(2L);
            }
        }
    }
}
