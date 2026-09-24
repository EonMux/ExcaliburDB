package com.eonmux.excalibur.core.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SourceScope")
class SourceScopeTest {

    /** Attribution as the registry would give it: team:wiki's documents are team:wiki's. */
    private static final ChunkSources REGISTERED = chunkId -> Map.of(
        "team:guide.md", "team",
        "team:wiki:home.md", "team:wiki",
        "other:notes.md", "other").get(ChunkIdentifiers.documentKeyOfChunk(chunkId));

    @Test
    @DisplayName("admits every chunk, and filters nothing, when it names no source")
    void allRestrictsNothing() {
        assertThat(SourceScope.ALL.isUnrestricted()).isTrue();
        assertThat(SourceScope.ALL.admits("anything:at:all", REGISTERED)).isTrue();
        assertThat(SourceScope.ALL.admits(null, REGISTERED)).isTrue();
        assertThat(SourceScope.ALL.admitting(null)).isNull();
    }

    @Test
    @DisplayName("admits only the chunks of the sources it names")
    void admitsTheNamedSources() {
        SourceScope scope = SourceScope.of(List.of("team"));

        assertThat(scope.admits("team:guide.md:chunk:0", REGISTERED)).isTrue();
        assertThat(scope.admits("other:notes.md:chunk:3", REGISTERED)).isFalse();
        assertThat(scope.admits(null, REGISTERED))
            .as("a result that carries no chunk id cannot be placed in any source")
            .isFalse();
    }

    @Test
    @DisplayName("tells a source from another whose id extends it")
    void doesNotAdmitALongerSourceId() {
        assertThat(SourceScope.of(List.of("team")).admits("team:wiki:home.md:chunk:0", REGISTERED))
            .as("team:wiki:home.md begins with team: and is team:wiki's")
            .isFalse();
        assertThat(SourceScope.of(List.of("team:wiki"))
            .admits("team:wiki:home.md:chunk:0", REGISTERED)).isTrue();
    }

    @Test
    @DisplayName("refuses a chunk no registered document holds")
    void refusesAnUnregisteredChunk() {
        assertThat(SourceScope.of(List.of("team")).admits("team:gone.md:chunk:0", REGISTERED))
            .isFalse();
    }

    @Test
    @DisplayName("asks about a chunk only when its name could be from a named source")
    void asksOnlyAboutCandidates() {
        List<String> asked = new ArrayList<>();
        ChunkSources counting = chunkId -> {
            asked.add(chunkId);
            return REGISTERED.sourceOf(chunkId);
        };
        SourceScope scope = SourceScope.of(List.of("team"));

        scope.admits("other:notes.md:chunk:0", counting);
        scope.admits("team:guide.md:chunk:0", counting);

        assertThat(asked).containsExactly("team:guide.md:chunk:0");
    }

    @Test
    @DisplayName("is a value: the same sources in any order and repeated are one scope")
    void isAValue() {
        SourceScope one = SourceScope.of(List.of("b", "a", "b"));
        SourceScope two = SourceScope.of(Set.of("a", "b"));

        assertThat(one).isEqualTo(two).hasSameHashCodeAs(two);
        assertThat(one.sourceIds()).containsExactly("a", "b");
        assertThat(one).isNotEqualTo(SourceScope.ALL);
        assertThat(one.toString()).contains("a", "b");
    }

    @Test
    @DisplayName("works as a filter on chunk ids")
    void filters() {
        var filter = SourceScope.of(List.of("other")).admitting(REGISTERED);

        assertThat(filter.test("other:notes.md:chunk:1")).isTrue();
        assertThat(filter.test("team:guide.md:chunk:1")).isFalse();
    }

    @Test
    @DisplayName("refuses a scope over no source, or over a blank or missing id")
    void refusesAnEmptyScope() {
        assertThatThrownBy(() -> SourceScope.of(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceScope.of(List.of(" ")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceScope.of(java.util.Arrays.asList("a", null)))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SourceScope.of(java.util.stream.IntStream
                .rangeClosed(0, SourceScope.MAX_SOURCES).mapToObj(i -> "s" + i).toList()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at most " + SourceScope.MAX_SOURCES);
        assertThatThrownBy(() -> SourceScope.of(List.of("a")).admitting(null))
            .isInstanceOf(NullPointerException.class);
    }
}
