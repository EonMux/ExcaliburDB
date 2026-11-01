// Ported from Indra OCR 1.1.2, lm.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.lm;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.array.Npz;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * A token n-gram language model with back-off, as Indra OCR stores one in {@code lm.npz} and
 * {@code lm_<group>.npz}: the language model that breaks close calls in
 * {@link BeamSearch#search beam search} over a recognizer's unsure lines.
 *
 * <p>The file holds, per history length {@code n} below {@code order}, the sorted keys of the
 * (history, token) pairs seen in training with their log-probabilities ({@code k<n>},
 * {@code v<n>}), and the sorted keys of the histories seen with their back-off weights
 * ({@code hk<n>}, {@code hb<n>}). A key packs its tokens {@code bits} bits each, oldest first
 * ({@link #pack}): 0 is the line start ({@link #BOS}) and {@code t + 1} is token {@code t}. A
 * file without {@code bits} uses {@link #BITS}.</p>
 *
 * <p>As in Python, every history length's keys go into one table per kind, sorted, with the
 * history length tagged in the top three bits ({@link #TAG_SHIFT}), so a lookup is one binary
 * search. Keys are unsigned 64-bit values held in {@code long}s and compared unsigned, as numpy
 * compares {@code uint64}. Instances are immutable and safe to share between threads; Python's
 * memo caches are left out, since they change no result.</p>
 *
 * <p>Port of {@code lm.py}'s {@code NgramLM}.</p>
 */
public final class NgramLM {

    /** Bits per token in a key when the file does not say. Port of {@code BITS}. */
    public static final int BITS = 11;

    /** Where the history length goes in a merged-table key. Port of {@code TAG_SHIFT}. */
    public static final int TAG_SHIFT = 61;

    /** The line-start token, packed as 0. Port of {@code BOS}. */
    public static final int BOS = -1;

    /** The longest history length the three tag bits can name, plus one. */
    private static final int MAX_ORDER = 1 << (Long.SIZE - TAG_SHIFT);

    private final int order;
    private final int vocabSize;
    private final int bits;
    private final KeyTable probs;
    private final KeyTable backoffs;
    private final double logVocab;
    private final double maxLogp;

    private NgramLM(int order, int vocabSize, int bits, KeyTable probs, KeyTable backoffs) {
        this.order = order;
        this.vocabSize = vocabSize;
        this.bits = bits;
        this.probs = probs;
        this.backoffs = backoffs;
        this.logVocab = Math.log(vocabSize);
        this.maxLogp = computeMaxLogp();
    }

    /**
     * Reads a language model file.
     *
     * @param file An {@code lm*.npz}
     * @return The model
     * @throws IOException if the file cannot be read or is not a language model
     */
    public static NgramLM load(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Map<String, NdArray> arrays = Npz.read(file);
        try {
            return of(arrays);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IOException(file + " is not a language model this can read: "
                + e.getMessage(), e);
        }
    }

    /**
     * Builds a model from the arrays of an {@code lm*.npz}. Port of {@code NgramLM.__init__}.
     *
     * @param arrays The arrays: {@code order}, {@code vocab_size}, optionally {@code bits}, and
     *               {@code k<n>}, {@code v<n>}, {@code hk<n>}, {@code hb<n>} for each history
     *               length {@code n} below {@code order}
     * @return The model
     * @throws IllegalArgumentException if an array is missing or the tables break the format:
     *                                  keys not strictly ascending, keys that reach the tag bits,
     *                                  keys and values of different lengths, or a first history
     *                                  table other than the empty history alone
     */
    public static NgramLM of(Map<String, NdArray> arrays) {
        Objects.requireNonNull(arrays, "arrays must not be null");
        int order = Math.toIntExact(require(arrays, "order").scalarLong());
        int vocabSize = Math.toIntExact(require(arrays, "vocab_size").scalarLong());
        int bits = arrays.containsKey("bits")
            ? Math.toIntExact(arrays.get("bits").scalarLong()) : BITS;
        if (order < 1 || order > MAX_ORDER) {
            throw new IllegalArgumentException("order must be 1 to " + MAX_ORDER + ", got "
                + order);
        }
        if (vocabSize < 1) {
            throw new IllegalArgumentException("vocab_size must be positive, got " + vocabSize);
        }
        if (bits < 1 || (long) order * bits > TAG_SHIFT) {
            throw new IllegalArgumentException("order " + order + " x " + bits
                + " bits do not fit a key");
        }
        KeyTable probs = KeyTable.merge(arrays, "k", "v", order);
        KeyTable backoffs = KeyTable.merge(arrays, "hk", "hb", order);
        if (backoffs.size(0) != 1 || backoffs.key(0) != 0L) {
            throw new IllegalArgumentException("hk0 must hold the empty history (key 0) alone");
        }
        return new NgramLM(order, vocabSize, bits, probs, backoffs);
    }

    /**
     * The n-gram order: the longest (history, token) pair has {@code order - 1} history
     * tokens.
     *
     * @return The order
     */
    public int order() {
        return order;
    }

    /**
     * The token count the uniform floor of an unseen token divides by.
     *
     * @return The vocabulary size
     */
    public int vocabSize() {
        return vocabSize;
    }

    /**
     * Bits per token in a key.
     *
     * @return The key width per token
     */
    public int bits() {
        return bits;
    }

    /**
     * An upper bound of every value {@link #logp} and {@link #logpKeys} return: the largest
     * log-probability (or the uniform floor, if larger) plus every positive back-off weight a
     * back-off can add. Port of {@code NgramLM.max_logp}.
     *
     * @return The bound
     */
    public double maxLogp() {
        return maxLogp;
    }

    /**
     * The log-probability of a token after a history. Port of {@code NgramLM.logp}.
     *
     * <p>The value comes from the longest suffix of the history that the model saw followed by
     * the token, plus the back-off weights of the longer seen suffixes; a token never seen after
     * any suffix gets those weights and the uniform floor {@code -log(vocabSize)}. Only the last
     * {@code order - 1} history tokens count.</p>
     *
     * @param history The tokens before, oldest first; {@link #BOS} for the line start
     * @param token   The token, 0 or more
     * @return Its log-probability
     * @throws IllegalArgumentException if a token does not fit the key's token width
     */
    public double logp(int[] history, int token) {
        Objects.requireNonNull(history, "history must not be null");
        checkToken(token, false);
        for (int h : history) {
            checkToken(h, true);
        }
        int top = Math.min(history.length, order - 1);
        int seen = 0;
        for (int n = 1; n <= top; n++) {
            if (backoffs.find(n, packTail(history, n)) < 0) {
                break;
            }
            seen = n;
        }
        double acc = 0.0;
        for (int n = seen; n >= 0; n--) {
            long key = (packTail(history, n) << bits) | (token + 1L);
            int i = probs.find(n, key);
            if (i >= 0) {
                return acc + probs.value(i);
            }
            acc += backoffs.value(backoffs.find(n, packTail(history, n)));
        }
        return acc - logVocab;
    }

    /**
     * {@link #logp} for many (history, token) pairs, each given as one key: the last
     * {@code order - 1} history tokens and then the token, {@link #pack packed}, with
     * {@link #BOS} (0) for history before the line start. Port of {@code NgramLM.logp_keys},
     * with its arithmetic in the same order, so it returns the same values.
     *
     * @param keys The packed pairs, as unsigned 64-bit values
     * @return Each pair's log-probability
     */
    public double[] logpKeys(long[] keys) {
        Objects.requireNonNull(keys, "keys must not be null");
        double[] out = new double[keys.length];
        for (int i = 0; i < keys.length; i++) {
            out[i] = logpKey(keys[i]);
        }
        return out;
    }

    /**
     * One pair of {@link #logpKeys}. Every history length is looked up; {@code seen} is the
     * longest one seen without a gap, the value comes from the longest seen length that saw the
     * token, and the back-off weights of the longer seen lengths are summed from the longest
     * down, as numpy's reversed cumulative sum adds them.
     */
    private double logpKey(long key) {
        long tokenField = key & ((1L << bits) - 1);
        long history = key >>> bits;
        double[] backoff = new double[order];
        boolean[] historySeen = new boolean[order];
        double[] prob = new double[order];
        boolean[] pairSeen = new boolean[order];
        for (int n = 0; n < order; n++) {
            long tag = (long) n << TAG_SHIFT;
            long h = history & ((1L << (bits * n)) - 1);
            int hi = backoffs.search(h | tag);
            historySeen[n] = hi >= 0;
            backoff[n] = hi >= 0 ? backoffs.value(hi) : 0.0;
            int pi = probs.search((h << bits) | tokenField | tag);
            pairSeen[n] = pi >= 0;
            prob[n] = pi >= 0 ? probs.value(pi) : 0.0;
        }
        historySeen[0] = true;
        int seen = 0;
        while (seen + 1 < order && historySeen[seen + 1]) {
            seen++;
        }
        int chosen = -1;
        for (int n = seen; n >= 0 && chosen < 0; n--) {
            if (pairSeen[n]) {
                chosen = n;
            }
        }
        double[] suffix = new double[order + 1];
        double sum = 0.0;
        for (int n = order - 1; n >= 0; n--) {
            double term = backoff[n] * (n <= seen ? 1.0 : 0.0);
            sum = n == order - 1 ? term : sum + term;
            suffix[n] = sum;
        }
        return chosen >= 0 ? suffix[chosen + 1] + prob[chosen] : suffix[0] - logVocab;
    }

    /**
     * Packs tokens into one key, {@code bits} bits each, the first token highest. Port of
     * {@code _pack}.
     *
     * @param tokens The tokens; {@link #BOS} packs as 0 and token {@code t} as {@code t + 1}
     * @param bits   Bits per token
     * @return The key, as an unsigned 64-bit value
     * @throws IllegalArgumentException if the tokens do not fit 64 bits or their fields
     */
    public static long pack(int[] tokens, int bits) {
        Objects.requireNonNull(tokens, "tokens must not be null");
        if (bits < 1 || (long) tokens.length * bits > Long.SIZE) {
            throw new IllegalArgumentException(tokens.length + " tokens of " + bits
                + " bits do not fit a key");
        }
        long key = 0;
        for (int t : tokens) {
            if (t < BOS || (long) t + 1 >= 1L << bits) {
                throw new IllegalArgumentException("token " + t + " does not fit " + bits
                    + " bits");
            }
            key = (key << bits) | (t == BOS ? 0 : t + 1L);
        }
        return key;
    }

    /**
     * Where a key is in an ascending unsigned table, or -1. Port of {@code _find}: numpy's
     * {@code searchsorted} (the leftmost insertion point) and a check of the key there.
     *
     * @param keys The table, ascending as unsigned 64-bit values
     * @param key  The key
     * @return Its index, or -1 if the table does not hold it
     */
    public static int find(long[] keys, long key) {
        Objects.requireNonNull(keys, "keys must not be null");
        return KeyTable.find(keys, 0, keys.length, key);
    }

    /** {@link #pack} of the last {@code n} history tokens, without the argument checks. */
    private long packTail(int[] history, int n) {
        long key = 0;
        for (int i = history.length - n; i < history.length; i++) {
            int t = history[i];
            key = (key << bits) | (t == BOS ? 0 : t + 1L);
        }
        return key;
    }

    private void checkToken(int token, boolean history) {
        if (token < (history ? BOS : 0) || (long) token + 1 >= 1L << bits) {
            throw new IllegalArgumentException((history ? "history token " : "token ") + token
                + " does not fit a " + bits + "-bit key field");
        }
    }

    /** Port of {@code max_logp}'s computation, Python's float operations in order. */
    private double computeMaxLogp() {
        double largest = probs.maxValue();
        double top = -logVocab > largest ? -logVocab : largest;
        double positive = 0.0;
        for (int n = 0; n < order; n++) {
            if (backoffs.size(n) > 0) {
                double m = backoffs.maxValue(n);
                positive += m > 0.0 ? m : 0.0;
            }
        }
        return top + positive;
    }

    /** An array of a language model file, refused by name when it is missing. */
    static NdArray require(Map<String, NdArray> arrays, String name) {
        NdArray a = arrays.get(name);
        if (a == null) {
            throw new IllegalArgumentException("no array " + name);
        }
        return a;
    }

    @Override
    public String toString() {
        return "NgramLM[order " + order + ", " + vocabSize + " tokens, " + bits + " bits, "
            + probs.length() + " n-grams]";
    }
}
