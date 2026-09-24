package com.eonmux.excalibur.query.executor;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryRegistryKindTest {

    @Test
    @DisplayName("a query registered without a kind is a corpus search")
    void corpusByDefault() {
        QueryRegistry registry = new QueryRegistry();
        registry.register("q-1", new CancellationToken(), "corr-1");
        registry.register("q-2", new CancellationToken());

        assertThat(registry.getQueryInfo("q-1").kind()).isEqualTo(QueryRegistry.Kind.CORPUS);
        assertThat(registry.getQueryInfo("q-2").kind()).isEqualTo(QueryRegistry.Kind.CORPUS);
    }

    @Test
    @DisplayName("a graph query is listed as one, beside corpus searches")
    void graphQueriesAreListedAsGraph() {
        QueryRegistry registry = new QueryRegistry();
        registry.register("corpus-q", new CancellationToken(), "corr-c");
        registry.register("graph-q", new CancellationToken(), "corr-g", QueryRegistry.Kind.GRAPH);

        assertThat(registry.getActiveQueries())
            .extracting(QueryRegistry.ActiveQueryInfo::queryId, QueryRegistry.ActiveQueryInfo::kind)
            .containsExactlyInAnyOrder(
                Tuple.tuple("corpus-q", QueryRegistry.Kind.CORPUS),
                Tuple.tuple("graph-q", QueryRegistry.Kind.GRAPH));
    }

    @Test
    @DisplayName("a graph query is cancelled like any other")
    void graphQueriesCancel() {
        QueryRegistry registry = new QueryRegistry();
        CancellationToken token = new CancellationToken();
        registry.register("graph-q", token, null, QueryRegistry.Kind.GRAPH);

        assertThat(registry.cancel("graph-q")).isTrue();
        assertThat(token.isCancelled()).isTrue();
        assertThat(registry.getQueryInfo("graph-q").cancelled()).isTrue();
    }

    @Test
    @DisplayName("each kind has its wire name")
    void wireNames() {
        assertThat(QueryRegistry.Kind.CORPUS.wireName()).isEqualTo("corpus");
        assertThat(QueryRegistry.Kind.GRAPH.wireName()).isEqualTo("graph");
    }

    @Test
    @DisplayName("a missing kind is refused")
    void kindIsRequired() {
        assertThatThrownBy(() -> new QueryRegistry()
            .register("q", new CancellationToken(), null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("kind");
    }
}
