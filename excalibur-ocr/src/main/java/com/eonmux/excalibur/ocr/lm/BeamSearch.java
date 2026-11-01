// Ported from Indra OCR 1.1.2, lm.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.lm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CTC prefix beam search with a token n-gram language model, for the lines a recognizer is
 * unsure of.
 *
 * <p>The recognizer reads greedily, one best class per step. On a line under its confidence
 * gate, this search keeps several readings and the language model breaks close calls between
 * them: {@code score = log P_ctc + alpha * log P_lm + beta * tokens}. The recognizer's own
 * probabilities still decide, so a clearly written odd word survives.</p>
 *
 * <p>Port of {@code lm.py}'s {@code beam_search}, kept exact: float64 arithmetic in Python's
 * operation order, the same candidate pruning, and the same tie-breaking - beams are kept in
 * insertion order and sorted stably, as Python's dicts and {@code sorted} keep them. One
 * comparison is float32, as numpy makes it: a class's float32 log-probability against the
 * candidate floor.</p>
 */
public final class BeamSearch {

    private static final double NEG_INF = Double.NEGATIVE_INFINITY;

    /** The slack in the pruning comparison that absorbs float rounding, as Python's 1e-9. */
    private static final double FLOAT_SLACK = 1e-9;

    private BeamSearch() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The search's parameters.
     *
     * @param alpha     The weight of the language model's log-probability
     * @param beta      The reward per token
     * @param beam      How many prefixes are kept after each step
     * @param blankSkip A step whose blank probability is over this is all blank: every prefix
     *                  just ends in blank there
     * @param candFloor A class under this probability at a step is not tried there
     */
    public record Options(double alpha, double beta, int beam, double blankSkip,
                          double candFloor) {

        /** {@code beam_search}'s defaults; the pipeline uses a candidate floor of 3e-3. */
        public static final Options DEFAULTS = new Options(0.5, 1.5, 8, 0.999, 1e-3);

        public Options {
            if (!Double.isFinite(alpha) || !Double.isFinite(beta)) {
                throw new IllegalArgumentException("alpha and beta must be finite, got " + alpha
                    + " and " + beta);
            }
            if (beam < 1) {
                throw new IllegalArgumentException("beam must be at least 1, got " + beam);
            }
            if (!(blankSkip > 0) || !Double.isFinite(blankSkip)) {
                throw new IllegalArgumentException("blankSkip must be a positive probability, got "
                    + blankSkip);
            }
            if (!(candFloor > 0) || !Double.isFinite(candFloor)) {
                throw new IllegalArgumentException("candFloor must be a positive probability, got "
                    + candFloor);
            }
        }
    }

    /**
     * The best reading.
     *
     * @param ids   The token (class) ids, in order
     * @param steps The step at which each token was first emitted
     */
    public record Reading(int[] ids, int[] steps) {

        public Reading {
            Objects.requireNonNull(ids, "ids must not be null");
            Objects.requireNonNull(steps, "steps must not be null");
            if (ids.length != steps.length) {
                throw new IllegalArgumentException(ids.length + " ids but " + steps.length
                    + " steps");
            }
            ids = ids.clone();
            steps = steps.clone();
        }

        @Override
        public int[] ids() {
            return ids.clone();
        }

        @Override
        public int[] steps() {
            return steps.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Reading r && Arrays.equals(ids, r.ids)
                && Arrays.equals(steps, r.steps);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(ids) + Arrays.hashCode(steps);
        }

        @Override
        public String toString() {
            return "Reading[ids=" + Arrays.toString(ids) + ", steps=" + Arrays.toString(steps)
                + "]";
        }
    }

    /**
     * Searches a line's steps for its best reading. Port of {@code beam_search}.
     *
     * @param blank   Per step, the blank class's log-probability ({@code [T]}, the recognizer's
     *                {@code blank} output)
     * @param topv    Per step, the log-probabilities of the {@code k} best classes, blank
     *                included ({@code [T, k]}, the {@code topv} output)
     * @param topi    Per step, those classes ({@code [T, k]}, the {@code topi} output)
     * @param lm      The language model over the recognizer's classes
     * @param options The search parameters
     * @return The best reading and the step each of its tokens was first emitted at
     * @throws IllegalArgumentException if the arrays disagree in shape, a log-probability is NaN
     *                                  or positive infinity, or a class is negative
     */
    public static Reading search(float[] blank, float[][] topv, long[][] topi, NgramLM lm,
                                 Options options) {
        Objects.requireNonNull(lm, "lm must not be null");
        Objects.requireNonNull(options, "options must not be null");
        checkInputs(blank, topv, topi);
        return new Search(lm, options).run(blank, topv, topi);
    }

