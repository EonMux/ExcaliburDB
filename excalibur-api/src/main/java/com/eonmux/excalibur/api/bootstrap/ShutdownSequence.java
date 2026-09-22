package com.eonmux.excalibur.api.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An ordered, fail-tolerant shutdown sequence.
 *
 * <p>Shutdown order is a correctness property here, not a tidiness one. Query threads
 * read memory-mapped segments through a shared {@link java.lang.foreign.Arena}; closing
 * that arena while a mapping is still being read crashes the JVM rather than throwing.
 * The safe order is therefore always:</p>
 * <ol>
 *   <li>stop accepting new work (HTTP server)</li>
 *   <li>drain work already in flight (endpoint executors, ingestion, background workers)</li>
 *   <li>close the storage engine</li>
 *   <li>close the arena last</li>
 * </ol>
 *
 * <p>Every step runs even if an earlier one failed, because a step that throws must not
 * leave the arena open with the process still holding mappings.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Instances are built on one thread and run once. {@link #run()} is idempotent.</p>
 *
 * @author ExcaliburDB
 */
public final class ShutdownSequence {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShutdownSequence.class);

    private final List<Step> steps = new ArrayList<>();
    private volatile boolean executed;

    /**
     * A shutdown action that may fail.
     */
    @FunctionalInterface
    public interface ShutdownAction {
        /**
         * Performs the action.
         *
         * @throws Exception if the step fails; later steps still run
         */
        void run() throws Exception;
    }

    /**
     * A shutdown action on a target that may or may not be there.
     *
     * <p>Takes the target rather than closing over it, so that a caller writes
     * {@code Arena::close} - an unbound reference, evaluated per call - where the no-argument
     * form would invite {@code arena::close}, which binds its receiver while the argument list
     * is being evaluated and therefore throws on a null target before this class ever gets to
     * test it. That failure takes the whole sequence with it, on the one path where nothing
     * else will ever close the engine.</p>
     *
     * @param <T> What the step acts on
     */
    @FunctionalInterface
    public interface TargetedShutdownAction<T> {
        /**
         * Performs the action.
         *
         * @param target The object the step acts on, never null
         * @throws Exception if the step fails; later steps still run
         */
        void run(T target) throws Exception;
    }

    private record Step(String name, ShutdownAction action) {
    }

    /**
     * Appends a step. Steps run in the order they were added.
     *
     * @param name   Human-readable name, used in logs and in the completion report
     * @param action The action to run
     * @return this sequence
     */
    public ShutdownSequence add(String name, ShutdownAction action) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(action, "action cannot be null");
        steps.add(new Step(name, action));
        return this;
    }

    /**
     * Appends a step only when the target is present.
     *
     * <p>The action receives the target, which is what makes the null-safety structural: there
     * is no receiver to bind before the check, so a caller cannot write the reference that
     * would fail. See {@link TargetedShutdownAction}.</p>
     *
     * @param name   Step name
     * @param target The object the step acts on; when null the step is skipped
     * @param action The action to run on it
     * @param <T>    Target type
     * @return this sequence
     */
    public <T> ShutdownSequence addIfPresent(String name, T target,
                                             TargetedShutdownAction<T> action) {
        Objects.requireNonNull(action, "action cannot be null");
        if (target != null) {
            add(name, () -> action.run(target));
        }
        return this;
    }

    /**
     * Runs every step in order.
     *
     * @return The names of the steps that completed without throwing, in order
     */
    public List<String> run() {
        if (executed) {
            return Collections.emptyList();
        }
        executed = true;

        List<String> completed = new ArrayList<>(steps.size());
        for (Step step : steps) {
            try {
                LOGGER.info("Shutdown step: {}", step.name());
                step.action().run();
                completed.add(step.name());
            } catch (Exception e) {
                LOGGER.error("Shutdown step '{}' failed: {}", step.name(), e.getMessage(), e);
            }
        }
        return Collections.unmodifiableList(completed);
    }

    /**
     * Returns the step names in execution order.
     *
     * @return Ordered step names
     */
    public List<String> stepNames() {
        List<String> names = new ArrayList<>(steps.size());
        for (Step step : steps) {
            names.add(step.name());
        }
        return Collections.unmodifiableList(names);
    }
}
