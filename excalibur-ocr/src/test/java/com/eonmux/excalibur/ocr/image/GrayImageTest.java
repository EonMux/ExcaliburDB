package com.eonmux.excalibur.ocr.image;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("A gray image")
class GrayImageTest {

    @Test
    @DisplayName("turns by quarter turns as numpy's rot90 does")
    void rot90MatchesNumpy() {
        Map<String, NdArray> f = Fixtures.npz("foundation/rot90.npz");
        GrayImage image = Fixtures.image(f.get("image"));

        assertThat(image.rot90(1)).isEqualTo(Fixtures.image(f.get("k1")));
        assertThat(image.rot90(2)).isEqualTo(Fixtures.image(f.get("k2")));
        assertThat(image.rot90(3)).isEqualTo(Fixtures.image(f.get("k3")));
        assertThat(image.rot90(-1)).isEqualTo(Fixtures.image(f.get("k-1")));
        assertThat(image.rot90(4)).isSameAs(image);
    }

    @Test
    @DisplayName("cuts a region clipped to itself, and none outside it")
    void region() {
        GrayImage image = GrayImage.of(3, 2, new byte[] {1, 2, 3, 4, 5, 6});

        assertThat(image.region(1, 0, 9, 9)).isEqualTo(GrayImage.of(2, 2, new byte[] {2, 3, 5, 6}));
        assertThat(image.region(-5, 1, 1, 2)).isEqualTo(GrayImage.of(1, 1, new byte[] {4}));
        assertThat(image.region(3, 0, 5, 2)).isNull();
    }

    @Test
    @DisplayName("keeps its pixels to itself")
    void immutable() {
        byte[] pixels = {10, 20};
        GrayImage image = GrayImage.of(2, 1, pixels);
        pixels[0] = 99;
        image.pixels()[1] = 99;

        assertThat(image.get(0, 0)).isEqualTo(10);
        assertThat(image.get(1, 0)).isEqualTo(20);
    }

    @Test
    @DisplayName("reports its mean and inverts")
    void meanAndInvert() {
        GrayImage image = GrayImage.of(2, 1, new byte[] {0, (byte) 200});

        assertThat(image.mean()).isEqualTo(100.0);
        assertThat(image.inverted().get(0, 0)).isEqualTo(255);
        assertThat(image.inverted().get(1, 0)).isEqualTo(55);
        assertThat(GrayImage.filled(2, 2, 7).mean()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("refuses impossible sizes and reads")
    void refusals() {
        assertThatThrownBy(() -> GrayImage.of(0, 1, new byte[0]))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrayImage.of(2, 2, new byte[3]))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrayImage.filled(1, 1, 0).get(1, 0))
            .isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(GrayImage.filled(1, 1, 0).toString()).contains("1x1");
    }
}
