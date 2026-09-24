package com.eonmux.excalibur.ingest.graph;

import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.erasure.ErasureRequest;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.erasure.ScopedStagedErasure;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Withdraws what was staged from a replaced or removed document, and keeps owing a withdrawal
 * that could not be made until it has been.
 *
 * <h2>Why a failed withdrawal has to be remembered</h2>
 * <p>A withdrawal is asked for exactly once: when a document is replaced, or when a re-crawl
 * finds its source no longer lists it. Nothing asks again. A removed document has no registry
 * entry left for an erasure request to resolve, and an unchanged document is never reported
 * again - so a withdrawal that failed once would leave what the document used to say staged for
 * every later build to publish, for as long as the namespace exists. So a failed withdrawal is
 * recorded, durably, in {@value #LOG_FILE} beside the staging logs it concerns, and retried: when
 * the namespace is next opened, by the sweep over the resident namespaces, before every build, and
 * whenever the same document is reported again.</p>
 *
 * <h2>Why not a graph erasure obligation</h2>
 * <p>An erasure obligation is a promise to a caller that data will stop existing, and it cannot be
 * taken back: whatever carries the document's source id when the obligation is discharged goes,
 * including the facts of a version published after the obligation was recorded. That is right for
 * a document someone asked to have erased and wrong for one that was merely replaced, or that a
 * source stopped listing and then listed again. A withdrawal owed here is settled the moment any
 * withdrawal of the same document succeeds, and a newer version is never staged while one is
 * owed - see {@link #stage} - so settling it can only ever remove what the corpus no longer
 * holds. The file format is the erasure log's, reused rather than repeated, because what it has to
 * guarantee is the same: an obligation that survives power loss, and a torn tail that costs only
 * the record being written.</p>
 *
 * <h2>Ordering against the document's own reports</h2>
 * <p>Everything this does to one document happens under that document's lock, a stripe of
 * {@link #STRIPES}, so a retry cannot run between a report's withdrawal and the staging that
 * follows it: a retry landing there would take the new version's facts with the old ones.</p>
 *
 * <h2>When the record itself cannot be written</h2>
 * <p>The withdrawal is still owed, in memory, and retried like any other; only a restart before it
 * succeeds loses it, and that is logged at ERROR naming the document. The same holds when a
 * settled withdrawal's completion cannot be forced: until it is, the withdrawal is treated as
 * owed, because a completion lost to a power cut would otherwise replay the withdrawal over
 * whatever the document has staged since.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use.</p>
 *
 * @author ExcaliburDB
 */
public final class StagedWithdrawals implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(StagedWithdrawals.class);

    /** File within a staging directory recording the withdrawals still owed. */
    public static final String LOG_FILE = "withdrawals.log";

    /**
     * How many locks the documents are spread over.
     *
     * <p>Striped rather than one per document so the set is bounded by nothing but this number,
     * and rather than one for the store so a withdrawal of one document does not hold up the
     * staging of every other. Two documents sharing a stripe only wait for each other.</p>
     */
    static final int STRIPES = 64;

    private final GraphStagingStore staging;
    private final ErasureLog log;
    private final Clock clock;
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];

    /**
     * Withdrawals owed that the log does not reliably hold: its record could not be written, or
     * the completion that settled it could not be forced. Every withdrawal of a store with no
     * log is owed here.
     */
    private final Set<ErasureScope> owedInMemory = ConcurrentHashMap.newKeySet();

    /**
     * Withdrawals over a store, recorded in a log the caller opened.
     *
     * @param staging The store withdrawals are made from
     * @param log     Where owed withdrawals are recorded, or null for nowhere
     * @param clock   What an owed withdrawal is timestamped with
     */
    StagedWithdrawals(GraphStagingStore staging, ErasureLog log, Clock clock) {
        this.staging = Objects.requireNonNull(staging, "staging must not be null");
        this.log = log;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /**
     * Opens the withdrawals owed against a staging store, recorded in a file that outlives the
     * process.
     *
     * @param staging The store withdrawals are made from
     * @param logFile Where owed withdrawals are recorded; created if absent
     * @return The withdrawals, owing whatever was owed when the file was last written
     * @throws IOException if the file cannot be opened or is not a log this build can read
     */
    public static StagedWithdrawals open(GraphStagingStore staging, Path logFile)
            throws IOException {
        Objects.requireNonNull(logFile, "logFile must not be null");
        return new StagedWithdrawals(staging, ErasureLog.open(logFile), Clock.systemUTC());
    }

    /**
     * Withdrawals against a staging store with nowhere to record what is owed.
     *
     * <p>For a sink that stages into a store it does not own - a bulk import, a test. What it
     * owes is retried for as long as this object lives, and not after.</p>
     *
     * @param staging The store withdrawals are made from
     * @return The withdrawals, owing nothing
     */
    public static StagedWithdrawals inMemory(GraphStagingStore staging) {
        return new StagedWithdrawals(staging, null, Clock.systemUTC());
    }

    /**
     * What withdrawing a document did.
     *
     * @param removed How many staged facts, snippets and labels were removed; zero when owed
     * @param owed    Whether the withdrawal is still owed: it failed, or it could not be settled.
     *                Nothing was staged after it
     */
    public record Result(long removed, boolean owed) {

        /** A withdrawal that did not happen and is owed. */
        static final Result OWED = new Result(0, true);
    }

    /**
     * The store withdrawals are made from.
     *
     * @return The staging store
     */
    public GraphStagingStore staging() {
        return staging;
    }

    /**
     * Withdraws everything staged from a document, then stages what replaces it.
     *
     * <p>{@code thenStage} runs only once the withdrawal has happened and is settled, under the
     * document's lock. When the withdrawal fails it is recorded as owed and {@code thenStage} does
     * not run at all: the owed withdrawal is by document, not by version, so what it staged would
     * be taken by the retry, and until then both versions would be published side by side.</p>
     *
     * @param documentKey The source-qualified document key
     * @param thenStage   What to stage once the document holds nothing staged, or null for
     *                    nothing
     * @return What the withdrawal did
     * @throws NullPointerException if documentKey is null
     */
    public Result withdraw(String documentKey, Runnable thenStage) {
        ErasureScope scope = scopeOf(documentKey);
        ReentrantLock lock = stripeOf(scope);
        lock.lock();
        try {
            return withdrawLocked(documentKey, scope, thenStage);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stages a document's facts, first making any withdrawal still owed for it.
     *
     * <p>For a document published with no version this node knows of before it - one whose
     * removal was never withdrawn, for instance, published again after its source listed it once
     * more. What was staged from the removed version is still owed a withdrawal, and staging the
     * new facts beside it would put them in reach of that withdrawal's retry.</p>
     *
     * @param documentKey The source-qualified document key
     * @param stage       What to stage
     * @return What a withdrawal owed did, or nothing removed and nothing owed when none was owed
     * @throws NullPointerException if either argument is null
     */
    public Result stage(String documentKey, Runnable stage) {
        Objects.requireNonNull(stage, "stage must not be null");
        ErasureScope scope = scopeOf(documentKey);
        ReentrantLock lock = stripeOf(scope);
        lock.lock();
        try {
            if (owes(scope)) {
                return withdrawLocked(documentKey, scope, stage);
            }
            stage.run();
            return new Result(0, false);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Makes every withdrawal still owed.
     *
     * <p>Never throws: a withdrawal that fails again stays owed for the next attempt, and one
     * document's staging refusing must not stop another's withdrawal.</p>
     *
     * @return How many withdrawals are still owed afterwards
     */
    public int retry() {
        Set<ErasureScope> owed = owedScopes();
        if (owed.isEmpty()) {
            return 0;
        }
        int settled = 0;
        for (ErasureScope scope : owed) {
            ReentrantLock lock = stripeOf(scope);
            lock.lock();
            try {
                // Settled by the document's own report since the snapshot: retrying now could
                // only remove what that report staged.
                if (owes(scope) && !withdrawLocked(null, scope, null).owed()) {
                    settled++;
                }
            } finally {
                lock.unlock();
            }
        }
        int left = owedCount();
        if (settled > 0) {
            LOGGER.info("made {} owed withdrawal(s) of staged facts from {}; {} still owed",
                settled, staging.directory(), left);
        }
        return left;
    }

    /**
     * Whether a withdrawal is owed for a document.
     *
     * @param documentKey The source-qualified document key
     * @return true while one is owed
     */
    public boolean owes(String documentKey) {
        return owes(scopeOf(documentKey));
    }

    /**
     * How many withdrawals are owed.
     *
     * @return The count
     */
    public int owedCount() {
        return owedScopes().size();
    }

    /**
     * Closes the record of what is owed; the staging store is not this object's to close.
     *
     * @throws IOException if the record cannot be forced as it closes
     */
    @Override
    public void close() throws IOException {
        if (log != null) {
            log.close();
        }
    }

    /**
     * Withdraws, settles and stages, with the document's lock held.
     *
     * @param documentKey The document, for the log lines; null on a retry, which knows only the
     *                    source id it records
     * @param scope       What the document's staged records carry
     * @param thenStage   What to stage afterwards, or null
     * @return What the withdrawal did
     */
    private Result withdrawLocked(String documentKey, ErasureScope scope, Runnable thenStage) {
        String what = documentKey != null ? documentKey : "the document recorded as " + scope;
        long removed;
        try {
            removed = staging.erase(ScopedStagedErasure.of(scope)).removed();
        } catch (IOException | RuntimeException e) {
            owe(what, scope, e);
            return Result.OWED;
        }
        if (!settle(what, scope)) {
            return Result.OWED;
        }
        if (removed > 0) {
            LOGGER.debug("withdrew {} staged record(s) of {}", removed, what);
        }
        if (thenStage != null) {
            thenStage.run();
        }
        return new Result(removed, false);
    }

    /**
     * Records a withdrawal that failed as owed.
     *
     * @param what  The document, for the log line
     * @param scope What its staged records carry
     * @param cause Why the withdrawal failed
     */
    private void owe(String what, ErasureScope scope, Exception cause) {
        if (owes(scope)) {
            LOGGER.warn("could not withdraw the facts staged from {}; the withdrawal stays owed "
                + "and is retried: {}", what, cause.toString());
            return;
        }
        if (log != null) {
            try {
                log.submit(scope, clock.instant());
                LOGGER.warn("could not withdraw the facts staged from {}; the withdrawal is "
                    + "recorded as owed and retried until it is made, and nothing newer is staged "
                    + "for the document meanwhile: {}", what, cause.toString());
                return;
            } catch (IOException | RuntimeException e) {
                cause.addSuppressed(e);
            }
        }
        owedInMemory.add(scope);
        if (log != null) {
            LOGGER.error("could not withdraw the facts staged from {}, nor record the withdrawal "
                + "as owed; it is retried while this process runs, and a restart before it is "
                + "made leaves those facts staged for every later build to publish: {}", what,
                cause.toString(), cause);
        } else {
            LOGGER.warn("could not withdraw the facts staged from {}; the withdrawal is retried "
                + "while this sink lives: {}", what, cause.toString());
        }
    }

    /**
     * Records that nothing is owed for a document any longer, once its withdrawal has been made.
     *
     * <p>Forced, not merely written: a completion lost to a power cut replays the withdrawal on
     * the next open, and by then whatever was staged for the document after it would go too.</p>
     *
     * @param what  The document, for the log line
     * @param scope What its staged records carry
     * @return true when nothing is owed for it; false when the completion could not be made
     *         durable, and the withdrawal is then still owed
     */
    private boolean settle(String what, ErasureScope scope) {
        if (log == null) {
            owedInMemory.remove(scope);
            return true;
        }
        List<Long> ids = new ArrayList<>();
        for (ErasureRequest request : log.outstanding()) {
            if (request.scope().equals(scope)) {
                ids.add(request.id());
            }
        }
        if (ids.isEmpty() && !owedInMemory.contains(scope)) {
            return true;
        }
        try {
            log.complete(ids, clock.instant());
            log.flush();
            owedInMemory.remove(scope);
            return true;
        } catch (IOException | RuntimeException e) {
            owedInMemory.add(scope);
            LOGGER.warn("withdrew the facts staged from {} but could not record that durably; the "
                + "withdrawal stays owed, and nothing newer is staged for the document until it "
                + "is: {}", what, e.toString());
            return false;
        }
    }

    /** Whether a withdrawal is owed for what a document's staged records carry. */
    private boolean owes(ErasureScope scope) {
        if (owedInMemory.contains(scope)) {
            return true;
        }
        if (log == null || log.outstandingCount() == 0) {
            return false;
        }
        for (ErasureRequest request : log.outstanding()) {
            if (request.scope().equals(scope)) {
                return true;
            }
        }
        return false;
    }

    /** Every withdrawal owed, from the log and from memory, once each. */
    private Set<ErasureScope> owedScopes() {
        Set<ErasureScope> owed = new LinkedHashSet<>();
        if (log != null) {
            for (ErasureRequest request : log.outstanding()) {
                owed.add(request.scope());
            }
        }
        owed.addAll(owedInMemory);
        return owed;
    }

    private ReentrantLock stripeOf(ErasureScope scope) {
        return stripes[Math.floorMod(scope.hashCode(), STRIPES)];
    }

    /**
     * What every fact and snippet staged from a document carries, whatever its version.
     *
     * @param documentKey The source-qualified document key
     * @return The scope a withdrawal of it erases
     */
    private static ErasureScope scopeOf(String documentKey) {
        Objects.requireNonNull(documentKey, "documentKey must not be null");
        return new ErasureScope.Source(SourceType.DOCUMENT,
            LlmRelationExtractor.sourceIdOf(documentKey));
    }

    @Override
    public String toString() {
        return "StagedWithdrawals[" + staging.directory() + ", " + owedCount() + " owed"
            + (log == null ? ", in memory" : "") + "]";
    }
}
