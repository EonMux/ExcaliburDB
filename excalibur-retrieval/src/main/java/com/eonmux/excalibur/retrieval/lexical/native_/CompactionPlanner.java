package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.graph.erasure.CompactionPolicy;
import com.eonmux.excalibur.graph.erasure.CompactionPolicy.CompactionStats;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses which segments a compaction should rewrite.
 *
 * <h2>Two decisions, not one</h2>
 * <p>Whether to compact and what to compact are separate questions with separate answers.
 * {@link CompactionPolicy} answers the first and is deliberately pluggable, because the right
 * trigger belongs to the deployment; this class answers the second, because the right selection
 * belongs to the store's layout and no operator should have to know it. The policy is reused from
 * the graph store rather than restated, since both ask the same question of the same three
 * numbers.</p>
 *
 * <h2>What gets picked, and why that differs by trigger</h2>
 * <ul>
 *   <li><b>An overdue obligation picks every segment holding a tombstone.</b> The deadline is on
 *       the erasure, not on the convenient part of it, so cost does not enter into it. Leaving out
 *       an expensive segment would mean reporting an obligation discharged while the data it
 *       covers is still readable on disk.</li>
 *   <li><b>Volume alone picks only segments worth their own rewrite.</b> A segment with one
 *       tombstone in ten million documents costs ten million documents of writing to reclaim one.
 *       The store-wide ratio is what decides that some reclaiming is due; which segments repay it
 *       is a per-segment question, and answering it store-wide is how a compaction triggered by
 *       one crowded segment ends up rewriting every quiet one beside it.</li>
 *   <li><b>Segment count picks the smallest segments.</b> Every segment is a set of mappings and a
 *       search that must visit it, so the count has to stay bounded; merging the smallest ones
 *       reduces it for the least writing. Merging a large segment into a small one would rewrite a
 *       gigabyte to retire a megabyte's worth of overhead.</li>
 * </ul>
 *
 * <h2>The epoch floor</h2>
 * <p>Merging segments published at different epochs changes what a reader at an epoch between them
 * can see, so a merge is confined to segments at or below the oldest epoch any live reader may
 * still query at. Purging is not: removing erased documents is meant to be retroactive, and a
 * single-segment rewrite keeps that segment's epoch, so it changes no reader's visibility except in
 * the way erasure is supposed to. That asymmetry is what lets an erasure deadline be met while a
 * long-running query is holding an old epoch open.</p>
 */
public final class CompactionPlanner {

    /** Segments tolerated before merging to reduce the count. */
    public static final int DEFAULT_MAX_SEGMENTS = 16;

    /** Tombstoned fraction at which rewriting repays itself. */
    public static final double DEFAULT_TOMBSTONE_RATIO = 0.2;

    /** How long an erasure may remain unapplied before cost stops being a consideration. */
    public static final Duration DEFAULT_MAX_ERASURE_LATENCY = Duration.ofHours(1);

    /**
     * Segments one compaction will merge at once.
     *
     * <p>A bound rather than "all of them" so compaction stays incremental: a live store recovers
     * from many bounded rewrites, and a single unbounded one holds the whole corpus hostage to one
     * pass. What one pass leaves undone the next one finishes.</p>
     */
    public static final int DEFAULT_MERGE_FAN_IN = 8;

    private final CompactionPolicy policy;
    private final double tombstoneRatio;
    private final Duration maxErasureLatency;
    private final int maxSegments;
    private final int mergeFanIn;

    /**
     * A planner and a matching policy from one set of thresholds.
     *
     * <p>One set of numbers, so the trigger and the selection cannot be configured to disagree -
     * a policy that fires at a ratio the selection does not act on would compact endlessly and
     * remove nothing.</p>
     */
    public static CompactionPlanner standard(double tombstoneRatio, Duration maxErasureLatency,
                                             int maxSegments) {
        return new CompactionPlanner(
            CompactionPolicy.standard(tombstoneRatio, maxErasureLatency, maxSegments),
            tombstoneRatio, maxErasureLatency, maxSegments, DEFAULT_MERGE_FAN_IN);
    }

    /** The defaults. */
    public static CompactionPlanner standard() {
        return standard(DEFAULT_TOMBSTONE_RATIO, DEFAULT_MAX_ERASURE_LATENCY, DEFAULT_MAX_SEGMENTS);
    }

