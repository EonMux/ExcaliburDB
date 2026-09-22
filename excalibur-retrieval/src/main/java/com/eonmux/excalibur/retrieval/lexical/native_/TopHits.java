package com.eonmux.excalibur.retrieval.lexical.native_;

import java.util.Arrays;

/**
 * The best {@code k} documents seen so far, and the score that gets a document in.
 *
 * <h2>Why a heap and not a sorted list</h2>
 * <p>A query scores documents in doc-id order and keeps only the best few, so the collection is
 * written to far more often than it is read. A min-heap keyed on score answers the only question
 * the scorer asks between documents - "what is the weakest hit I hold?" - in constant time, and
 * replaces it in logarithmic time. That answer is also the pruning threshold, so it is asked once
 * per scored document and any cost in it is paid on the whole corpus.</p>
 *
 * <p>Kept apart from the scorer because it is the one piece of it with no BM25 in it at all: the
 * scorer decides what a document is worth, this decides only whether that is enough.</p>
 */
final class TopHits {

    private final double[] scores;
    private final int[] docs;
    private final int wanted;
    private int held;

    /**
     * @param wanted How many hits to keep; must be at least one
     */
    TopHits(int wanted) {
        if (wanted <= 0) {
            throw new IllegalArgumentException("wanted must be positive, got " + wanted);
        }
        this.wanted = wanted;
        this.scores = new double[wanted];
        this.docs = new int[wanted];
    }

    /**
     * Offers a scored document, keeping it if it is good enough.
     *
     * @param doc   The document
     * @param score What it scored
     */
    void offer(int doc, double score) {
        if (held < wanted) {
            scores[held] = score;
            docs[held] = doc;
            held++;
            if (held == wanted) {
                for (int i = held / 2 - 1; i >= 0; i--) {
                    siftDown(i);
                }
            }
        } else if (score > scores[0]) {
            scores[0] = score;
            docs[0] = doc;
            siftDown(0);
        }
    }

    /**
     * The score a document must beat to be kept.
     *
     * <p>Zero until the heap is full, because until then every document is kept whatever it
     * scored - and a threshold that claimed otherwise would let the scorer prune on the strength
     * of a top-k it has not actually found yet.</p>
     */
    double threshold() {
        return held < wanted ? 0 : scores[0];
    }

    /** Whether the heap holds as many documents as were asked for. */
    boolean isFull() {
        return held == wanted;
    }

    /** The hits, best first. */
    PostingsIndex.Hit[] ranked() {
        PostingsIndex.Hit[] hits = new PostingsIndex.Hit[held];
        for (int i = 0; i < held; i++) {
            hits[i] = new PostingsIndex.Hit(docs[i], scores[i]);
        }
        Arrays.sort(hits, (a, b) -> Double.compare(b.score(), a.score()));
        return hits;
    }

    /** Min-heap on score, so position 0 is the weakest hit currently held. */
    private void siftDown(int start) {
        int root = start;
        while (true) {
            int child = 2 * root + 1;
            if (child >= held) {
                return;
            }
            if (child + 1 < held && scores[child + 1] < scores[child]) {
                child++;
            }
            if (scores[root] <= scores[child]) {
                return;
            }
            double score = scores[root];
            scores[root] = scores[child];
            scores[child] = score;
            int doc = docs[root];
            docs[root] = docs[child];
            docs[child] = doc;
            root = child;
        }
    }
}
