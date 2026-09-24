package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.core.hash.XxHash64;
import com.eonmux.excalibur.graph.erasure.CompactionPolicy.CompactionStats;
import com.eonmux.excalibur.retrieval.lexical.Bm25Parameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A lexical index that grows, remembers when things became visible, and can forget.
 *
 * <h2>Segments</h2>
 * <p>Documents are added into a new immutable segment rather than into the existing index. That is
 * what makes addition incremental: nothing already written is rewritten, and the cost of adding a
 * thousand documents to a hundred-million-document store is the cost of a thousand documents.
 * Segments accumulate and are periodically merged, which is the same bargain the storage engine
 * already makes.</p>
 *
 * <h2>Epochs</h2>
 * <p>Each segment records the epoch at which it became visible, and a query names the epoch it is
 * reading at. A segment published after that epoch is not searched, so a query sees the corpus as
 * it stood when it started however much is written while it runs. Visibility is per segment rather
 * than per posting because that is exactly the guarantee wanted - a new index becoming visible
 * atomically at an epoch boundary - and per-posting epochs would cost bytes on every posting to
 * express something finer than anyone asked for.</p>
 *
 * <h2>Erasure</h2>
 * <p>Erasure is a two-part promise: recorded durably now, physically removed later. The record goes
 * to {@link DocumentErasureLog} before the call returns, so an obligation survives a crash; the
 * removal happens in a compaction, which rewrites segments without the erased documents. Until then
 * the documents are tombstoned and no query returns them. Hiding alone would not be erasure, and
 * rewriting the whole store on every request would make erasure a denial of service - which is why
 * the two halves are separate, and why {@link CompactionPlanner} rather than the caller decides
 * when the second one happens.</p>
 *
 * <h2>Identifiers</h2>
 * <p>Callers name documents by their own key, never by an internal id. Internal ids are dense
 * within a segment and are renumbered by compaction; a key is not.</p>
 *
 * <h2>Concurrency</h2>
 * <p>One writer at a time. Readers at any epoch may run throughout, since publication is an atomic
 * manifest replacement and nothing already published is mutated in place. Two processes writing the
 * same directory would both allocate segment names from their own counters and overwrite each
 * other's, so that arrangement is not supported rather than half-defended against.</p>
 */