    /**
     * A planner over a policy of the caller's own.
     *
     * @param policy            Decides whether compaction is due
     * @param tombstoneRatio    Per-segment tombstoned fraction that justifies a rewrite
     * @param maxErasureLatency How long an erasure may wait before every tombstone is purged
     * @param maxSegments       Segments tolerated before merging
     * @param mergeFanIn        Segments merged by one pass
     */
    public CompactionPlanner(CompactionPolicy policy, double tombstoneRatio,
                             Duration maxErasureLatency, int maxSegments, int mergeFanIn) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        if (tombstoneRatio <= 0 || tombstoneRatio > 1 || Double.isNaN(tombstoneRatio)) {
            throw new IllegalArgumentException(
                "tombstoneRatio must be in (0, 1], got " + tombstoneRatio);
        }
        if (maxErasureLatency == null || maxErasureLatency.isNegative()) {
            throw new IllegalArgumentException("maxErasureLatency must be non-negative");
        }
        if (maxSegments < 1) {
            throw new IllegalArgumentException("maxSegments must be at least 1");
        }
        if (mergeFanIn < 2) {
            throw new IllegalArgumentException("mergeFanIn must be at least 2");
        }
        this.tombstoneRatio = tombstoneRatio;
        this.maxErasureLatency = maxErasureLatency;
        this.maxSegments = maxSegments;
        this.mergeFanIn = mergeFanIn;
    }

    /** The numbers the policy judges, for an operator or a metric. */
    public CompactionStats stats(List<SegmentManifest.Entry> segments,
                                 Optional<Instant> oldestOutstanding) {
        Objects.requireNonNull(segments, "segments must not be null");
        long total = 0;
        long tombstoned = 0;
        for (SegmentManifest.Entry entry : segments) {
            total += entry.docCount();
            tombstoned += entry.tombstoned();
        }
        return new CompactionStats(total, tombstoned, oldestOutstanding, segments.size());
    }

    /**
     * Plans a compaction, or nothing when the policy declines.
     *
     * @param segments          The store's segments
     * @param oldestOutstanding When the longest-waiting erasure was promised, if any
     * @param now               The current time
     * @param floorEpoch        The oldest epoch any live reader may still query at
     */
    public Optional<CompactionPlan> plan(List<SegmentManifest.Entry> segments,
                                         Optional<Instant> oldestOutstanding, Instant now,
                                         long floorEpoch) {
        Objects.requireNonNull(segments, "segments must not be null");
        Objects.requireNonNull(oldestOutstanding, "oldestOutstanding must not be null");
        Objects.requireNonNull(now, "now must not be null");
        CompactionStats stats = stats(segments, oldestOutstanding);
        if (!policy.shouldCompact(stats, now)) {
            return Optional.empty();
        }
        boolean overdue = isOverdue(oldestOutstanding, now);
        return Optional.of(select(segments, floorEpoch, overdue, describe(stats, overdue)));
    }

    /**
     * Plans the most work worth doing now, without consulting the policy.
     *
     * <p>For an operator who has decided compaction should happen - discharging an erasure on
     * demand rather than on a schedule. Every segment the floor allows is merged, and every
     * segment above it holding a tombstone is purged.</p>
     */
    public CompactionPlan forcedPlan(List<SegmentManifest.Entry> segments, long floorEpoch) {
        Objects.requireNonNull(segments, "segments must not be null");
        List<List<String>> groups = new ArrayList<>();
        List<SegmentManifest.Entry> mergeable = new ArrayList<>();
        for (SegmentManifest.Entry entry : segments) {
            if (entry.epoch() <= floorEpoch) {
                mergeable.add(entry);
            } else if (entry.tombstoned() > 0) {
                groups.add(List.of(entry.name()));
            }
        }
        for (int i = 0; i < mergeable.size(); i += mergeFanIn) {
            List<SegmentManifest.Entry> chunk =
                mergeable.subList(i, Math.min(mergeable.size(), i + mergeFanIn));
            // A lone segment with nothing tombstoned would be rewritten byte for byte.
            if (chunk.size() == 1 && chunk.get(0).tombstoned() == 0) {
                continue;
            }
            groups.add(names(chunk));
        }
        return new CompactionPlan(groups, "forced");
    }

    private CompactionPlan select(List<SegmentManifest.Entry> segments, long floorEpoch,
                                  boolean overdue, String reason) {
        List<List<String>> groups = new ArrayList<>();
        List<SegmentManifest.Entry> purgeBelowFloor = new ArrayList<>();
        List<SegmentManifest.Entry> purgeAboveFloor = new ArrayList<>();
        for (SegmentManifest.Entry entry : segments) {
            if (!worthPurging(entry, overdue)) {
                continue;
            }
            if (entry.epoch() <= floorEpoch) {
                purgeBelowFloor.add(entry);
            } else {
                purgeAboveFloor.add(entry);
            }
        }

        if (purgeBelowFloor.isEmpty() && purgeAboveFloor.isEmpty()) {
            // A policy fired on grounds this selection does not model. It cannot be the standard
            // policy's ratio - that is a weighted average of the per-segment ratios, so it cannot
            // reach the threshold unless some segment does - but the policy is pluggable and a
            // custom one owes us no such relationship. Take the worst offender rather than
            // returning empty, which would leave that policy firing on the same numbers forever.
            SegmentManifest.Entry worst = worstOffender(segments);
            if (worst != null) {
                (worst.epoch() <= floorEpoch ? purgeBelowFloor : purgeAboveFloor).add(worst);
            }
        }

        // Above the floor a segment may only be rewritten in place, keeping its epoch. Below it
        // the segments are being rewritten anyway, so merging them costs nothing extra.
        for (SegmentManifest.Entry entry : purgeAboveFloor) {
            groups.add(List.of(entry.name()));
        }
        for (int i = 0; i < purgeBelowFloor.size(); i += mergeFanIn) {
            groups.add(names(purgeBelowFloor.subList(
                i, Math.min(purgeBelowFloor.size(), i + mergeFanIn))));
        }

        int claimed = purgeBelowFloor.size() + purgeAboveFloor.size();
        int projected = segments.size() - claimed + groups.size();
        if (projected > maxSegments) {
            addCountReducingMerge(segments, floorEpoch, groups, projected);
        }
        return new CompactionPlan(groups, reason);
    }

    private void addCountReducingMerge(List<SegmentManifest.Entry> segments, long floorEpoch,
                                       List<List<String>> groups, int projected) {
        Set<String> alreadyGrouped = new HashSet<>();
        groups.forEach(alreadyGrouped::addAll);
        List<SegmentManifest.Entry> mergeable = new ArrayList<>();
        for (SegmentManifest.Entry entry : segments) {
            if (entry.epoch() <= floorEpoch && !alreadyGrouped.contains(entry.name())) {
                mergeable.add(entry);
            }
        }
        // Smallest first: the count falls by one per segment merged whatever their size, so the
        // cheapest segments buy the same reduction as the dearest ones.
        mergeable.sort(Comparator.comparingInt(SegmentManifest.Entry::liveCount));
        int surplus = projected - maxSegments;
        int take = Math.min(mergeable.size(), Math.min(mergeFanIn, surplus + 1));
        if (take > 1) {
            groups.add(names(mergeable.subList(0, take)));
        }
    }

    private boolean worthPurging(SegmentManifest.Entry entry, boolean overdue) {
        if (entry.tombstoned() == 0) {
            return false;
        }
        return overdue || entry.tombstoneRatio() >= tombstoneRatio;
    }

    private static SegmentManifest.Entry worstOffender(List<SegmentManifest.Entry> segments) {
        SegmentManifest.Entry worst = null;
        for (SegmentManifest.Entry entry : segments) {
            if (entry.tombstoned() > 0
                    && (worst == null || entry.tombstoned() > worst.tombstoned())) {
                worst = entry;
            }
        }
        return worst;
    }

    private boolean isOverdue(Optional<Instant> oldestOutstanding, Instant now) {
        return oldestOutstanding
            .map(at -> !Duration.between(at, now).minus(maxErasureLatency).isNegative())
            .orElse(false);
    }

    private static List<String> names(List<SegmentManifest.Entry> entries) {
        List<String> names = new ArrayList<>(entries.size());
        for (SegmentManifest.Entry entry : entries) {
            names.add(entry.name());
        }
        return names;
    }

    private static String describe(CompactionStats stats, boolean overdue) {
        return (overdue ? "erasure overdue: " : "due: ")
            + stats.tombstonedRecords() + " of " + stats.totalRecords()
            + " document(s) tombstoned across " + stats.segmentCount() + " segment(s)";
    }
}
