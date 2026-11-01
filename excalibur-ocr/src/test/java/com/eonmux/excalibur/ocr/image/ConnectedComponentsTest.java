package com.eonmux.excalibur.ocr.image;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.image.ConnectedComponents.Connectivity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Connected-component labelling")
class ConnectedComponentsTest {

    @Test
    @DisplayName("numbers components and measures them as cv2.connectedComponentsWithStats does")
    void matchesOpenCv() {
        Map<String, NdArray> f = ImagingFixtures.load("components.npz");
        long[] params = f.get("params").longs();
        ImagingFixtures.Packed masks = new ImagingFixtures.Packed(f.get("mask"));
        ImagingFixtures.Packed labels = new ImagingFixtures.Packed(f.get("labels"));
        ImagingFixtures.Packed stats = new ImagingFixtures.Packed(f.get("stats"));
        int cases = params.length / 4;
        for (int c = 0; c < cases; c++) {
            int w = (int) params[c * 4];
            int h = (int) params[c * 4 + 1];
            Connectivity conn = params[c * 4 + 2] == 4 ? Connectivity.FOUR : Connectivity.EIGHT;
            int n = (int) params[c * 4 + 3];
            Components cc = ConnectedComponents.label(masks.image(w, h), conn);
            int[] expectedStats = stats.ints(n * 5);

            assertThat(cc.count()).as("case %d count", c).isEqualTo(n);
            assertThat(cc.labels()).as("case %d labels", c).containsExactly(labels.ints(w * h));
            for (int l = 0; l < n; l++) {
                assertThat(new int[] {cc.left(l), cc.top(l), cc.boxWidth(l), cc.boxHeight(l),
                    cc.area(l)})
                    .as("case %d label %d", c, l)
                    .containsExactly(java.util.Arrays.copyOfRange(expectedStats, l * 5,
                        l * 5 + 5));
            }
        }
        assertThat(cases).isGreaterThan(200);
        assertThat(masks.exhausted() && labels.exhausted() && stats.exhausted()).isTrue();
    }

    @Test
    @DisplayName("joins diagonal neighbours at 8-connectivity only")
    void diagonals() {
        boolean[] mask = {true, false, false, true};

        assertThat(ConnectedComponents.label(mask, 2, 2, Connectivity.FOUR).count()).isEqualTo(3);
        Components eight = ConnectedComponents.label(mask, 2, 2, Connectivity.EIGHT);
        assertThat(eight.count()).isEqualTo(2);
        assertThat(eight.label(1, 1)).isEqualTo(1);
        assertThat(eight.width()).isEqualTo(2);
        assertThat(eight.height()).isEqualTo(2);
        assertThat(eight).hasToString("Components[1 in 2x2]");
    }

    @Test
    @DisplayName("rejects a mask of the wrong size and labels out of range")
    void validates() {
        assertThatThrownBy(() -> ConnectedComponents.label(new boolean[3], 2, 2,
            Connectivity.FOUR)).isInstanceOf(IllegalArgumentException.class);
        Components cc = ConnectedComponents.label(new boolean[4], 2, 2, Connectivity.FOUR);
        assertThatThrownBy(() -> cc.area(1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> cc.label(2, 0)).isInstanceOf(IndexOutOfBoundsException.class);
    }
}