public final class LexicalStore implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexicalStore.class);

    private static final String SEGMENT_PREFIX = "seg-";
    private static final String SCRATCH_PREFIX = ".compact-";

    /** One result: the caller's key, and the score. */
    public record Hit(String key, double score) { }

    /** A segment written to disk but not yet named in the manifest, so not yet readable. */
    public record Staged(String name, int docCount) { }

    /**
     * A segment the manifest names that this store could not open.
     *
     * @param name          The segment
     * @param reason        What is wrong with it
     * @param liveDocuments Documents it holds that are not erased - each one missing from every
     *                      answer until the segment is rebuilt or restored
     * @param rebuildable   Whether its key table is sound, so a compaction can rebuild it from the
     *                      text it is given; false means only restoring its files brings it back
     */
    public record DamagedSegment(String name, String reason, int liveDocuments,
                                 boolean rebuildable) {
        public DamagedSegment {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /**
     * One group's replacement segment, built and waiting to be published.
     *
     * @param replaced  The segments it replaces, exactly as the manifest held them when it was
     *                  built - which is what {@link #commit} compares against to notice that
     *                  something changed underneath it
     * @param name      The new segment
     * @param epoch     The epoch it becomes visible at
     * @param survivors Documents it holds
     * @param before    Documents the segments it replaces held between them
     */
    public record PreparedGroup(List<SegmentManifest.Entry> replaced, String name, long epoch,
                                int survivors, int before) {
        public PreparedGroup {
            replaced = List.copyOf(replaced);
        }
    }

    /**
     * Everything one group's rewrite will read, copied out of the store.
     *
     * <p>Taken while the store is held exclusively so that the rewrite itself needs no lock at
     * all: the segment handles are immutable once open, the entries are records, and the tombstone
     * marks are copied because an erasure mutates the store's own copy in place. What the rewrite
     * then works from cannot be changed underneath it, which is what allows it to run for as long
     * as it needs to without a query waiting on it.</p>
     */
    public record GroupSnapshot(List<SegmentManifest.Entry> entries, List<SegmentKeys> keys,
                                List<boolean[]> tombstoned) {
        public GroupSnapshot {
            entries = List.copyOf(entries);
            keys = List.copyOf(keys);
            tombstoned = List.copyOf(tombstoned);
        }
    }

    /** What a whole compaction will read. */
    public record CompactionSnapshot(List<GroupSnapshot> groups, String reason) {
        public CompactionSnapshot {
            groups = List.copyOf(groups);
        }
    }

    /**
     * Everything a compaction has built, waiting for the one write that publishes it.
     *
     * @param groups    The replacements built
     * @param abandoned Planned groups left as they were because a surviving key's text could not
     *                  be read back; carried to {@link #commit} so that a compaction which
     *                  publishes nothing for that reason is not reported as one that ran
     * @param reason    Why the compaction was planned
     */
    public record PreparedCompaction(List<PreparedGroup> groups, int abandoned, String reason) {
        public PreparedCompaction {
            groups = List.copyOf(groups);
            if (abandoned < 0) {
                throw new IllegalArgumentException("abandoned must not be negative: " + abandoned);
            }
        }

        /** Whether anything was built. */
        public boolean isEmpty() {
            return groups.isEmpty();
        }
    }

    private final Path directory;
    private final CompactionPlanner planner;
    private final Clock clock;
    private final Bm25Parameters bm25;
    private final DocumentErasureLog erasureLog;
    private SegmentManifest manifest;
    private final Map<String, PostingsIndex> open = new LinkedHashMap<>();
    private final Map<String, SegmentKeys> keys = new LinkedHashMap<>();
    private final Map<String, boolean[]> tombstones = new LinkedHashMap<>();

    /**
     * Per segment, the documents a republished copy replaced, mapped to the epoch that copy
     * became visible at.
     *
     * <p>A replaced copy is tombstoned the moment its replacement is published, and a tombstone
     * holds at every epoch, while the replacement is visible only from its own. A reader still
     * behind that epoch would otherwise find neither copy - the chunk matching no query at all,
     * for as long as the reader lags. So the tombstone is written as it always was, and this
     * records the exception to it: a reader before the replacement's epoch still sees the copy
     * it replaced, and a reader at or after it sees only the replacement. Never both.</p>
     *
     * <p>Held in memory only. What it describes ends once no reader precedes the replacement,
     * and a node that restarts resumes its epoch past every epoch this store has published at,
     * so no reader after a restart precedes one; the tombstone on disk is then the whole truth.
     * Entries leave once the reader floor passes them - see {@link #compactableEntries} - and
     * with their segment. Concurrent maps, because a search reads them under the caller's
     * shared lock while planning prunes them.</p>
     */
    private final Map<String, Map<Integer, Long>> replacedAt =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Segments the manifest names that this store could not open, and why.
     *
     * <p>Filled at open, because a segment's files do not change while the store holds them; an
     * entry leaves only when a compaction replaces its segment.</p>
     */
    private final Map<String, String> unreadable = new LinkedHashMap<>();

    /** Of {@link #unreadable}, the segments whose key table is sound enough to rebuild from. */
    private final Set<String> rebuildable = new HashSet<>();

    /**
     * Segments that opened but were written in an older format: readable, exact, and without the
     * checksums - on the columns, the key table or the tombstones - that would let damage to them
     * be detected. Filled at open, and emptied by the compactions that rewrite them.
     */
    private final Set<String> outdated = new HashSet<>();
    private final java.util.concurrent.atomic.AtomicLong nextSegment =
        new java.util.concurrent.atomic.AtomicLong();

    /**
     * Keys dropped because nothing left on disk can bring them back: a rewrite dropped them
     * because their stored text is permanently unreadable, or an owed erasure removed the
     * segment holding them because neither its postings nor its key table could be read.
     *
     * <p>Counted since this store was opened, and reported rather than only logged: each is a
     * chunk that stopped matching any query because storage lost it, and an operator needs the
     * number to know that something has to be restored and reindexed. See
     * {@link UnrecoverableTextException} and {@link #removeUnrecoverable}.</p>
     */
    private final java.util.concurrent.atomic.AtomicLong unreadableKeysDropped =
        new java.util.concurrent.atomic.AtomicLong();

    private LexicalStore(Path directory, SegmentManifest manifest, CompactionPlanner planner,
                         Clock clock, Bm25Parameters bm25, DocumentErasureLog erasureLog) {
        this.directory = directory;
        this.manifest = manifest;
        this.planner = planner;
        this.clock = clock;
        this.bm25 = bm25;
        this.erasureLog = erasureLog;
        long next = 0;
        for (SegmentManifest.Entry entry : manifest.entries()) {
            long index = Long.parseLong(entry.name().substring(entry.name().indexOf('-') + 1));
            next = Math.max(next, index + 1);
        }
        nextSegment.set(next);
    }

    /** Opens a store with the default compaction policy and BM25 pair, creating it if absent. */
    public static LexicalStore open(Path directory) throws IOException {
        return open(directory, CompactionPlanner.standard(), Clock.systemUTC(),
            Bm25Parameters.DEFAULT);
    }

    /** Opens a store with the default compaction policy, scored with a chosen BM25 pair. */
    public static LexicalStore open(Path directory, Bm25Parameters bm25) throws IOException {
        return open(directory, CompactionPlanner.standard(), Clock.systemUTC(), bm25);
    }

    /**
     * Opens a store with the default BM25 pair, creating it if absent.
     *
     * @param planner Decides when and what to compact
     * @param clock   Reads the time erasures are promised and compaction is judged against
     */
    public static LexicalStore open(Path directory, CompactionPlanner planner, Clock clock)
            throws IOException {
        return open(directory, planner, clock, Bm25Parameters.DEFAULT);
    }

    /**
     * Opens a store, creating it if absent.
     *
     * @param planner Decides when and what to compact
     * @param clock   Reads the time erasures are promised and compaction is judged against
     * @param bm25    What every segment this store writes is built with, and what every segment
     *                it already holds must have been built with
     * @throws Bm25MismatchException if a segment was built with a different pair
     */
    public static LexicalStore open(Path directory, CompactionPlanner planner, Clock clock,
                                    Bm25Parameters bm25) throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(planner, "planner must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(bm25, "bm25 must not be null");
        Files.createDirectories(directory);
        LexicalStore store = new LexicalStore(directory, SegmentManifest.read(directory), planner,
            clock, bm25, DocumentErasureLog.open(directory));
        store.removeUnreferenced();
        store.inspectSegments();
        if (!store.erasureLog.outstanding().isEmpty()) {
            store.removeUnrecoverable("the erasure log owes " + store.erasureLog.outstanding()
                .size() + " erasure(s) this store cannot show it holds none of");
        }
        store.reapplyErasures();
        return store;
    }

    /**
     * Checks every named segment before a query can, and decides what to do about the bad ones.
     *
     * <h2>Why the BM25 pair fails the whole store</h2>
     * <p>Checked here rather than left to the first query that touches a segment. Segments are
     * opened lazily, but writing one is not: a store that opened under a changed pair would go on
     * accepting documents and end up holding segments built two different ways, with only the
     * older ones failing and only when something searched them. That is a configuration mistake
     * with an obvious remedy, so it stops the open.</p>
     *
     * <h2>Why a damaged segment does not</h2>
     * <p>A torn segment is the opposite kind of problem: nothing the operator can change makes
     * it open, so failing here makes the store unopenable for good, and the manifest names the
     * segment so nothing will ever clean it up either. Dropping it quietly is no better - its
     * documents would simply stop being found. So it is neither: the segment is left exactly
     * where it is, with its entry in the manifest, which is what keeps
     * {@link #removeUnreferenced()} from deleting the bytes somebody may yet recover; it is kept
     * out of every query, because half a segment is a wrong answer rather than a partial one;
     * and it is reported, in the log at open and through {@link #damagedSegments()}, so that a
     * store answering from four segments out of five says so. Restoring the files makes it
     * searchable again at the next open, with nothing to undo.</p>
     *
     * <h2>Why a damaged segment can usually be rebuilt</h2>
     * <p>A rewrite never reads a segment's postings: it reads the segment's names and asks the
     * caller for each one's text, which is how every compaction already works. So a segment
     * whose postings are damaged and whose key table is sound can be rebuilt exactly, and the
     * next compaction does so without being asked - see {@link #requiredRewrites()}. The key
     * table is checked here, while the store is deciding what it holds, because a rebuild
     * trusts it completely.</p>
     *
     * <h2>The files beside the segment</h2>
     * <p>The key table and the tombstones are checked here too, against the checksums written
     * with them, and damage to either is worse than damage to the postings: it cannot be
     * rebuilt. A rebuild reads the names to ask for each document's text, so names that are not
     * the ones written leave nothing to ask with; and it drops every tombstoned document, so
     * marks that cannot be trusted leave it unable to tell an erased document from a live one -
     * rebuilding from them would make erased documents readable again. Either keeps the segment
     * out of every query exactly as damaged postings do, and it is never rebuilt; an owed
     * erasure removes it, for the reason {@link #removeUnrecoverable} gives. The key lookup table
     * is the exception: it is derived from the names, so one that does not match its checksum is
     * rebuilt from them, which reproduces the bytes the checksum was taken over.</p>
     *
     * <h2>Older formats</h2>
     * <p>A segment written before its format carried column checksums, before its key table
     * carried checksums, or whose tombstones were written without a header opens and answers
     * exactly, and is noted so that compaction rewrites it into the current format in time.
     * Nothing is upgraded in place: a checksum written now would vouch for bytes nothing has
     * vouched for. Tombstones are the one exception, because they change: the next erasure or
     * replacement in the segment writes them at the current version.</p>
     *
     * @throws Bm25MismatchException if a segment was built with a different BM25 pair
     * @throws IOException if a segment's tombstones or key table checksums were written by a
     *                     newer build: nothing here can read them, and treating them as damage
     *                     would let an owed erasure remove a segment that is not damaged
     */
    private void inspectSegments() throws IOException {
        for (SegmentManifest.Entry entry : manifest.entries()) {
            SegmentInspection found = inspectSegment(entry);
            if (found.sound()) {
                if (!found.current()) {
                    outdated.add(entry.name());
                }
            } else {
                quarantine(entry, found);
            }
        }
        if (!outdated.isEmpty()) {
            LOGGER.warn("{} segment(s) of the lexical store at {} were written in an older format "
                + "and carry no checksums on their columns, key table or tombstones; they answer "
                + "exactly, and compaction rewrites them into the current format a few at a time",
                outdated.size(), directory);
        }
    }

    /**
     * What checking one segment found.
     *
     * @param damage    Why its postings cannot be served, or null
     * @param lostNames Why its names cannot be trusted, or null
     * @param lostMarks Why its tombstones cannot be trusted, or null
     * @param current   Whether everything checked carries the current format's checksums
     */
    private record SegmentInspection(String damage, String lostNames, String lostMarks,
                                     boolean current) {

        boolean sound() {
            return damage == null && lostNames == null && lostMarks == null;
        }
    }

    /** Checks one segment's columns, key table and tombstones, changing nothing but the lookup table. */
    private SegmentInspection inspectSegment(SegmentManifest.Entry entry) throws IOException {
        Path segment = directory.resolve(entry.name());
        String damage = null;
        boolean current = true;
        try {
            IndexMeta meta = IndexMeta.read(segment);
            if (!meta.bm25().equals(bm25)) {
                throw new Bm25MismatchException(segment, meta.bm25(), bm25);
            }
            SegmentGeometry.verify(segment, meta);
            LexicalSegmentIntegrity.requireIntact(segment, meta);
            current = meta.checksummed();
        } catch (Bm25MismatchException e) {
            throw e;
        } catch (IOException e) {
            damage = e.getMessage();
        }
        String lostNames = null;
        try {
            KeyChecksums.Recorded sums = KeyChecksums.read(directory, entry.name());
            if (sums == null) {
                current = false;
            } else {
                lostNames = verifyKeyTable(entry, sums);
            }
        } catch (KeyChecksums.UnsupportedVersionException e) {
            throw e;
        } catch (IOException e) {
            // The digests themselves are gone, which says nothing about the names: they are held
            // to what their structure can show, as a segment written before checksums is, and a
            // rebuild writes the segment again with digests of its own.
            damage = damage == null ? e.getMessage() : damage + "; " + e.getMessage();
        }
        String lostMarks = null;
        try {
            TombstoneFile.Marks marks = TombstoneFile.read(
                TombstoneFile.of(directory, entry.name()), entry.docCount());
            if (marks.version() != TombstoneFile.VERSION) {
                current = false;
            }
        } catch (TombstoneFile.UnsupportedVersionException e) {
            throw e;
        } catch (IOException e) {
            lostMarks = e.getMessage();
        }
        return new SegmentInspection(damage, lostNames, lostMarks, current);
    }

    /**
     * Checks a segment's key table against the checksums written with it.
     *
     * <p>A lookup table that does not match is rebuilt from the names, which are checked first,
     * and checked again: the table is a pure function of the names, so the rebuilt one matches
     * unless the names or the rebuild are at fault.</p>
     *
     * @return Why the names cannot be trusted, or null when they are the ones written
     */
    private String verifyKeyTable(SegmentManifest.Entry entry, KeyChecksums.Recorded sums)
            throws IOException {
        String name = entry.name();
        if (sums.docCount() != entry.docCount()) {
            return "its key table's checksums were recorded for " + sums.docCount()
                + " name(s) where the manifest records " + entry.docCount();
        }
        for (String suffix : List.of(SegmentKeys.KEYS_SUFFIX, SegmentKeys.INDEX_SUFFIX)) {
            String wrong = KeyChecksums.mismatch(directory, name, sums, suffix);
            if (wrong != null) {
                return wrong;
            }
        }
        String table = KeyChecksums.mismatch(directory, name, sums, SegmentKeys.MAP_SUFFIX);
        if (table == null) {
            return null;
        }
        Files.deleteIfExists(directory.resolve(name + SegmentKeys.MAP_SUFFIX));
        openKeys(name);
        String rebuilt = KeyChecksums.mismatch(directory, name, sums, SegmentKeys.MAP_SUFFIX);
        if (rebuilt != null) {
            return "its key lookup table did not match its checksum, and the one rebuilt from "
                + "its names does not either: " + rebuilt;
        }
        LOGGER.warn("Rebuilt the key lookup table of segment {} of the lexical store at {} from "
            + "its names, because it did not match the checksum written with it: {}", name,
            directory, table);
        return null;
    }

    /**
     * Keeps a segment that could not be opened out of every query, and works out whether it can
     * be rebuilt.
     */
    private void quarantine(SegmentManifest.Entry entry, SegmentInspection found) {
        String keyDamage = found.lostNames();
        if (keyDamage == null) {
            try {
                keyDamage = openKeys(entry.name()).damage(entry.docCount());
            } catch (IOException | RuntimeException e) {
                keyDamage = String.valueOf(e.getMessage());
            }
        }
        if (keyDamage == null && found.lostMarks() == null) {
            unreadable.put(entry.name(), found.damage());
            rebuildable.add(entry.name());
            LOGGER.error("Segment {} of the lexical store at {} cannot be opened, so the {} "
                + "document(s) it holds will not be found by any query until it is rebuilt; its "
                + "key table is sound, so the next compaction rebuilds it from stored text: {}",
                entry.name(), directory, entry.liveCount(), found.damage());
            return;
        }
        List<String> reasons = new ArrayList<>(3);
        if (found.damage() != null) {
            reasons.add(found.damage());
        }
        if (keyDamage != null) {
            reasons.add("its key table cannot be relied on" + (reasons.isEmpty() ? "" : " either")
                + ", so it cannot be rebuilt: " + keyDamage);
        }
        if (found.lostMarks() != null) {
            reasons.add("its tombstones cannot be relied on, so which of its documents were "
                + "erased or replaced is unknown and it can be neither served nor rebuilt: "
                + found.lostMarks());
        }
        String why = String.join("; ", reasons);
        unreadable.put(entry.name(), why);
        LOGGER.error("Segment {} of the lexical store at {} cannot be served, so the {} "
            + "document(s) it holds will not be found by any query until its files are restored "
            + "or its documents reindexed; no compaction can rebuild it, and its files are left "
            + "untouched until an erasure is owed, which removes it: {}",
            entry.name(), directory, entry.liveCount(), why);
    }

    /**
     * Whether a segment is one this store can neither search nor rebuild: its postings could not
     * be opened and its key table cannot be relied on either.
     */
    private boolean unrecoverable(String name) {
        return unreadable.containsKey(name) && !rebuildable.contains(name);
    }

    /**
     * Takes every unrecoverable segment out of the store, because an erasure is owed.
     *
     * <h3>Why an erasure is what removes it</h3>
     * <p>An erasure is discharged by showing that no segment holds the erased name, and a segment
     * whose key table cannot be read cannot show that: its names are the only record of what it
     * holds. Left in place it would hold every erasure hostage - none could be marked in it, none
     * could ever be settled against it, and each would stay owed past its deadline for as long as
     * the damaged files sat on disk. Nothing is lost that the store could answer with: the
     * segment is kept out of every query, and without its names no compaction can rebuild it, so
     * reindexing its documents was already the only way back for them. Removing it keeps the
     * promise an erasure makes - the bytes that might hold the erased name are gone - and it is
     * the same decision a rewrite makes about a chunk whose stored text is permanently
     * unreadable, for the same reason; see {@link UnrecoverableTextException}.</p>
     *
     * <p>Only when an erasure is owed. With nothing owed a damaged segment costs nothing but the
     * documents it cannot serve, and it stays on disk and reported, where an operator can still
     * copy it aside.</p>
     *
     * <p>The manifest stops naming the segments before their files go, as a compaction retires
     * segments, so a crash between the two leaves files the next open sweeps up as unreferenced
     * rather than a manifest naming what is gone. Each segment's documents are counted in
     * {@link #unreadableKeysDropped()}.</p>
     *
     * @param why What is owed, for the log line
     */
    private void removeUnrecoverable(String why) throws IOException {
        List<SegmentManifest.Entry> doomed = new ArrayList<>();
        for (SegmentManifest.Entry entry : manifest.entries()) {
            if (unrecoverable(entry.name())) {
                doomed.add(entry);
            }
        }
        if (doomed.isEmpty()) {
            return;
        }
        manifest.removeAll(names(doomed));
        manifest.write(directory);
        for (SegmentManifest.Entry entry : doomed) {
            deleteSegment(entry.name());
            String damage = unreadable.remove(entry.name());
            outdated.remove(entry.name());
            unreadableKeysDropped.addAndGet(entry.liveCount());
            LOGGER.error("Removed segment {} of the lexical store at {}: {}, and it cannot be "
                + "read or rebuilt ({}), so its {} document(s) could never be shown free of an "
                + "erased name. Reindex the documents of the sources it held to make them "
                + "searchable again", entry.name(), directory, why, damage, entry.liveCount());
        }
    }

    /**
     * Segments the manifest names that could not be opened, mapped to what is wrong with them.
     *
     * <p>Empty for a healthy store. {@link #damagedSegments()} says the same with the counts and
     * the prognosis a report needs.</p>
     */
    public Map<String, String> unreadableSegments() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(unreadable));
    }

    /**
     * Every segment the manifest names that could not be opened, in manifest order.
     *
     * <p>Empty for a healthy store. A caller that reports on this store should surface it: these
     * documents are still on disk and are not in {@link #liveDocuments(long)}, and they are the
     * difference between what the store contains and what it can answer with.</p>
     */
    public List<DamagedSegment> damagedSegments() {
        if (unreadable.isEmpty()) {
            return List.of();
        }
        List<DamagedSegment> damaged = new ArrayList<>(unreadable.size());
        for (SegmentManifest.Entry entry : manifest.entries()) {
            String why = unreadable.get(entry.name());
            if (why != null) {
                damaged.add(new DamagedSegment(entry.name(), why, entry.liveCount(),
                    rebuildable.contains(entry.name())));
            }
        }
        return List.copyOf(damaged);
    }

    /**
     * Segments written in an older format and not yet rewritten, in manifest order.
     *
     * <p>Each answers exactly; what it lacks is the column checksums, and before version 2 the
     * block bounds that let a query skip what cannot reach the top results.</p>
     */
    public List<String> outdatedSegments() {
        if (outdated.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(outdated.size());
        for (SegmentManifest.Entry entry : manifest.entries()) {
            if (outdated.contains(entry.name())) {
                names.add(entry.name());
            }
        }
        return List.copyOf(names);
    }

    /** What compaction has to rewrite whatever its policy decides. */
    private CompactionPlanner.RequiredRewrites requiredRewrites() {
        if (rebuildable.isEmpty() && outdated.isEmpty()) {
            return CompactionPlanner.RequiredRewrites.NONE;
        }
        return new CompactionPlanner.RequiredRewrites(rebuildable, outdated);
    }

    /** The entries a query may actually read, which is all of them on a healthy store. */
    private List<SegmentManifest.Entry> readable(List<SegmentManifest.Entry> entries) {
        if (unreadable.isEmpty()) {
            return entries;
        }
        List<SegmentManifest.Entry> usable = new ArrayList<>(entries.size());
        for (SegmentManifest.Entry entry : entries) {
            if (!unreadable.containsKey(entry.name())) {
                usable.add(entry);
            }
        }
        return usable;
    }

    /**
     * Adds documents as one new segment, visible from {@code epoch}.
     *
     * @param documents Caller key to text, in the order they should be numbered
     * @param epoch     The epoch at which this segment becomes visible
     * @return The segment's name, or null when there was nothing to add
     */
    public String add(Map<String, String> documents, long epoch) throws IOException {
        Objects.requireNonNull(documents, "documents must not be null");
        Staged staged = stage(documents);
        if (staged == null) {
            return null;
        }
        publish(List.of(staged), epoch);
        return staged.name();
    }

    /**
     * Writes documents as a segment without making them readable.
     *
     * <p>Separate from publication so a writer with more documents than it can hold in memory can
     * flush as often as it needs to and still have everything become visible at one instant. A
     * batch that flushed and published as it went would let a query see half of it, which for a
     * batch that is one document's chunks is a wrong answer rather than a partial one.</p>
     *
     * @param documents Caller key to text, in the order they should be numbered
     * @return The staged segment, or null when there was nothing to write
     */
    public Staged stage(Map<String, String> documents) throws IOException {
        Objects.requireNonNull(documents, "documents must not be null");
        if (documents.isEmpty()) {
            return null;
        }
        // Every key checked before any byte is written, so a bad one in the middle of a batch
        // fails the batch rather than leaving half of it on disk to be swept up later.
        for (String key : documents.keySet()) {
            DocumentKeys.require(key);
        }
        String name = SEGMENT_PREFIX + nextSegment.getAndIncrement();
        PostingsWriter writer = new PostingsWriter(bm25);
        try (SegmentKeys.Writer names = new SegmentKeys.Writer(directory, name)) {
            for (Map.Entry<String, String> document : documents.entrySet()) {
                names.add(document.getKey());
                writer.add(document.getValue());
            }
            names.finish();
        }
        writer.write(directory.resolve(name));
        return new Staged(name, documents.size());
    }

    /**
     * Makes staged segments readable, all at one epoch, replacing what they name.
     *
     * <p>One manifest replacement for the whole set, which is a single atomic rename. A reader
     * either sees every one of them or none, whatever it was doing while they were written.</p>
     *
     * <h3>Republishing a key</h3>
     * <p>A key this batch carries retires the copy the store already held under that name, so a
     * document that is ingested again is one document rather than two. Without that a re-crawl
     * would return the same chunk twice and go on scoring the text it used to hold, which is not
     * a stale answer but a wrong one: the chunk's own storage entry holds the new text, so the
     * hit is reported against words the chunk no longer contains.</p>
     *
     * <p>Supersession is against segments published <em>before</em> this batch. Two documents
     * with the same key inside one batch are not a replacement and a copy - they are one name
     * given to two documents, which is a caller's error rather than a history to reconcile.</p>
     *
     * @param staged Segments from {@link #stage}
     * @param epoch  The epoch at which they become visible
     * @return How many earlier copies were retired
     */
    public int publish(List<Staged> staged, long epoch) throws IOException {
        Objects.requireNonNull(staged, "staged must not be null");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative: " + epoch);
        }
        if (staged.isEmpty()) {
            return 0;
        }
        // Read before the batch joins the manifest, so a segment supersedes what the store held
        // rather than what arrived beside it. A segment whose key table cannot be read is passed
        // over: nothing in it is searched, so no earlier copy there can be returned beside the
        // new one, and refusing the batch over it would stop the reindex that is the only way
        // back for its documents.
        List<SegmentManifest.Entry> targets = new ArrayList<>();
        for (SegmentManifest.Entry entry : manifest.entries()) {
            if (!unrecoverable(entry.name())) {
                targets.add(entry);
            }
        }
        int replaced = supersede(staged, targets, epoch);

        int documents = 0;
        for (Staged segment : staged) {
            manifest.add(new SegmentManifest.Entry(
                segment.name(), epoch, segment.docCount(), 0));
            documents += segment.docCount();
        }
        manifest.write(directory);
        LOGGER.info("Published {} segment(s) with {} documents at epoch {}{}",
            staged.size(), documents, epoch,
            replaced == 0 ? "" : ", retiring " + replaced + " earlier copy(ies)");
        return replaced;
    }

    /**
     * Retires the earlier copies of every name a batch carries.
     *
     * <p>Each incoming name is looked up in the segments already published rather than compared
     * against them: a batch of a thousand chunks costs a few thousand probes, where a scan would
     * cost one pass over every chunk identifier in the store.</p>
     *
     * <p>No entry in the erasure log. A replaced copy is not a promise made to anyone - it is the
     * previous version of a document that is still present, and demanding a compaction deadline
     * for it would make every re-crawl an erasure obligation.</p>
     *
     * <p>Each copy retired here stays visible to a reader before {@code epoch}, which cannot see
     * the batch replacing it; see {@link #replacedAt}. It is recorded there before it is marked,
     * so no reader can find it marked and not yet excepted.</p>
     *
     * @param epoch The epoch the batch becomes visible at
     * @return How many documents were retired
     */
    private int supersede(List<Staged> staged, List<SegmentManifest.Entry> targets, long epoch)
            throws IOException {
        if (targets.isEmpty()) {
            return 0;
        }
        List<SegmentKeys> targetKeys = new ArrayList<>(targets.size());
        List<boolean[]> targetMarks = new ArrayList<>(targets.size());
        List<Retired> retired = new ArrayList<>(targets.size());
        for (SegmentManifest.Entry entry : targets) {
            targetKeys.add(openKeys(entry.name()));
            boolean[] marks = loadTombstones(entry.name(), entry.docCount());
            targetMarks.add(marks);
            Map<Integer, Long> replaced = replacedAt.computeIfAbsent(entry.name(),
                name -> new java.util.concurrent.ConcurrentHashMap<>());
            retired.add(new Retired(marks, doc -> replaced.put(doc, epoch)));
        }

        for (Staged segment : staged) {
            SegmentKeys incoming = openKeys(segment.name());
            for (int doc = 0; doc < incoming.count(); doc++) {
                byte[] key = incoming.rawAt(doc);
                for (int at = 0; at < targets.size(); at++) {
                    targetKeys.get(at).find(key, retired.get(at));
                }
            }
        }

        int total = 0;
        for (int at = 0; at < targets.size(); at++) {
            int count = retired.get(at).count();
            if (count == 0) {
                continue;
            }
            SegmentManifest.Entry entry = targets.get(at);
            boolean[] marks = targetMarks.get(at);
            writeTombstones(entry.name(), marks);
            manifest.replace(entry.name(), new SegmentManifest.Entry(entry.name(), entry.epoch(),
                entry.docCount(), countMarks(marks)));
            total += count;
        }
        return total;
    }

    /**
     * The tombstone count a segment's marks actually carry.
     *
     * <p>Counted rather than added to what the manifest said, because the two can disagree: a
     * crash between writing a tombstone file and writing the manifest leaves the manifest behind,
     * and marking again on top of a count that already included those marks would tombstone the
     * segment twice over - which reads as a segment with nothing live in it, so it stops being
     * searched at all.</p>
     */
    private static int countMarks(boolean[] marks) {
        int total = 0;
        for (boolean mark : marks) {
            if (mark) {
                total++;
            }
        }
        return total;
    }

    /**
     * Marks documents of one segment as gone, counting only the ones that were not already.
     *
     * <p>Counting the transition rather than the match is what keeps the manifest's tombstone
     * total equal to the number of marks actually set, which the compaction policy reads as the
     * proportion of a segment worth reclaiming.</p>
     */
    private static final class Retired implements IntConsumer {

        private static final IntConsumer NOTHING = doc -> { };

        private final boolean[] marks;
        private final IntConsumer beforeMarking;
        private int count;

        Retired(boolean[] marks) {
            this(marks, NOTHING);
        }

        /**
         * @param marks         The segment's tombstones, set in place
         * @param beforeMarking Told of each document about to be marked, before it is
         */
        Retired(boolean[] marks, IntConsumer beforeMarking) {
            this.marks = marks;
            this.beforeMarking = beforeMarking;
        }

        @Override
        public void accept(int doc) {
            if (!marks[doc]) {
                beforeMarking.accept(doc);
                marks[doc] = true;
                count++;
            }
        }

        int count() {
            return count;
        }
    }

    /** Discards staged segments that will never be published. */
    public void discard(List<Staged> staged) throws IOException {
        Objects.requireNonNull(staged, "staged must not be null");
        for (Staged segment : staged) {
            deleteSegment(segment.name());
        }
    }

    /**
     * Searches every segment visible at an epoch.
     *
     * @param terms   Query terms
     * @param weights Weight per term
     * @param limit   Results wanted
     * @param epoch   The epoch to read at
     * @return Hits, best first
     */
    public List<Hit> search(String[] terms, double[] weights, int limit, long epoch)
            throws IOException {
        return search(terms, weights, limit, epoch, null);
    }

    /**
     * Searches every segment visible at an epoch, over the documents a filter admits.
     *
     * <p>The filter is applied where the ranking is decided, beside the tombstones, and for the
     * same reason: a page cut from the best matches and filtered afterwards comes back short
     * whenever the best matches are ones the filter refuses, while admitted documents that belong
     * on it are never looked at. A document is judged by its name, read from the segment's key
     * file only for a document that matches a query term.</p>
     *
     * @param terms   Query terms
     * @param weights Weight per term
     * @param limit   Results wanted
     * @param epoch   The epoch to read at
     * @param admits  Whether a document, by the name it was stored under, may be returned; null
     *                admits every document
     * @return Hits, best first, every one admitted
     */
    public List<Hit> search(String[] terms, double[] weights, int limit, long epoch,
                            Predicate<String> admits) throws IOException {
        List<SegmentManifest.Entry> visible = readable(manifest.visibleAt(epoch));
        PostingsIndex.Stats stats = collectionStats(visible, terms);
        List<Hit> merged = new ArrayList<>();
        for (SegmentManifest.Entry entry : visible) {
            Map<Integer, Long> replaced = replacedAt.get(entry.name());
            if (entry.liveCount() == 0 && stillVisible(replaced, epoch) == 0) {
                continue;
            }
            PostingsIndex index = openSegment(entry.name());
            SegmentKeys segmentKeys = keys.get(entry.name());
            boolean[] erased = tombstones.get(entry.name());
            // The tombstones are handed to the scorer rather than applied to what it returns. A
            // segment's best matches are often exactly the documents that were erased, and
            // filtering afterwards would then leave the page short of live documents the segment
            // still holds. A copy whose replacement this reader cannot see yet is live to it.
            IntPredicate live = erased == null ? null
                : replaced == null || replaced.isEmpty() ? doc -> !erased[doc]
                : doc -> !erased[doc] || replaced.getOrDefault(doc, Long.MIN_VALUE) > epoch;
            if (admits != null) {
                IntPredicate named = doc -> admits.test(segmentKeys.at(doc));
                live = live == null ? named : live.and(named);
            }
            for (PostingsIndex.Hit hit : index.search(terms, weights, limit, stats, live)) {
                merged.add(new Hit(segmentKeys.at(hit.docId()), hit.score()));
            }
        }
        merged.sort(Comparator.comparingDouble(Hit::score).reversed());
        return merged.size() > limit ? merged.subList(0, limit) : merged;
    }

    /**
     * How many of a segment's replaced copies a reader at an epoch still sees, because their
     * replacements became visible after it.
     */
    private static int stillVisible(Map<Integer, Long> replaced, long epoch) {
        if (replaced == null) {
            return 0;
        }
        int visible = 0;
        for (long from : replaced.values()) {
            if (from > epoch) {
                visible++;
            }
        }
        return visible;
    }

    /**
     * Whether any visible segment holds this term.
     *
     * <p>An exact answer, from the same dictionaries the search reads. It is what lets a query
     * expander's invented vocabulary be dropped before it is searched for: a term no document
     * contains cannot change a ranking, but it can make a caller believe a formulation was tried
     * on its merits when nothing in the corpus could ever have matched it.</p>
     */
    public boolean knowsTerm(String term, long epoch) throws IOException {
        Objects.requireNonNull(term, "term must not be null");
        for (SegmentManifest.Entry entry : readable(manifest.visibleAt(epoch))) {
            if (openSegment(entry.name()).termId(term) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * A digest of everything a search at this epoch reads, which moves exactly when its answer
     * can.
     *
     * <p>Taken over the same list {@link #search} and {@link #knowsTerm} read - the segments
     * visible at the epoch, less any this store could not open - and over each entry's name,
     * publication epoch, document count and tombstone count. Each of those is what one kind of
     * change moves: a publication becoming visible adds an entry once the epoch crosses it,
     * which is the change no write is observed making at the moment it takes effect; an
     * erasure raises a tombstone count; a compaction replaces names. The collection statistics
     * BM25 scores against are sums over the same entries, so they cannot change while this
     * stands still.</p>
     *
     * <p>Cheap: one pass over the manifest, which compaction keeps short, with no file access.
     * The caller holds whatever guards the manifest against a concurrent writer, as it does
     * for a search.</p>
     *
     * @param epoch The epoch a search would read at
     * @return The digest
     */
    public long searchVersion(long epoch) {
        List<SegmentManifest.Entry> visible = readable(manifest.visibleAt(epoch));
        long digest = fold(0x9E3779B97F4A7C15L, visible.size());
        // In publication order, which the manifest keeps stable across compaction, so two
        // equal manifests always digest the same way.
        for (SegmentManifest.Entry entry : visible) {
            digest = fold(digest, XxHash64.hash(entry.name()));
            digest = fold(digest, entry.epoch());
            digest = fold(digest, entry.docCount());
            digest = fold(digest, entry.tombstoned());
        }
        return digest;
    }

    /**
     * Mixes one value into a digest with a 64-bit avalanche step, so two manifests differing in
     * one low bit of one count digest differently everywhere rather than in that bit.
     */
    private static long fold(long digest, long value) {
        long mixed = digest * 0x100000001B3L ^ value;
        mixed ^= mixed >>> 33;
        mixed *= 0xFF51AFD7ED558CCDL;
        mixed ^= mixed >>> 33;
        mixed *= 0xC4CEB9FE1A85EC53L;
        return mixed ^ (mixed >>> 33);
    }

    /**
     * Gathers the statistics every visible segment must be scored against.
     *
     * <p>Summed across segments rather than taken from any one of them. A term's rarity and a
     * document's relative length are properties of the collection, and scoring each segment
     * against its own made a document alone in a segment average by definition - so the ranking
     * followed which segment a document landed in rather than how well it matched.</p>
     */
    private PostingsIndex.Stats collectionStats(List<SegmentManifest.Entry> visible, String[] terms)
            throws IOException {
        int documents = 0;
        double tokens = 0;
        Map<String, Integer> frequencies = new LinkedHashMap<>();
        for (SegmentManifest.Entry entry : visible) {
            PostingsIndex index = openSegment(entry.name());
            documents += index.docCount();
            tokens += index.averageDocumentLength() * index.docCount();
            for (String term : terms) {
                int termId = index.termId(term);
                if (termId >= 0) {
                    frequencies.merge(term, index.documentFrequency(termId), Integer::sum);
                }
            }
        }
        double average = documents == 0 ? 1.0 : tokens / documents;
        return new PostingsIndex.Stats(documents, average,
            term -> frequencies.getOrDefault(term, 1));
    }

    /**
     * Records an obligation to erase documents, and hides them immediately.
     *
     * <p>The log entry is forced to disk before this returns. A caller told the request was
     * accepted and then losing it to a restart is the one outcome erasure must not have, because
     * nothing afterwards would notice the data was still there.</p>
     *
     * @param documentKeys Keys to erase
     * @return How many were found and tombstoned
     */
    public int erase(Set<String> documentKeys) throws IOException {
        Objects.requireNonNull(documentKeys, "documentKeys must not be null");
        if (documentKeys.isEmpty()) {
            return 0;
        }
        for (String key : documentKeys) {
            DocumentKeys.require(key);
        }
        erasureLog.append(documentKeys, clock.instant());
        // After the obligation is durable, so a crash while the segment goes still owes the
        // erasure, and the next open removes what is left of it for the same reason.
        removeUnrecoverable("an erasure of " + documentKeys.size() + " name(s) was recorded");

        int erased = markErased(documentKeys);
        if (erased > 0) {
            manifest.write(directory);
        }
        // Only now: the tombstones and the manifest counting them are on the device, so a reopen
        // has nothing left to replay for these keys - and must not, because by then the key may
        // name a document written after this erasure.
        erasureLog.confirmApplied(documentKeys);
        LOGGER.info("Erasure recorded for {} key(s), {} document(s) tombstoned",
            documentKeys.size(), erased);
        return erased;
    }

    /**
     * Tombstones every document carrying one of these keys, in every segment.
     *
     * <p>Does not write the manifest: the caller decides when, so a batch of marks costs one
     * manifest replacement rather than one per segment.</p>
     *
     * @return How many documents were marked that were not already
     */
    private int markErased(Set<String> documentKeys) throws IOException {
        int erased = 0;
        for (SegmentManifest.Entry entry : manifest.entries()) {
            SegmentKeys segmentKeys = openKeys(entry.name());
            boolean[] marks = loadTombstones(entry.name(), entry.docCount());
            Retired retired = new Retired(marks);
            // An erased copy is gone for every reader at once, including one that could not yet
            // see the copy replacing it: erasure is retroactive, supersession is not.
            Map<Integer, Long> replaced = replacedAt.get(entry.name());
            IntConsumer erase = replaced == null ? retired : doc -> {
                replaced.remove(doc);
                retired.accept(doc);
            };
            for (String key : documentKeys) {
                segmentKeys.find(key.getBytes(StandardCharsets.UTF_8), erase);
            }
            if (retired.count() > 0) {
                writeTombstones(entry.name(), marks);
                manifest.replace(entry.name(), new SegmentManifest.Entry(entry.name(),
                    entry.epoch(), entry.docCount(), countMarks(marks)));
                erased += retired.count();
            }
        }
        return erased;
    }

    /**
     * Makes the tombstones agree with the erasure log.
     *
     * <p>The log is forced to disk before an erasure returns; the tombstone files and the manifest
     * beside it are written by atomic rename, which orders them but does not force them. A crash
     * in that window leaves the store holding a promise it has no record of having applied - and
     * worse, the next compaction would discharge the log entry on the grounds that it names
     * nothing tombstoned, forgetting the promise while the document is still readable.</p>
     *
     * <p>So the log is the authority on open, and any promise it has no confirmation of having
     * applied is marked again. Only those: a promise that was applied stays on the log until
     * compaction removes the document, and marking it again would tombstone whatever has been
     * written under the same key since. Free when nothing is unconfirmed, which is the usual
     * case.</p>
     */
    private void reapplyErasures() throws IOException {
        Set<String> unapplied = erasureLog.unappliedKeys();
        if (unapplied.isEmpty()) {
            return;
        }
        int restored = markErased(unapplied);
        if (restored > 0) {
            manifest.write(directory);
            LOGGER.warn("Restored {} tombstone(s) that the erasure log still owed but the store "
                + "had lost; an interrupted write made {} erased key(s) readable again",
                restored, unapplied.size());
        }
        erasureLog.confirmApplied(unapplied);
    }

    /**
     * How many keys this store has dropped since it opened because nothing on disk can bring
     * them back.
     *
     * @return The count; see {@link UnrecoverableTextException} and the damaged segments an owed
     *         erasure removes
     */
    public long unreadableKeysDropped() {
        return unreadableKeysDropped.get();
    }

    /** Documents erased but not yet physically removed. */
    public int outstandingErasures() {
        return manifest.tombstonedDocuments();
    }

    /** Erasure promises still on the log, whether or not they matched anything. */
    public List<DocumentErasureLog.Record> outstandingErasureRequests() {
        return erasureLog.outstanding();
    }

    /** The numbers the compaction policy judges, for an operator or a metric. */
    public CompactionStats compactionStats() {
        return planner.stats(manifest.entries(), erasureLog.oldestOutstanding());
    }

    /**
     * What a compaction would do now, or nothing when the policy declines and no segment has to
     * be rebuilt or upgraded.
     *
     * @param floorEpoch The oldest epoch any live reader may still query at
     */
    public Optional<CompactionPlan> planCompaction(long floorEpoch) {
        return planner.plan(compactableEntries(floorEpoch), erasureLog.oldestOutstanding(),
            clock.instant(), floorEpoch, requiredRewrites());
    }

    /**
     * The segments a compaction planned now may rewrite.
     *
     * <p>Every segment, less those still holding a replaced copy some reader needs: one whose
     * replacement became visible after the reader floor. A rewrite drops every tombstoned
     * document, and dropping that copy would take away the only one a reader behind its
     * replacement can see - the gap {@link #replacedAt} exists to close. Such a segment waits
     * for the floor to pass, which is a reader timeout at most. Planning is also where those
     * exceptions are forgotten once the floor has passed them, since the floor is the caller's
     * promise that no reader is further behind.</p>
     *
     * <p>Never a segment that can be neither served nor rebuilt: see {@link #quarantine}. A
     * damaged segment that can be rebuilt is planned like any other.</p>
     *
     * @param floorEpoch The oldest epoch any live reader may still query at
     */
    private List<SegmentManifest.Entry> compactableEntries(long floorEpoch) {
        List<SegmentManifest.Entry> entries = new ArrayList<>(manifest.entries().size());
        for (SegmentManifest.Entry entry : manifest.entries()) {
            if (unrecoverable(entry.name())) {
                // Nothing can rewrite it: a rewrite reads its names and drops its tombstoned
                // documents, and one of the two is what cannot be trusted.
                continue;
            }
            Map<Integer, Long> replaced = replacedAt.get(entry.name());
            if (replaced != null) {
                replaced.values().removeIf(from -> from <= floorEpoch);
                if (!replaced.isEmpty()) {
                    continue;
                }
                replacedAt.remove(entry.name(), replaced);
            }
            entries.add(entry);
        }
        return entries;
    }

    /**
     * Compacts if the policy says it is due.
     *
     * <p>The call to make on a timer. Doing nothing is the common outcome and costs a comparison
     * of four numbers.</p>
     *
     * @param textOf     Supplies the text for a surviving key
     * @param floorEpoch The oldest epoch any live reader may still query at
     */
    public CompactionResult compactIfDue(Function<String, String> textOf, long floorEpoch)
            throws IOException {
        Optional<CompactionPlan> plan = planCompaction(floorEpoch);
        if (plan.isEmpty()) {
            CompactionStats stats = compactionStats();
            return CompactionResult.declined("not due: " + stats.tombstonedRecords() + " of "
                + stats.totalRecords() + " document(s) tombstoned across " + stats.segmentCount()
                + " segment(s)");
        }
        return compact(textOf, plan.get());
    }

    /**
     * Compacts everything the epoch floor allows, whatever the policy thinks.
     *
     * <p>For an operator discharging an erasure on demand rather than on a schedule.</p>
     *
     * @param textOf     Supplies the text for a surviving key
     * @param floorEpoch The oldest epoch any live reader may still query at
     */
    public CompactionResult compactNow(Function<String, String> textOf, long floorEpoch)
            throws IOException {
        return compact(textOf, forcedPlan(floorEpoch));
    }

    /**
     * What a compaction would rewrite if it ran regardless of whether the policy says it is due.
     *
     * <p>For a caller under a deadline that has to be able to say the data is gone, rather than
     * that its removal has been scheduled. Separate from {@link #compactNow} so the caller can
     * hold the store exclusively for the planning and the commit and not for the rewrite between
     * them.</p>
     *
     * @param floorEpoch The epoch below which segments may be merged with each other
     */
    public CompactionPlan forcedPlan(long floorEpoch) {
        return planner.forcedPlan(compactableEntries(floorEpoch), floorEpoch,
            requiredRewrites());
    }

    /**
     * What a compaction would rewrite if every segment were to be rewritten.
     *
     * <p>For an operator asking for the whole index to be rebuilt from the text the caller
     * supplies, which drops every key whose text is gone - see
     * {@link CompactionPlanner#fullPlan}.</p>
     *
     * @param floorEpoch The epoch below which segments may be merged with each other
     */
    public CompactionPlan fullPlan(long floorEpoch) {
        return planner.fullPlan(compactableEntries(floorEpoch), floorEpoch);
    }

    /**
     * Executes a plan.
     *
     * <p>The index stores postings, not text - that is the point of it, since the text already
     * exists once in the engine's own segments. So compaction is given the text of the documents
     * that survive, rather than pretending to recover it from an index that deliberately does not
     * hold it.</p>
     *
     * <p>A supplier returning null drops that document. The store cannot know whether a key it
     * holds still has anything behind it, and a document whose text is gone would otherwise be
     * rewritten as an empty one - present in every count, findable by nothing. A supplier that
     * throws {@link java.io.UncheckedIOException} is one that could not say, and the group it was
     * asked for is left as it is - see {@link #buildUnlessStopped}.</p>
     *
     * @param textOf Supplies the text for a surviving key, or null to drop it
     * @param plan   Which segments to rewrite
     */
    public CompactionResult compact(Function<String, String> textOf, CompactionPlan plan)
            throws IOException {
        return commit(prepare(textOf, plan));
    }

    /**
     * Builds the replacement segments without publishing any of them.
     *
     * <p>This is where a compaction spends its time, and it deliberately changes nothing a reader
     * can see: it reads segments that already exist and writes files the manifest does not name.
     * Separating it from {@link #commit} is what lets a caller hold this store open to queries
     * while a compaction runs, rather than stopping every query for the length of a rewrite.</p>
     *
     * <p>Each group is built through {@link SegmentedIndexBuilder} a document at a time, so the
     * memory a compaction needs is set by the builder's budget rather than by the size of what is
     * being compacted. Gathering the surviving text first would make the largest segment the store
     * could hold the largest one it could ever compact, which is the wrong way round.</p>
     *
     * @param textOf Supplies the text for a surviving key, or null to drop it
     * @param plan   Which segments to rewrite
     */
    public PreparedCompaction prepare(Function<String, String> textOf, CompactionPlan plan)
            throws IOException {
        return build(textOf, snapshot(plan));
    }

    /**
     * Copies out everything a plan's rewrites will read.
     *
     * <p>Cheap, and the only part of a compaction that has to see a consistent store. A caller
     * serialising access holds it exclusively for this and for {@link #commit}, and not for
     * {@link #build} in between - which is the whole of the time a compaction takes.</p>
     */
    public CompactionSnapshot snapshot(CompactionPlan plan) throws IOException {
        Objects.requireNonNull(plan, "plan must not be null");
        List<GroupSnapshot> groups = new ArrayList<>(plan.groups().size());
        for (List<String> group : plan.groups()) {
            List<SegmentManifest.Entry> entries = entriesNamed(group);
            List<SegmentKeys> keys = new ArrayList<>(entries.size());
            List<boolean[]> marks = new ArrayList<>(entries.size());
            for (SegmentManifest.Entry entry : entries) {
                keys.add(openKeys(entry.name()));
                // Cloned: erase() sets marks on the store's own array in place, and a rewrite
                // reading it as it changed would drop documents the manifest still counts.
                marks.add(loadTombstones(entry.name(), entry.docCount()).clone());
            }
            groups.add(new GroupSnapshot(entries, keys, marks));
        }
        return new CompactionSnapshot(groups, plan.reason());
    }

    /**
     * Builds the replacement segments from a snapshot, touching nothing shared.
     *
     * @param textOf   Supplies the text for a surviving key, or null to drop it; see
     *                 {@link #buildUnlessStopped} for a supplier that cannot tell
     * @param snapshot What {@link #snapshot} copied out
     */
    public PreparedCompaction build(Function<String, String> textOf, CompactionSnapshot snapshot)
            throws IOException {
        return buildUnlessStopped(textOf, snapshot, () -> false).orElseThrow();
    }

    /**
     * Builds the replacement segments from a snapshot, unless asked to stop first.
     *
     * <h4>Stopping</h4>
     * <p>{@code stopRequested} is read before each group and before each document, and once it
     * answers true the build ends: every replacement already built is deleted, nothing is
     * published and the manifest is not touched, so the store is exactly what it was. The caller
     * that needs this is one about to take away what {@code textOf} reads - a shutdown closing
     * the storage engine - or an operator cancelling a rewrite of the whole index. A rewrite has
     * nothing to salvage half-way: its output replaces its inputs as a group or not at all.</p>
     *
     * <h4>A supplier that cannot tell</h4>
     * <p>Null from {@code textOf} means the key's text is gone, and the rewrite drops the key. A
     * read that failed is not that answer, and a supplier signals it by throwing
     * {@link java.io.UncheckedIOException}. The group being built is then abandoned - its files
     * deleted, its segments left in the manifest as they are - and the build goes on with the
     * next group. Treating the failure as absence would drop the key's postings for good from a
     * segment whose text storage still holds; abandoning costs only this rewrite, and the next
     * compaction tries the group again. A supplier that knows the failure is permanent - the
     * stored entry is damaged and will fail the same way every time - throws
     * {@link UnrecoverableTextException} instead, and the key is dropped as if its text were
     * gone, logged at error and counted in {@link #unreadableKeysDropped()}: abandoning would
     * abandon the group on every attempt, and an erasure recorded in it could never be
     * discharged. Any other exception fails the whole build, after the replacements built so far
     * are deleted.</p>
     *
     * @param textOf        Supplies the text for a surviving key, null to drop it, throws
     *                      {@link java.io.UncheckedIOException} when it cannot say, or throws
     *                      {@link UnrecoverableTextException} when the text can never be read
     * @param snapshot      What {@link #snapshot} copied out
     * @param stopRequested Answers true once the build should end without publishing anything
     * @return What was built, or empty when the build was stopped
     */
    public Optional<PreparedCompaction> buildUnlessStopped(Function<String, String> textOf,
                                                           CompactionSnapshot snapshot,
                                                           BooleanSupplier stopRequested)
            throws IOException {
        Objects.requireNonNull(textOf, "textOf must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(stopRequested, "stopRequested must not be null");
        List<PreparedGroup> prepared = new ArrayList<>(snapshot.groups().size());
        int abandoned = 0;
        try {
            for (GroupSnapshot group : snapshot.groups()) {
                GroupBuild built = stopRequested.getAsBoolean()
                    ? GroupBuild.STOPPED
                    : buildGroup(textOf, group, stopRequested);
                switch (built.fate()) {
                    case BUILT -> prepared.add(built.group());
                    case ABANDONED -> abandoned++;
                    case STOPPED -> {
                        discardAll(prepared);
                        LOGGER.info("Compaction stopped on request after building {} of {} "
                            + "group(s); nothing was published ({})", prepared.size(),
                            snapshot.groups().size(), snapshot.reason());
                        return Optional.empty();
                    }
                }
            }
        } catch (IOException | RuntimeException | Error e) {
            discardAll(prepared, e);
            throw e;
        }
        if (abandoned > 0) {
            LOGGER.warn("Abandoned {} of {} compaction group(s) whose text storage could not "
                + "read back; their segments stay as they are until the next compaction",
                abandoned, snapshot.groups().size());
        }
        return Optional.of(new PreparedCompaction(prepared, abandoned, snapshot.reason()));
    }

    /** What became of one group's rewrite. */
    private enum Fate {
        /** Its replacement is built and waiting to be published. */
        BUILT,
        /** A key's text could not be read, so the group is left as it is. */
        ABANDONED,
        /** The build was asked to stop. */
        STOPPED
    }

    /**
     * One group's rewrite, and what came of it.
     *
     * @param fate  How it ended
     * @param group The replacement, when it was built; null otherwise
     */
    private record GroupBuild(Fate fate, PreparedGroup group) {
        static final GroupBuild ABANDONED = new GroupBuild(Fate.ABANDONED, null);
        static final GroupBuild STOPPED = new GroupBuild(Fate.STOPPED, null);
    }

    /** Deletes the replacements a build that will publish nothing had already written. */
    private void discardAll(List<PreparedGroup> prepared) throws IOException {
        for (PreparedGroup group : prepared) {
            discardPrepared(group);
        }
    }

    /** As {@link #discardAll(List)}, attaching a failure to clean up to the one propagating. */
    private void discardAll(List<PreparedGroup> prepared, Throwable failure) {
        for (PreparedGroup group : prepared) {
            try {
                discardPrepared(group);
            } catch (IOException | RuntimeException e) {
                failure.addSuppressed(e);
            }
        }
    }

    private GroupBuild buildGroup(Function<String, String> textOf, GroupSnapshot group,
                                  BooleanSupplier stopRequested) throws IOException {
        List<SegmentManifest.Entry> entries = group.entries();
        long epoch = 0;
        int before = 0;
        for (SegmentManifest.Entry entry : entries) {
            // The highest epoch of the group, so nothing in the new segment claims to have been
            // visible before it was. Readers below the floor are what a merge cannot serve, and
            // the floor is the caller's promise that there are none.
            epoch = Math.max(epoch, entry.epoch());
            before += entry.docCount();
        }

        String name = SEGMENT_PREFIX + nextSegment.getAndIncrement();
        Path scratch = directory.resolve(SCRATCH_PREFIX + name);
        int survivors = 0;
        Fate fate = Fate.BUILT;
        try (SegmentedIndexBuilder builder = new SegmentedIndexBuilder(scratch, bm25);
             SegmentKeys.Writer names = new SegmentKeys.Writer(directory, name)) {
            fill:
            for (int at = 0; at < entries.size(); at++) {
                SegmentKeys segmentKeys = group.keys().get(at);
                boolean[] erased = group.tombstoned().get(at);
                for (int doc = 0; doc < segmentKeys.count(); doc++) {
                    if (erased[doc]) {
                        continue;
                    }
                    if (stopRequested.getAsBoolean()) {
                        fate = Fate.STOPPED;
                        break fill;
                    }
                    String key = segmentKeys.at(doc);
                    String text;
                    try {
                        text = textOf.apply(key);
                    } catch (UnrecoverableTextException e) {
                        // Permanent, so abandoning would abandon this group on every attempt and
                        // hold every erasure recorded in it hostage to a chunk it does not name.
                        unreadableKeysDropped.incrementAndGet();
                        LOGGER.error("Dropping {} from the lexical index while rewriting {}: its "
                            + "stored text is permanently unreadable, so it can no longer be "
                            + "matched. Restore the entry and reindex its document to bring it "
                            + "back", key, names(entries), e);
                        continue;
                    } catch (java.io.UncheckedIOException e) {
                        LOGGER.warn("Could not read the text of {} back while rewriting {}; the "
                            + "group is left as it is: {}", key, names(entries), e.getMessage());
                        fate = Fate.ABANDONED;
                        break fill;
                    }
                    if (text == null) {
                        continue;
                    }
                    builder.add(text);
                    names.add(key);
                }
            }
            if (fate == Fate.BUILT) {
                names.finish();
                survivors = names.count();
                if (survivors > 0) {
                    builder.build(directory.resolve(name));
                }
            }
        } catch (IOException | RuntimeException | Error e) {
            // After the writers have closed, which is why this is here rather than inside: the
            // key table's close forces and maps the files it wrote.
            try {
                discardUnpublished(name);
            } catch (IOException | RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        if (fate != Fate.BUILT) {
            discardUnpublished(name);
            return fate == Fate.STOPPED ? GroupBuild.STOPPED : GroupBuild.ABANDONED;
        }
        return new GroupBuild(Fate.BUILT,
            new PreparedGroup(entries, name, epoch, survivors, before));
    }

    /** The names of some manifest entries, for a log line. */
    private static List<String> names(List<SegmentManifest.Entry> entries) {
        List<String> names = new ArrayList<>(entries.size());
        for (SegmentManifest.Entry entry : entries) {
            names.add(entry.name());
        }
        return names;
    }

    /**
     * Publishes what {@link #prepare} built, in one manifest replacement.
     *
     * <p>Fast by construction: every byte was written already, so this is a rename and some
     * deletions. That is the only part of a compaction a reader has to be kept out of.</p>
     *
     * <p>A group whose inputs changed while it was being built is discarded rather than published.
     * The segments it was rewriting were tombstoned or replaced in the meantime, and publishing a
     * replacement built from the older versions would undo that - an erasure recorded during the
     * rewrite would silently come back. The next compaction rebuilds it from what is there now.</p>
     *
     * <p>A compaction that planned work and published none of it - every group abandoned or
     * discarded - is reported as not performed, with the abandoned count and the reason; one that
     * published some of its plan and abandoned the rest is performed and
     * {@link CompactionResult#partial() partial}. Only a plan with nothing in it is performed
     * with nothing rewritten, because that is the truthful "ran and found nothing to do".</p>
     */
    public CompactionResult commit(PreparedCompaction prepared) throws IOException {
        Objects.requireNonNull(prepared, "prepared must not be null");
        int removed = 0;
        int rewritten = 0;
        int produced = 0;
        int discarded = 0;
        int rebuilt = 0;
        int upgraded = 0;
        List<String> retired = new ArrayList<>();
        boolean changed = false;
        for (PreparedGroup group : prepared.groups()) {
            if (!group.replaced().equals(currentEntriesOf(group.replaced()))) {
                discardPrepared(group);
                discarded++;
                continue;
            }
            List<String> names = new ArrayList<>(group.replaced().size());
            for (SegmentManifest.Entry entry : group.replaced()) {
                names.add(entry.name());
            }
            if (group.survivors() == 0) {
                // Everything in the group was erased. An empty segment would be searched, mapped
                // and counted forever to represent nothing.
                SegmentKeys.delete(directory, group.name());
                manifest.removeAll(names);
            } else {
                manifest.replaceGroup(names, new SegmentManifest.Entry(
                    group.name(), group.epoch(), group.survivors(), 0));
                produced++;
            }
            retired.addAll(names);
            for (String name : names) {
                if (unreadable.containsKey(name)) {
                    rebuilt++;
                } else if (outdated.contains(name)) {
                    upgraded++;
                }
            }
            rewritten += names.size();
            removed += group.before() - group.survivors();
            changed = true;
        }
        if (changed) {
            manifest.write(directory);
        }

        // Only after the new manifest is in place: until then the old segments are what a reader
        // is using, and deleting them first would unmap the store out from under one. A damaged
        // segment's bytes go too: its replacement was built from the same names and the text
        // behind them, so they hold nothing the store still needs.
        for (String old : retired) {
            deleteSegment(old);
            unreadable.remove(old);
            rebuildable.remove(old);
            outdated.remove(old);
        }
        settleErasureLog();
        if (discarded > 0) {
            LOGGER.info("Discarded {} prepared group(s) whose segments changed while they were "
                + "being rewritten; the next compaction will redo them", discarded);
        }
        int abandoned = prepared.abandoned();
        if (!changed && (abandoned > 0 || discarded > 0)) {
            // Work was planned and none of it reached the manifest. Reporting that as a
            // compaction that ran would tell an erasure's caller its chunks had left disk, and
            // an operator's forced rewrite that it had succeeded, when every segment is exactly
            // as it was.
            String why = "nothing published: " + abandoned + " group(s) abandoned because stored "
                + "text could not be read back, " + discarded + " discarded because their "
                + "segments changed during the rewrite (" + prepared.reason() + ")";
            LOGGER.warn("Compaction published nothing; {}", why);
            return new CompactionResult(false, 0, 0, 0, abandoned, why);
        }
        LOGGER.info("Compacted {} segment(s) into {}, physically removing {} document(s){}{}{} "
            + "({})", rewritten, produced, removed,
            rebuilt > 0 ? "; rebuilt " + rebuilt + " damaged segment(s)" : "",
            upgraded > 0 ? "; upgraded " + upgraded + " segment(s) from an older format" : "",
            abandoned > 0 ? "; " + abandoned + " group(s) abandoned and left as they were" : "",
            prepared.reason());
        return new CompactionResult(true, rewritten, produced, removed, abandoned, rebuilt,
            upgraded, abandoned > 0
                ? "partial: " + abandoned + " group(s) abandoned because stored text could not "
                    + "be read back (" + prepared.reason() + ")"
                : prepared.reason());
    }

    /** Removes files a prepared group built that will never be published. */
    private void discardPrepared(PreparedGroup group) throws IOException {
        discardUnpublished(group.name());
    }

    /**
     * Removes whatever a rewrite wrote under a segment name no manifest names.
     *
     * <p>Idempotent, so it serves a group abandoned half-way - whose key table may still be open
     * and whose postings were never built - as well as one built whole. Its scratch directory is
     * the builder's to remove, and the builder does on close.</p>
     */
    private void discardUnpublished(String name) throws IOException {
        SegmentKeys.delete(directory, name);
        deleteRecursively(directory.resolve(name));
    }

    /** The manifest's current record of the named segments, or null if any is gone. */
    private List<SegmentManifest.Entry> currentEntriesOf(List<SegmentManifest.Entry> replaced) {
        List<SegmentManifest.Entry> current = new ArrayList<>(replaced.size());
        for (SegmentManifest.Entry wanted : replaced) {
            SegmentManifest.Entry found = null;
            for (SegmentManifest.Entry entry : manifest.entries()) {
                if (entry.name().equals(wanted.name())) {
                    found = entry;
                    break;
                }
            }
            if (found == null) {
                return null;
            }
            current.add(found);
        }
        return current;
    }

    /**
     * Discharges the promises this compaction kept.
     *
     * <p>What remains outstanding is what is still tombstoned somewhere. A key that named nothing
     * in the store is discharged too: left on the log it would age past the latency bound forever
     * and demand a compaction that could never settle it.</p>
     */
    private void settleErasureLog() throws IOException {
        Set<String> owed = new HashSet<>();
        for (SegmentManifest.Entry entry : manifest.entries()) {
            // An unrecoverable segment has nothing to show: its names or its marks cannot be
            // read. It is only ever here while nothing is owed, because an owed erasure removes
            // it before anything is settled.
            if (entry.tombstoned() == 0 || unrecoverable(entry.name())) {
                continue;
            }
            SegmentKeys segmentKeys = openKeys(entry.name());
            boolean[] erased = loadTombstones(entry.name(), entry.docCount());
            for (int doc = 0; doc < segmentKeys.count(); doc++) {
                if (erased[doc]) {
                    owed.add(segmentKeys.at(doc));
                }
            }
        }
        erasureLog.retain(owed);
    }

    private List<SegmentManifest.Entry> entriesNamed(List<String> group) {
        List<SegmentManifest.Entry> entries = new ArrayList<>(group.size());
        for (SegmentManifest.Entry entry : manifest.entries()) {
            if (group.contains(entry.name())) {
                entries.add(entry);
            }
        }
        if (entries.size() != group.size()) {
            throw new IllegalArgumentException("Plan names segments the store does not have: "
                + group + ", store has " + manifest.names());
        }
        return entries;
    }

    /**
     * Opens every segment now, so a later search does not have to.
     *
     * <p>Search populates its segment handles lazily, which means the first query touching a
     * segment mutates state the others are reading. Doing it here instead lets a caller hold this
     * store behind a read-write lock and serve concurrent queries under the read half - the
     * alternative being to serialise every query behind the one that happened to be first.</p>
     */
    public void warm() throws IOException {
        for (SegmentManifest.Entry entry : readable(manifest.entries())) {
            openSegment(entry.name());
        }
    }

    /** Segments currently in the store. */
    public List<SegmentManifest.Entry> segments() {
        return manifest.entries();
    }

    /**
     * Documents a query at an epoch can match.
     *
     * <p>Not those in a segment this store could not open: they are still held, and still on
     * disk, but counting them here would report a corpus that answers for documents no query can
     * find. {@link #unsearchableDocuments(long)} counts them instead.</p>
     */
    public int liveDocuments(long epoch) {
        int total = 0;
        for (SegmentManifest.Entry entry : readable(manifest.visibleAt(epoch))) {
            total += entry.liveCount() + stillVisible(replacedAt.get(entry.name()), epoch);
        }
        return total;
    }

    /**
     * Documents visible at an epoch that no query can match, because the segment holding them
     * could not be opened.
     */
    public int unsearchableDocuments(long epoch) {
        if (unreadable.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (SegmentManifest.Entry entry : manifest.visibleAt(epoch)) {
            if (unreadable.containsKey(entry.name())) {
                total += entry.liveCount();
            }
        }
        return total;
    }

    /**
     * Deletes anything on disk the manifest does not name.
     *
     * <p>A segment is written before it is published, so a crash in between leaves files that no
     * reader will ever consult. The manifest is the sole authority on what the store contains, so
     * what it does not name is not part of the store - and left alone these would accumulate as
     * dead weight nothing ever accounts for.</p>
     *
     * <p>That authority is only as good as the file. On a manifest that cannot be shown to be
     * all of itself, "the manifest does not name it" and "the line that named it is no longer
     * there" are indistinguishable, and this method is the step that would turn the second into
     * a deletion of the whole index. So an unverified manifest sweeps nothing: the cost is some
     * dead weight until the next write republishes the manifest in a form that can be checked.
     * The scratch directories are swept either way - no manifest ever names one, so nothing
     * about the manifest's state makes them anything but leftovers.</p>
     */
    private void removeUnreferenced() throws IOException {
        Set<String> known = new HashSet<>(manifest.names());
        boolean sweepSegments = manifest.verified();
        List<Path> orphans = new ArrayList<>();
        try (Stream<Path> listing = Files.list(directory)) {
            for (Path path : listing.toList()) {
                String fileName = path.getFileName().toString();
                if (fileName.startsWith(SCRATCH_PREFIX)) {
                    orphans.add(path);
                } else if (sweepSegments && fileName.startsWith(SEGMENT_PREFIX)
                        && !known.contains(baseName(fileName))) {
                    orphans.add(path);
                }
            }
        }
        for (Path orphan : orphans) {
            deleteRecursively(orphan);
        }
        if (!orphans.isEmpty()) {
            LOGGER.info("Removed {} unreferenced file(s) left by an interrupted write",
                orphans.size());
        }
    }

    private static String baseName(String fileName) {
        int dot = fileName.indexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private PostingsIndex openSegment(String name) throws IOException {
        PostingsIndex index = open.get(name);
        if (index == null) {
            index = PostingsIndex.open(directory.resolve(name), bm25);
            open.put(name, index);
            openKeys(name);
            for (SegmentManifest.Entry entry : manifest.entries()) {
                if (entry.name().equals(name)) {
                    loadTombstones(name, entry.docCount());
                }
            }
        }
        return index;
    }

    private SegmentKeys openKeys(String name) throws IOException {
        SegmentKeys cached = keys.get(name);
        if (cached == null) {
            cached = SegmentKeys.open(directory, name);
            keys.put(name, cached);
        }
        return cached;
    }

    /**
     * A segment's marks, read once and then kept.
     *
     * <p>Refused rather than read leniently when the file does not match its checksum: every
     * segment's marks were verified when the store opened, so this fails only for a file damaged
     * since, and failing the operation that needed it is the one outcome that cannot make an
     * erased document readable.</p>
     */
    private boolean[] loadTombstones(String name, int docCount) throws IOException {
        boolean[] cached = tombstones.get(name);
        if (cached != null) {
            return cached;
        }
        boolean[] marks = TombstoneFile.read(TombstoneFile.of(directory, name), docCount).marks();
        tombstones.put(name, marks);
        return marks;
    }

    /**
     * Replaces a segment's marks with these, at the current version.
     *
     * <p>A manifest an older build could read is rewritten first. That build reads a tombstone
     * file as marks from its first byte, so it would take this file's header for marks and its
     * marks for the wrong documents; it refuses the current manifest outright, so no store it
     * opens can hold tombstones it would misread.</p>
     */
    private void writeTombstones(String name, boolean[] marks) throws IOException {
        if (manifest.version() < SegmentManifest.VERSION) {
            manifest.write(directory);
        }
        // Forced, not merely renamed: an erasure is confirmed as applied once its tombstones are
        // written, and a confirmation resting on bytes still in the page cache is one a power cut
        // turns into a document readable again with nothing left to say it was owed.
        Durability.FILE_SYSTEM.replace(TombstoneFile.of(directory, name),
            TombstoneFile.encode(marks));
        tombstones.put(name, marks);
    }

    private void deleteSegment(String name) throws IOException {
        PostingsIndex index = open.remove(name);
        if (index != null) {
            index.close();
        }
        SegmentKeys names = keys.remove(name);
        if (names != null) {
            names.close();
        }
        tombstones.remove(name);
        replacedAt.remove(name);
        deleteRecursively(directory.resolve(name));
        SegmentKeys.delete(directory, name);
        Path marks = TombstoneFile.of(directory, name);
        Files.deleteIfExists(marks);
        Files.deleteIfExists(marks.resolveSibling(marks.getFileName()
            + Durability.REPLACEMENT_SUFFIX));
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> files = Files.list(path)) {
                for (Path file : files.toList()) {
                    deleteRecursively(file);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private void closeAll() {
        for (PostingsIndex index : open.values()) {
            index.close();
        }
        open.clear();
        for (SegmentKeys names : keys.values()) {
            names.close();
        }
        keys.clear();
    }

    @Override
    public void close() {
        closeAll();
    }
}
