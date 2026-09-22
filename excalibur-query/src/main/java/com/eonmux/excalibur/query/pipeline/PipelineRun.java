package com.eonmux.excalibur.query.pipeline;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * One query's stages in flight: what they will produce, and the handle that can stop them.
 *
 * <p>The submitted task is kept alongside the future the caller waits on because the two stop
 * queries differently. Timing out a {@link CompletableFuture} settles the future and nothing
 * else: the thread behind it goes on searching segments for a caller who has already been
 * answered. Cancelling the task handle with an interrupt is what actually reaches the stage
 * that is running.</p>
 *
 * @param result Completes with the final context, or exceptionally with whatever a stage threw
 * @param task The submitted work, cancelled with an interrupt when the query is abandoned
 * @param partialContext Holds the latest completed stage's context so that a query interrupted
 *                       by a timeout or cancellation can still be answered from the results
 *                       already computed - the pipeline's contract is "partial results on
 *                       timeout", not "empty results on timeout"
 */
record PipelineRun(CompletableFuture<PipelineContext> result,
                   Future<?> task,
                   AtomicReference<PipelineContext> partialContext) {

    /**
     * Starts a run on the given executor.
     *
     * <p>Whatever the stages throw completes the future exceptionally rather than escaping into
     * the executor, where it would be swallowed and leave the caller waiting on a future nobody
     * will ever settle.</p>
     *
     * @param executor The executor the stages run on
     * @param stages Runs every stage in order against the holder it is handed, publishing each
     *               stage's context into it, and returns the final context
     * @return The run in flight
     */
    static PipelineRun start(ExecutorService executor,
                             Function<AtomicReference<PipelineContext>, PipelineContext> stages) {
        AtomicReference<PipelineContext> partialContext = new AtomicReference<>();
        CompletableFuture<PipelineContext> result = new CompletableFuture<>();
        Future<?> task = executor.submit(() -> {
            try {
                result.complete(stages.apply(partialContext));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return new PipelineRun(result, task, partialContext);
    }
}
