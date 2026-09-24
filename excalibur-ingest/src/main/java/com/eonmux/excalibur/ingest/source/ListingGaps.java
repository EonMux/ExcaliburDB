package com.eonmux.excalibur.ingest.source;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The places a crawl could not see into, which make what it listed less than the whole source.
 *
 * <h2>Why a gap is not an empty place</h2>
 * <p>Change detection reads a listing as the source's current contents: a registered document
 * the crawl did not list is one the source no longer serves. A crawl that meets a place it cannot
 * read and carries on as though the place were empty hands it a listing that looks whole and is
 * not - a web page that answered {@code 503} contributes no links, so every page reachable only
 * through it drops out of the listing and would be removed from the index. A gap is how a source
 * says so: the place, and why it could not be read, so change detection keeps what the listing
 * missed instead of deleting it and an operator can see where the listing fell short.</p>
 *
 * <p>Distinct from {@link IncompleteCrawlException}, which ends a crawl that cannot be trusted at
 * all. A gap leaves the rest of the listing sound - every document it did list is new, changed or
 * current exactly as it would have been - and failing the whole crawl over one unreadable page
 * among thousands would hold back every new and changed document for as long as that page stayed
 * broken.</p>
 *
 * <h2>What an entry carries</h2>
 * <p>A location - the page or file, named the way the source names its documents - and a reason
 * written by the source in fixed terms: a status code, a kind of failure, never the text of an
 * exception. Both are served to anyone who can read a run's status, and an exception's message
 * can carry addresses and paths an operator's log may hold and a status readback must not.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable. A {@link Recorder} is thread-safe.</p>
 *
 * @see Source#getListingGaps()
 */
public final class ListingGaps {

    /**
     * How many gaps a snapshot names.
     *
     * <p>Enough to tell one broken page from a host that is down, and to try one by hand; bounded
     * because a source whose server fails every request has as many gaps as pages. The count is
     * always whole.</p>
     */
    public static final int SAMPLE_SIZE = 10;

    /** A listing that saw everything. */
    public static final ListingGaps NONE = new ListingGaps(0, List.of());

    private final long count;
    private final List<Gap> sample;

    private ListingGaps(long count, List<Gap> sample) {
        this.count = count;
        this.sample = List.copyOf(sample);
    }

    /**
     * Whether the listing saw everything it set out to.
     *
     * @return true when there are no gaps
     */
    public boolean isComplete() {
        return count == 0;
    }

    /**
     * How many places the listing could not see into.
     *
     * @return The count, each place once
     */
    public long count() {
        return count;
    }

    /**
     * The first gaps, in the order they were met.
     *
     * @return At most {@link #SAMPLE_SIZE} gaps
     */
    public List<Gap> sample() {
        return sample;
    }

    /**
     * Says where the listing fell short, in terms a status readback may carry.
     *
     * @return For example {@code "2 place(s) could not be read: https://example.com/ (HTTP 503),
     *         https://example.com/guide/ (no answer: HttpTimeoutException)"}, or an empty string
     *         for a complete listing
     */
    public String describe() {
        if (isComplete()) {
            return "";
        }
        StringBuilder text = new StringBuilder()
            .append(count).append(" place(s) could not be read: ");
        for (int i = 0; i < sample.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            Gap gap = sample.get(i);
            text.append(gap.location()).append(" (").append(gap.reason()).append(')');
        }
        if (count > sample.size()) {
            text.append(" and ").append(count - sample.size()).append(" more");
        }
        return text.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ListingGaps gaps && count == gaps.count
            && sample.equals(gaps.sample);
    }

    @Override
    public int hashCode() {
        return Objects.hash(count, sample);
    }

    @Override
    public String toString() {
        return isComplete() ? "ListingGaps[none]" : "ListingGaps[" + describe() + "]";
    }

    /**
     * One place a listing could not see into.
     *
     * @param location The page or file, as the source names its documents
     * @param reason   Why, in fixed terms: see the class documentation
     */
    public record Gap(String location, String reason) {

        /**
         * @throws NullPointerException if either argument is null
         */
        public Gap {
            Objects.requireNonNull(location, "location must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /**
     * Collects one run's gaps as its crawl meets them.
     *
     * <p>Each location is counted once, however often the crawl met it, and only the first
     * {@link #SAMPLE_SIZE} are kept by name. Thread-safe: a source's crawl and a fetch may record
     * into the same run.</p>
     */
    public static final class Recorder {

        private final List<Gap> sample = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();

        /**
         * Records that the listing could not see into a place.
         *
         * @param location The page or file
         * @param reason   Why, in fixed terms
         * @throws NullPointerException if either argument is null
         */
        public synchronized void record(String location, String reason) {
            Gap gap = new Gap(location, reason);
            if (seen.add(location) && sample.size() < SAMPLE_SIZE) {
                sample.add(gap);
            }
        }

        /**
         * What has been recorded so far.
         *
         * @return An immutable snapshot
         */
        public synchronized ListingGaps snapshot() {
            return seen.isEmpty() ? NONE : new ListingGaps(seen.size(), sample);
        }
    }
}