    private static void checkInputs(float[] blank, float[][] topv, long[][] topi) {
        Objects.requireNonNull(blank, "blank must not be null");
        Objects.requireNonNull(topv, "topv must not be null");
        Objects.requireNonNull(topi, "topi must not be null");
        if (topv.length != blank.length || topi.length != blank.length) {
            throw new IllegalArgumentException("blank, topv and topi must have one row per step: "
                + blank.length + ", " + topv.length + " and " + topi.length);
        }
        for (int t = 0; t < blank.length; t++) {
            checkLogProb(blank[t], "blank[" + t + "]");
            if (topv[t].length != topi[t].length) {
                throw new IllegalArgumentException("step " + t + " has " + topv[t].length
                    + " values but " + topi[t].length + " classes");
            }
            for (int j = 0; j < topv[t].length; j++) {
                checkLogProb(topv[t][j], "topv[" + t + "][" + j + "]");
                if (topi[t][j] < 0 || topi[t][j] >= Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("topi[" + t + "][" + j + "] = "
                        + topi[t][j] + " is not a class");
                }
            }
        }
    }

    private static void checkLogProb(float value, String name) {
        if (Float.isNaN(value) || value == Float.POSITIVE_INFINITY) {
            throw new IllegalArgumentException(name + " = " + value + " is not a log-probability");
        }
    }

    /**
     * Port of {@code _lse}: {@code log(exp(a) + exp(b))}, exact when either is minus infinity.
     */
    static double lse(double a, double b) {
        if (a == NEG_INF) {
            return b;
        }
        if (b == NEG_INF) {
            return a;
        }
        double m = b > a ? b : a;
        return m + Math.log(Math.exp(a - m) + Math.exp(b - m));
    }

    /**
     * A prefix's state: log-probability of ending in blank and in non-blank, its language model
     * score, the step of each token, and its last {@code order - 1} tokens packed.
     */
    private record State(double b, double nb, double s, int[] steps, long hk) {

        double total() {
            return lse(b, nb);
        }

        double score() {
            return lse(b, nb) + s;
        }
    }

    /** A candidate class at one step and its log-probability. */
    private record Candidate(int c, double lp) {
    }

    /** A new prefix whose language model score is still to be looked up. */
    private record Extension(TokenPrefix prefix, int c, double newFrom, State parent) {
    }

    /** One search: the language model and parameters, and the beams as they evolve. */
    private static final class Search {

        private final NgramLM lm;
        private final Options o;
        private final int bits;
        private final long hmask;
        private final double logSkip;
        private final float logFloor;
        private final double reach;

        Search(NgramLM lm, Options options) {
            this.lm = lm;
            this.o = options;
            this.bits = lm.bits();
            this.hmask = (1L << (bits * (lm.order() - 1))) - 1;
            this.logSkip = Math.log(options.blankSkip());
            // numpy compares a float32 value with a Python float in float32 (NEP 50).
            this.logFloor = (float) Math.log(options.candFloor());
            this.reach = options.beta() + options.alpha() * lm.maxLogp();
        }

        Reading run(float[] blank, float[][] topv, long[][] topi) {
            Map<TokenPrefix, State> beams = new LinkedHashMap<>();
            beams.put(TokenPrefix.EMPTY, new State(0.0, NEG_INF, 0.0, new int[0], 0L));
            for (int t = 0; t < blank.length; t++) {
                double b0 = blank[t];
                beams = b0 > logSkip ? allBlank(beams, b0)
                    : step(beams, t, b0, candidates(topv[t], topi[t]));
            }
            Map.Entry<TokenPrefix, State> best = null;
            for (Map.Entry<TokenPrefix, State> e : beams.entrySet()) {
                if (best == null || e.getValue().score() > best.getValue().score()) {
                    best = e;
                }
            }
            return new Reading(best.getKey().ids(), best.getValue().steps());
        }

        /** A step that is all blank: every prefix now ends in blank. */
        private static Map<TokenPrefix, State> allBlank(Map<TokenPrefix, State> beams,
                                                        double b0) {
            Map<TokenPrefix, State> out = new LinkedHashMap<>();
            beams.forEach((p, e) -> out.put(p, new State(lse(e.b(), e.nb()) + b0, NEG_INF,
                e.s(), e.steps(), e.hk())));
            return out;
        }

        private List<Candidate> candidates(float[] values, long[] classes) {
            List<Candidate> out = new ArrayList<>(values.length);
            for (int j = 0; j < values.length; j++) {
                if (classes[j] != 0 && values[j] >= logFloor) {
                    out.add(new Candidate((int) classes[j], values[j]));
                }
            }
            return out;
        }

