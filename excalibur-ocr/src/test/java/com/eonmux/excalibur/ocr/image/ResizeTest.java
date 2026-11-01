package com.eonmux.excalibur.ocr.image;

import com.eonmux.excalibur.ocr.array.NdArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Resizing")
class ResizeTest {

    @Test
    @DisplayName("matches cv2.resize pixel for pixel, shrinking and enlarging, by area and linearly")
    void matchesOpenCv() {
        Map<String, NdArray> f = ImagingFixtures.load("resize.npz");
        long[] params = f.get("params").longs();
        ImagingFixtures.Packed src = new ImagingFixtures.Packed(f.get("src"));
        ImagingFixtures.Packed dst = new ImagingFixtures.Packed(f.get("dst"));
        int cases = params.length / 5;
        for (int c = 0; c < cases; c++) {
            int sw = (int) params[c * 5];
            int sh = (int) params[c * 5 + 1];
            int dw = (int) params[c * 5 + 2];
            int dh = (int) params[c * 5 + 3];
            Interpolation mode = params[c * 5 + 4] == 0 ? Interpolation.LINEAR : Interpolation.AREA;
            GrayImage in = src.image(sw, sh);
            GrayImage expected = dst.image(dw, dh);

            assertThat(Resize.resize(in, dw, dh, mode))
                .as("%s %dx%d to %dx%d", mode, sw, sh, dw, dh)
                .isEqualTo(expected);
        }
        assertThat(cases).isGreaterThan(290);
        assertThat(src.exhausted() && dst.exhausted()).isTrue();
    }

    @Test
    @DisplayName("returns the image itself at its own size")
    void sameSize() {
        GrayImage image = GrayImage.filled(3, 2, 9);

        assertThat(Resize.resize(image, 3, 2, Interpolation.AREA)).isSameAs(image);
    }

    @Test
    @DisplayName("rejects empty targets and missing arguments")
    void validates() {
        GrayImage image = GrayImage.filled(3, 2, 9);

        assertThatThrownBy(() -> Resize.resize(image, 0, 2, Interpolation.AREA))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Resize.resize(image, 2, -1, Interpolation.LINEAR))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Resize.resize(null, 2, 2, Interpolation.LINEAR))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Resize.resize(image, 2, 2, null))
            .isInstanceOf(NullPointerException.class);
    }
}
