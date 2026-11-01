// Ported from Indra OCR 1.1.2, order_post.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.layout;

import com.eonmux.excalibur.ocr.array.NumpyOps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Groups of lines from the order model's pairwise "same group" logits, such as its same-block
 * head: average-linkage clustering.
 *
 * <p>The two groups with the highest mean logit between them merge while that mean is positive
 * (a probability above one half). Unlike connected components, one wrong pair cannot join two
 * groups that the other pairs keep apart. A merge changes only the merged group's row and
 * column of the mean matrix, and each row keeps its best partner, so a page of n lines costs
 * about n² rather than n³. The arithmetic is float64, as Indra's.</p>
 */
final class Clustering {

    private final int n;
    private final double[][] sums;
    private final double[][] means;
    private final double[] size;
    private final boolean[] alive;
    private final List<List<Integer>> members;
    private final int[] best;
    private final double[] bestValue;

    private Clustering(float[] logits, int n) {
        this.n = n;
        sums = new double[n][n];
        means = new double[n][n];
        size = new double[n];
        alive = new boolean[n];
        members = new ArrayList<>(n);
        best = new int[n];
        bestValue = new double[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                sums[i][j] = i == j ? 0.0 : logits[i * n + j];
                means[i][j] = i == j ? Double.NEGATIVE_INFINITY : sums[i][j];
            }
            size[i] = 1.0;
            alive[i] = true;
            members.add(new ArrayList<>(List.of(i)));
        }
        for (int i = 0; i < n; i++) {
            best[i] = NumpyOps.argmax(means[i]);
            bestValue[i] = means[i][best[i]];
        }
    }

    /**
     * Clusters lines by their pairwise logits. Ports {@code _cluster}.
     *
     * @param logits Row-major {@code [n, n]} logits; positive means "same group"
     * @param n      How many lines
     * @return The groups, each its line indices ascending, ordered by their first line
     */
    static List<int[]> cluster(float[] logits, int n) {
        LayoutArgs.requireMatrix(logits, n, n, "logits");
        if (n == 0) {
            return List.of();
        }
        Clustering c = new Clustering(logits, n);
        c.run();
        return c.groups();
    }

    private void run() {
        for (int step = 0; step < n - 1; step++) {
            int a = NumpyOps.argmax(bestValue);
            int b = best[a];
            if (bestValue[a] <= 0) {
                return;
            }
            merge(Math.min(a, b), Math.max(a, b));
        }
    }

    /** Merges group {@code b} into group {@code a < b} and updates every row's best partner. */
    private void merge(int a, int b) {
        for (int j = 0; j < n; j++) {
            sums[a][j] += sums[b][j];
        }
        for (int i = 0; i < n; i++) {
            sums[i][a] += sums[i][b];
        }
        sums[a][a] = 0.0;
        size[a] += size[b];
        alive[b] = false;
        members.get(a).addAll(members.get(b));
        double[] row = new double[n];
        for (int j = 0; j < n; j++) {
            row[j] = alive[j] && j != a ? sums[a][j] / (size[a] * size[j]) : Double.NEGATIVE_INFINITY;
        }
        for (int j = 0; j < n; j++) {
            means[a][j] = row[j];
            means[j][a] = row[j];
            means[b][j] = Double.NEGATIVE_INFINITY;
            means[j][b] = Double.NEGATIVE_INFINITY;
        }
        bestValue[b] = Double.NEGATIVE_INFINITY;
        best[a] = NumpyOps.argmax(row);
        bestValue[a] = row[best[a]];
        for (int j = 0; j < n; j++) {
            if (alive[j] && j != a && (best[j] == a || best[j] == b)) {
                best[j] = NumpyOps.argmax(means[j]);
                bestValue[j] = means[j][best[j]];
            }
        }
        for (int j = 0; j < n; j++) {
            if (alive[j] && j != a && row[j] > bestValue[j]) {
                best[j] = a;
                bestValue[j] = row[j];
            }
        }
    }

    private List<int[]> groups() {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (alive[i]) {
                int[] g = members.get(i).stream().mapToInt(Integer::intValue).toArray();
                Arrays.sort(g);
                out.add(g);
            }
        }
        return List.copyOf(out);
    }
}
