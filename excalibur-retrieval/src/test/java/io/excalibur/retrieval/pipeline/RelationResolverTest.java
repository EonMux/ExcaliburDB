package io.excalibur.retrieval.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Relation hint resolution")
class RelationResolverTest {

    private static final int COLLABORATED_WITH = 0;
    private static final int AUTHORED = 1;
    private static final int PART_OF = 2;
    private static final int IS = 3;

    private static RelationResolver resolver() {
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(COLLABORATED_WITH, "collaborated_with");
        names.put(AUTHORED, "authored");
        names.put(PART_OF, "partOf");
        names.put(IS, "is");
        return RelationResolver.over(names);
    }

    @Test
    @DisplayName("a question's bare verb reaches the graph's verb phrase")
    void verbReachesVerbPhrase() {
        // The whole point: cues are English ("collaborated"), relation names are whatever the
        // extractor wrote ("collaborated_with"), and nothing matches without meeting in between.
        assertThat(resolver().resolve("collaborated")).containsExactly(COLLABORATED_WITH);
    }

    @Test
    @DisplayName("casing and separators stop mattering")
    void normalisationCollapsesConventions() {
        assertThat(resolver().resolve("collaborated with")).containsExactly(COLLABORATED_WITH);
        assertThat(resolver().resolve("Collaborated_With")).containsExactly(COLLABORATED_WITH);
        assertThat(resolver().resolve("collaboratedWith")).containsExactly(COLLABORATED_WITH);
        assertThat(resolver().resolve("part of")).containsExactly(PART_OF);
        assertThat(resolver().resolve("part-of")).containsExactly(PART_OF);
    }

    @Test
    @DisplayName("a phrasing cue that names no relation resolves to nothing")
    void phrasingCueResolvesToNothing() {
        // These are the cues a classifier actually produces. They must not become a whitelist.
        assertThat(resolver().resolve("who did")).isEmpty();
        assertThat(resolver().resolve("between")).isEmpty();
        assertThat(resolver().resolve("related to")).isEmpty();
    }

    @Test
    @DisplayName("matching is by prefix, not by containment")
    void containmentDoesNotMatch() {
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(7, "livesBetweenCities");
        RelationResolver resolver = RelationResolver.over(names);

        assertThat(resolver.resolve("between"))
            .as("containment would let a generic cue claim any relation that happens to spell it")
            .isEmpty();
        assertThat(resolver.resolve("lives")).containsExactly(7);
    }

    @Test
    @DisplayName("a name too short to be distinctive never matches")
    void shortNamesAreNotMatchable() {
        // "is" is a prefix of a great many words. A filter built from that accident is worse than
        // no filter at all.
        assertThat(resolver().resolve("is")).isEmpty();
        assertThat(resolver().resolve("island")).isEmpty();
    }

    @Test
    @DisplayName("two relations recorded under the same name both resolve")
    void ambiguousNameResolvesToBoth() {
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(4, "worked_with");
        names.put(5, "workedWith");
        RelationResolver resolver = RelationResolver.over(names);

        assertThat(resolver.resolve("worked with"))
            .as("one of them is the right relation and this cannot tell which")
            .containsExactlyInAnyOrder(4, 5);
    }

    @Test
    @DisplayName("an unknown phrase resolves to nothing rather than to everything")
    void unknownPhraseResolvesToNothing() {
        assertThat(resolver().resolve("supersedes")).isEmpty();
        assertThat(resolver().resolve("")).isEmpty();
        assertThat(resolver().resolve(null)).isEmpty();
    }

    @Test
    @DisplayName("a graph with no relation names resolves nothing and does not fail")
    void emptyDictionaryIsUsable() {
        assertThat(RelationResolver.over(Map.of()).resolve("collaborated")).isEmpty();
        assertThatThrownBy(() -> RelationResolver.over(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