        /** One step: extend every beam by the candidates and keep the best {@code beam}. */
        private Map<TokenPrefix, State> step(Map<TokenPrefix, State> beams, int t, double b0,
                                        List<Candidate> cands) {
            Map<TokenPrefix, State> next = new LinkedHashMap<>();
            // Every beam's own entries first (the blank, and a repeat that collapses): their
            // scores only grow below, so with a full beam the lowest is a floor that a new
            // prefix must be able to reach to enter, and the language model is not asked about
            // one that cannot.
            for (Map.Entry<TokenPrefix, State> e : beams.entrySet()) {
                State s = e.getValue();
                add(next, e.getKey(), lse(s.b(), s.nb()) + b0, NEG_INF, s);
                int last = e.getKey().last();
                for (Candidate c : cands) {
                    if (c.c() == last) {
                        add(next, e.getKey(), NEG_INF, s.nb() + c.lp(), s);
                    }
                }
            }
            double floor = beams.size() >= o.beam() ? minScore(next) : NEG_INF;
            List<Extension> ext = extensions(beams, cands, next, floor);
            long[] keys = new long[ext.size()];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = (ext.get(i).parent().hk() << bits) | (ext.get(i).c() + 1L);
            }
            double[] lps = keys.length > 0 ? lm.logpKeys(keys) : new double[0];
            for (int i = 0; i < keys.length; i++) {
                Extension x = ext.get(i);
                State p = x.parent();
                int[] steps = Arrays.copyOf(p.steps(), p.steps().length + 1);
                steps[steps.length - 1] = t;
                State fresh = new State(NEG_INF, x.newFrom(), p.s() + o.alpha() * lps[i] + o.beta(),
                    steps, keys[i] & hmask);
                merge(next, x.prefix().plus(x.c()), fresh);
            }
            return best(next);
        }

        /** New prefixes that can reach the floor; extensions of an existing beam merge now. */
        private List<Extension> extensions(Map<TokenPrefix, State> beams, List<Candidate> cands,
                                           Map<TokenPrefix, State> next, double floor) {
            List<Extension> ext = new ArrayList<>();
            for (Map.Entry<TokenPrefix, State> e : beams.entrySet()) {
                State s = e.getValue();
                double tot = s.total();
                int last = e.getKey().last();
                for (Candidate c : cands) {
                    // After a blank, the same class is a new token.
                    double newFrom = c.c() == last ? s.b() + c.lp() : tot + c.lp();
                    if (newFrom == NEG_INF) {
                        continue;
                    }
                    TokenPrefix q = e.getKey().plus(c.c());
                    if (next.containsKey(q)) {
                        add(next, q, NEG_INF, newFrom, s);
                    } else if (newFrom + s.s() + reach >= floor - FLOAT_SLACK) {
                        ext.add(new Extension(e.getKey(), c.c(), newFrom, s));
                    }
                }
            }
            return ext;
        }

        /** Port of the nested {@code add}: a new prefix, or log-sum into an existing one. */
        private static void add(Map<TokenPrefix, State> next, TokenPrefix prefix, double b,
                                double nb, State from) {
            merge(next, prefix, new State(b, nb, from.s(), from.steps(), from.hk()));
        }

        private static void merge(Map<TokenPrefix, State> next, TokenPrefix prefix, State state) {
            State old = next.get(prefix);
            next.put(prefix, old == null ? state
                : new State(lse(old.b(), state.b()), lse(old.nb(), state.nb()), old.s(),
                    old.steps(), old.hk()));
        }

        private static double minScore(Map<TokenPrefix, State> states) {
            double min = Double.POSITIVE_INFINITY;
            for (State s : states.values()) {
                double v = s.score();
                if (v < min) {
                    min = v;
                }
            }
            return min;
        }

        /** The {@code beam} best prefixes, ties in insertion order, as a stable sort keeps. */
        private Map<TokenPrefix, State> best(Map<TokenPrefix, State> next) {
            List<Map.Entry<TokenPrefix, State>> entries = new ArrayList<>(next.entrySet());
            entries.sort((x, y) -> {
                double a = x.getValue().score();
                double b = y.getValue().score();
                return a > b ? -1 : a < b ? 1 : 0;
            });
            Map<TokenPrefix, State> out = new LinkedHashMap<>();
            for (Map.Entry<TokenPrefix, State> e : entries.subList(0, Math.min(o.beam(),
                entries.size()))) {
                out.put(e.getKey(), e.getValue());
            }
            return out;
        }
    }
}
