package com.eonmux.excalibur.api.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for reading a query parameter without letting encoding change what was asked for.
 */
@DisplayName("QueryParams")
class QueryParamsTest {

    @Test
    @DisplayName("reads a parameter's value")
    void readsAValue() {
        assertThat(QueryParams.value("source_id=docs", "source_id")).isEqualTo("docs");
    }

    @Test
    @DisplayName("reads one parameter from among several")
    void readsOneOfSeveral() {
        assertThat(QueryParams.value("a=1&source_id=docs&b=2", "source_id")).isEqualTo("docs");
    }

    @Test
    @DisplayName("an absent parameter is null, not empty")
    void absentIsNull() {
        assertThat(QueryParams.value("a=1", "source_id")).isNull();
        assertThat(QueryParams.value(null, "source_id")).isNull();
        assertThat(QueryParams.value("", "source_id")).isNull();
    }

    @Test
    @DisplayName("a bare parameter with no value reads as empty")
    void bareParameterIsEmpty() {
        assertThat(QueryParams.value("source_id", "source_id")).isEmpty();
    }

    @Test
    @DisplayName("percent-encoding is decoded")
    void decodesEncoding() {
        assertThat(QueryParams.value("source_id=my%20docs", "source_id")).isEqualTo("my docs");
    }

    @Test
    @DisplayName("an encoded delimiter stays inside the value")
    void encodedDelimiterIsNotASplit() {
        // The reason this reads the raw query: decoding first turns %26 into a separator and
        // truncates the value at it.
        assertThat(QueryParams.value("source_id=a%26b", "source_id")).isEqualTo("a&b");
        assertThat(QueryParams.value("source_id=a%3Db", "source_id")).isEqualTo("a=b");
    }

    @Test
    @DisplayName("a malformed escape is returned as it stands rather than throwing")
    void malformedEscapeSurvives() {
        assertThat(QueryParams.value("source_id=100%", "source_id")).isEqualTo("100%");
    }

    @Test
    @DisplayName("a parameter whose name is a prefix of another is not confused with it")
    void prefixNamesAreDistinct() {
        assertThat(QueryParams.value("source_id_extra=no&source_id=yes", "source_id"))
            .isEqualTo("yes");
    }
}
