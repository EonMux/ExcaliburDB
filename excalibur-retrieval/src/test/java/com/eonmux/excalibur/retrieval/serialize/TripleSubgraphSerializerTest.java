package com.eonmux.excalibur.retrieval.serialize;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.subgraph.Subgraph;
import com.eonmux.excalibur.graph.subgraph.SubgraphEdge;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripleSubgraphSerializerTest {

    private final TripleSubgraphSerializer serializer = new TripleSubgraphSerializer();
    private final TokenCounter counter = BpeTokenCounter.defaultCounter();

    private static SubgraphEdge edge(int subject, int relation, int object, long index,
                                     int depth, int confidence) {
        return new SubgraphEdge(subject, relation, object, index,
            Integer.MIN_VALUE, TemporalFilter.OPEN,
            new Provenance(SourceType.KNOWLEDGE_BASE, index, confidence, 1), depth);
    }

    private SerializationBudget budget(int maxTokens) {
        return new SerializationBudget(maxTokens, counter);
    }

    /**
     * A budget that fits exactly one fact of the shape {@code probe} produces.
     *
     * <p>Measured rather than guessed: the cost of a fact line depends on the tokenizer, the
     * labels and the provenance rendering, so a hard-coded number would make these ranking tests
     * fail for reasons that have nothing to do with ranking.
     */
    private int budgetForOneFact(SubgraphEdge probe) {
        Subgraph single = new Subgraph.Builder().seed(probe.subject()).edge(probe).build();
        return serializer.serialize(single, budget(100_000), SeedRelevance.uniform()).tokens();
    }

    @Test
    @DisplayName("an empty subgraph serialises to nothing")
    void emptySubgraph() {
        SerializedSubgraph result = serializer.serialize(
            Subgraph.empty(), budget(1000), SeedRelevance.uniform());

        assertThat(result.text()).isEmpty();
        assertThat(result.tokens()).isZero();
        assertThat(result.edgesIncluded()).isZero();
        assertThat(result.wasTruncated()).isFalse();
    }

    @Test
    @DisplayName("facts and their entities are rendered")
    void rendersFactsAndEntities() {
        Subgraph subgraph = new Subgraph.Builder()
            .seed(1)
            .edge(edge(1, 5, 2, 0, 1, 95))
            .build();

        SerializedSubgraph result = serializer.serialize(
            subgraph, budget(1000), SeedRelevance.uniform());

        assertThat(result.text()).contains("# entities").contains("# facts");
        assertThat(result.text()).contains("E1").contains("E2").contains("R5");
        assertThat(result.edgesIncluded()).isEqualTo(1);
        assertThat(result.format()).isEqualTo("triples-v1");
    }

    @Test
    @DisplayName("provenance reaches the model rather than being stored and dropped")
    void provenanceIsSerialized() {
        // Provenance the model never sees cannot make it weigh a curated claim differently from a
        // guess, which is the whole reason for storing it.
        Subgraph subgraph = new Subgraph.Builder()
            .seed(1)
            .edge(new SubgraphEdge(1, 5, 2, 0, Integer.MIN_VALUE, TemporalFilter.OPEN,
                new Provenance(SourceType.CONVERSATION, 77L, 40, 3), 1))
            .build();

        SerializedSubgraph result = serializer.serialize(
            subgraph, budget(1000), SeedRelevance.uniform());

        assertThat(result.text())
            .contains("conversation").contains("77").contains("conf=40").contains("ext=v3");
    }

    @Test
    @DisplayName("valid intervals are rendered as dates")
    void intervalsAreRendered() {
        int from = Math.toIntExact(java.time.LocalDate.of(2020, 1, 1).toEpochDay());
        int to = Math.toIntExact(java.time.LocalDate.of(2022, 6, 15).toEpochDay());

        Subgraph closed = new Subgraph.Builder().seed(1)
            .edge(new SubgraphEdge(1, 5, 2, 0, from, to, Provenance.UNKNOWN, 1)).build();
        Subgraph open = new Subgraph.Builder().seed(1)
            .edge(new SubgraphEdge(1, 5, 2, 0, from, TemporalFilter.OPEN, Provenance.UNKNOWN, 1))
            .build();

        assertThat(serializer.serialize(closed, budget(1000), SeedRelevance.uniform()).text())
            .contains("2020-01-01..2022-06-15");
        assertThat(serializer.serialize(open, budget(1000), SeedRelevance.uniform()).text())
            .contains("2020-01-01..present");
    }

    @Test
    @DisplayName("an entity mentioned by several facts is described once")
    void entitiesAreDeduplicated() {
        // Repeating an entity wastes budget and presents one entity seen repeatedly as though it
        // were corroboration from several places.
        Subgraph subgraph = new Subgraph.Builder()
            .seed(1)
            .edge(edge(1, 5, 2, 0, 1, 90))
            .edge(edge(1, 6, 3, 1, 1, 90))
            .edge(edge(1, 7, 4, 2, 1, 90))
            .build();

        String text = serializer.serialize(subgraph, budget(2000), SeedRelevance.uniform()).text();

        int occurrences = text.split("\n")[0].isEmpty() ? 0 : count(text, "(id=1)");
        assertThat(occurrences)
            .as("entity 1 appears in three facts but is described once")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("the budget is a hard ceiling, measured with the real tokenizer")
    void budgetIsHard() {
        Subgraph.Builder builder = new Subgraph.Builder().seed(0);
        for (int i = 1; i <= 200; i++) {
            builder.edge(edge(0, i % 7, i, i, 1, 90));
        }
        Subgraph subgraph = builder.build();

        for (int maxTokens : new int[] { 50, 100, 250, 500 }) {
            SerializedSubgraph result =
                serializer.serialize(subgraph, budget(maxTokens), SeedRelevance.uniform());

            assertThat(counter.count(result.text()))
                .as("actual token cost at a budget of %d", maxTokens)
                .isLessThanOrEqualTo(maxTokens);
            assertThat(result.tokens()).isEqualTo(counter.count(result.text()));
        }
    }

    @Test
    @DisplayName("what was dropped is reported, so a caller knows it is seeing a subset")
    void reportsTruncation() {
        Subgraph.Builder builder = new Subgraph.Builder().seed(0);
        for (int i = 1; i <= 100; i++) {
            builder.edge(edge(0, 1, i, i, 1, 90));
        }

        SerializedSubgraph result =
            serializer.serialize(builder.build(), budget(120), SeedRelevance.uniform());

        assertThat(result.wasTruncated()).isTrue();
        assertThat(result.edgesDropped()).isPositive();
        assertThat(result.edgesIncluded() + result.edgesDropped()).isEqualTo(100);
        assertThat(result.retentionRatio()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("shallower facts are kept over deeper ones")
    void depthRanksFirst() {
        Subgraph subgraph = new Subgraph.Builder()
            .seed(0)
            .edge(edge(0, 1, 900, 0, 4, 99))
            .edge(edge(0, 1, 100, 1, 1, 50))
            .build();

        SerializedSubgraph result = serializer.serialize(
            subgraph, budget(budgetForOneFact(edge(0, 1, 100, 1, 1, 50))),
            SeedRelevance.uniform());

        assertThat(result.edgesIncluded()).isEqualTo(1);
        assertThat(result.text())
            .as("the one-hop fact survives even though the four-hop one is more confident")
            .contains("(id=100)")
            .doesNotContain("(id=900)");
    }

    @Test
    @DisplayName("at equal depth the more confident fact is kept")
    void confidenceBreaksDepthTies() {
        Subgraph subgraph = new Subgraph.Builder()
            .seed(0)
            .edge(edge(0, 1, 900, 0, 2, 20))
            .edge(edge(0, 1, 100, 1, 2, 99))
            .build();

        SerializedSubgraph result = serializer.serialize(
            subgraph, budget(budgetForOneFact(edge(0, 1, 100, 1, 2, 99))),
            SeedRelevance.uniform());

        assertThat(result.edgesIncluded()).isEqualTo(1);
        assertThat(result.text()).contains("(id=100)").doesNotContain("(id=900)");
    }

    @Test
    @DisplayName("seed relevance breaks ties the graph alone cannot")
    void seedRelevanceBreaksRemainingTies() {
        Subgraph subgraph = new Subgraph.Builder()
            .seed(0)
            .edge(edge(0, 1, 900, 0, 2, 80))
            .edge(edge(0, 1, 100, 1, 2, 80))
            .build();

        SeedRelevance relevance = entityId -> entityId == 100 ? 10.0 : 0.0;
        SerializedSubgraph result = serializer.serialize(
            subgraph, budget(budgetForOneFact(edge(0, 1, 100, 1, 2, 80))), relevance);

        assertThat(result.edgesIncluded()).isEqualTo(1);
        assertThat(result.text()).contains("(id=100)").doesNotContain("(id=900)");
    }

    @Test
    @DisplayName("the same subgraph always serialises identically")
    void serialisationIsDeterministic() {
        // Without this an A/B test between formats measures sort instability alongside the format.
        Subgraph.Builder builder = new Subgraph.Builder().seed(0);
        for (int i = 1; i <= 20; i++) {
            builder.edge(edge(0, 1, i, i, 2, 80));
        }
        Subgraph subgraph = builder.build();

        String first = serializer.serialize(subgraph, budget(300), SeedRelevance.uniform()).text();
        String second = serializer.serialize(subgraph, budget(300), SeedRelevance.uniform()).text();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("a zero budget yields nothing rather than one oversized fact")
    void zeroBudget() {
        Subgraph subgraph = new Subgraph.Builder().seed(1).edge(edge(1, 5, 2, 0, 1, 90)).build();

        SerializedSubgraph result =
            serializer.serialize(subgraph, budget(0), SeedRelevance.uniform());

        assertThat(result.text()).isEmpty();
        assertThat(result.edgesIncluded()).isZero();
        assertThat(result.edgesDropped()).isEqualTo(1);
    }

    @Test
    @DisplayName("real labels are used when supplied")
    void customLabels() {
        TripleSubgraphSerializer named = new TripleSubgraphSerializer(
            id -> id == 1 ? "Ada_Lovelace" : "Note_G",
            relation -> "authored");

        Subgraph subgraph = new Subgraph.Builder().seed(1).edge(edge(1, 5, 2, 0, 1, 95)).build();

        assertThat(named.serialize(subgraph, budget(1000), SeedRelevance.uniform()).text())
            .contains("Ada_Lovelace -[authored]-> Note_G");
    }

    @Test
    @DisplayName("a negative budget is refused")
    void negativeBudget() {
        assertThatThrownBy(() -> new SerializationBudget(-1, counter))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the BPE counter disagrees with a character estimate on identifier-dense text")
    void bpeDiffersFromCharacterEstimate() {
        // The reason a real tokenizer is used: identifier-heavy text is exactly where a
        // characters-over-four estimate under-counts, and under-counting overruns the context.
        String identifierDense = "Q42 P569 Q1339 P800 Q193563 ERR_7742_TIMEOUT a1b2c3d4e5f6";
        int bpe = counter.count(identifierDense);
        int naive = identifierDense.length() / 4;

        assertThat(bpe)
            .as("BPE splits identifiers into many tokens; the estimate does not see it")
            .isGreaterThan(naive);
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            total++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return total;
    }

    @Test
    @DisplayName("a self-loop describes its entity once")
    void selfLoopDescribesItsEntityOnce() {
        // Reflexive relations - same_as, alias_of, supersedes on a self-corrected record - are
        // legal and storable. Described twice, the entity is charged twice against the budget
        // and reads to a model as two independent sources saying the same thing.
        SubgraphEdge selfLoop = edge(1, 0, 1, 0L, 1, 90);
        Subgraph subgraph = new Subgraph.Builder().seed(1).edge(selfLoop).build();

        SerializedSubgraph rendered =
            serializer.serialize(subgraph, budget(100_000), SeedRelevance.uniform());

        String entityBlock = rendered.text().split("# facts", 2)[0];
        long occurrences = entityBlock.lines().filter(line -> line.contains("id=1")).count();

        assertThat(occurrences).isEqualTo(1);
    }

    @Test
    @DisplayName("a self-loop costs one entity description, not two")
    void selfLoopIsChargedOnce() {
        SubgraphEdge selfLoop = edge(1, 0, 1, 0L, 1, 90);
        SubgraphEdge ordinary = edge(1, 0, 2, 1L, 1, 90);

        int selfLoopTokens = serializer.serialize(
            new Subgraph.Builder().seed(1).edge(selfLoop).build(),
            budget(100_000), SeedRelevance.uniform()).tokens();
        int ordinaryTokens = serializer.serialize(
            new Subgraph.Builder().seed(1).edge(ordinary).build(),
            budget(100_000), SeedRelevance.uniform()).tokens();

        assertThat(selfLoopTokens)
            .as("one entity described, where the two-endpoint edge describes two")
            .isLessThan(ordinaryTokens);
    }
}
