package com.eonmux.excalibur.ocr.image;

import com.eonmux.excalibur.ocr.array.NdArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Affine warping")
class WarpAffineTest {

    @Test
    @DisplayName("matches cv2.warpAffine pixel for pixel with constant and replicated borders")
    void matchesOpenCv() {
        Map<String, NdArray> f = ImagingFixtures.load("warp.npz");
        double[] params = f.get("params").doubles();
        double[] matrix = f.get("matrix").doubles();
        ImagingFixtures.Packed src = new ImagingFixtures.Packed(f.get("src"));
        ImagingFixtures.Packed dst = new ImagingFixtures.Packed(f.get("dst"));
        int cases = params.length / 9;
        for (int c = 0; c < cases; c++) {
            int w = (int) params[c * 9];
            int h = (int) params[c * 9 + 1];
            int dw = (int) params[c * 9 + 2];
            int dh = (int) params[c * 9 + 3];
            BorderMode border = params[c * 9 + 4] == 0 ? BorderMode.CONSTANT : BorderMode.REPLICATE;
            int m = c * 6;
            AffineTransform t = new AffineTransform(matrix[m], matrix[m + 1], matrix[m + 2],
                matrix[m + 3], matrix[m + 4], matrix[m + 5]);
            GrayImage in = src.image(w, h);

            assertThat(WarpAffine.warp(in, t, dw, dh, border))
                .as("case %d: %s, %dx%d to %dx%d", c, border, w, h, dw, dh)
                .isEqualTo(dst.image(dw, dh));
        }
        assertThat(cases).isGreaterThan(300);
        assertThat(src.exhausted() && dst.exhausted()).isTrue();
    }

    @Test
    @DisplayName("rejects empty targets and missing arguments")
    void validates() {
        GrayImage image = GrayImage.filled(3, 2, 9);
        AffineTransform identity = new AffineTransform(1, 0, 0, 0, 1, 0);

        assertThatThrownBy(() -> WarpAffine.warp(image, identity, 0, 2, BorderMode.CONSTANT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WarpAffine.warp(image, null, 3, 2, BorderMode.CONSTANT))
            .isInstanceOf(NullPointerException.class);
        assertThat(WarpAffine.warp(image, identity, 3, 2, BorderMode.REPLICATE)).isEqualTo(image);
    }
}
