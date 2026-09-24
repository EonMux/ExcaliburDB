package com.eonmux.excalibur.graph.provenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SourceType")
class SourceTypeTest {

    @Test
    @DisplayName("every live type keeps the byte earlier builds stored it as")
    void codesAreStable() {
        assertThat(SourceType.UNKNOWN.toByte()).isEqualTo((byte) 0);
        assertThat(SourceType.DOCUMENT.toByte()).isEqualTo((byte) 2);
        assertThat(SourceType.USER_ASSERTED.toByte()).isEqualTo((byte) 4);
        assertThat(SourceType.USER_ASSERTED.code()).isEqualTo(4);
    }

    @Test
    @DisplayName("every live type decodes back from its own byte")
    void roundTrips() {
        for (SourceType type : SourceType.values()) {
            assertThat(SourceType.fromByte(type.toByte())).isEqualTo(type);
            assertThat(SourceType.decode(type.toByte())).contains(type);
            assertThat(SourceType.isRetired(type.toByte())).isFalse();
        }
    }

    @Test
    @DisplayName("a retired code is recognised, and read as an unknown origin")
    void retiredCodes() {
        for (int code : new int[] {SourceType.RETIRED_KNOWLEDGE_BASE,
                SourceType.RETIRED_CONVERSATION, SourceType.RETIRED_INFERRED}) {
            assertThat(SourceType.isRetired((byte) code)).isTrue();
            assertThat(SourceType.decode((byte) code)).isEmpty();
            assertThat(SourceType.fromByte((byte) code)).isEqualTo(SourceType.UNKNOWN);
        }
    }

    @Test
    @DisplayName("a code from a newer build is unknown rather than an error")
    void newerCodes() {
        assertThat(SourceType.decode((byte) 0x5A)).isEmpty();
        assertThat(SourceType.decode((byte) 0xFF)).isEmpty();
        assertThat(SourceType.fromByte((byte) 0x5A)).isEqualTo(SourceType.UNKNOWN);
        assertThat(SourceType.isRetired((byte) 0x5A)).isFalse();
    }
}
