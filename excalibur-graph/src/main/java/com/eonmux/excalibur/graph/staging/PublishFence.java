package com.eonmux.excalibur.graph.staging;

import java.io.IOException;

/**
 * What a build must leave out of the graph it publishes, and the gate its publish passes through.
 *
 * <h2>Why a build needs to be told</h2>
 * <p>Staging holds a fact until an erasure compaction rewrites it away, and an erasure obligation
 * is owed from the moment it is recorded - which is before that compaction, often long before,
 * since the compaction waits on a policy. A build replays staging as it finds it, so without
 * this a build between the two would publish exactly what the obligation was accepted to remove:
 * a retracted fact back in the served graph, an erased person's name back in its label index.
 * That is not a slower erasure but a reversed one, and with builds that run by themselves it
 * would happen without anybody asking for it.</p>
 *
 * <h2>Why two halves</h2>
 * <p>{@link #withheld()} is what was owed when the fence was taken, and the build leaves all of
 * it out. That covers everything recorded before the build began, however the staged facts it
 * covers arrived. It cannot cover an obligation recorded while the build runs - a build takes
 * minutes, and recording an erasure must not wait for one - so {@link #swap} is the other half:
 * the publish's renames run inside it, and it refuses them if anything was owed since the fence
 * was taken. The refusal and the renames are one step with respect to a new obligation, so an
 * obligation is either one the build withheld or one recorded after the graph was in place,
 * against which it stands like any other until a compaction applies it.</p>
 *
 * <h2>What it does not do</h2>
 * <p>Nothing here discharges an obligation. A build that withheld a fact has kept it out of one
 * graph; staging, and any copy of the graph beside the served one, still hold it, and only the
 * erasure compaction removes those and records the obligation met.</p>
 *
 * <p>Declared here rather than in the erasure package, as {@link StagedErasure} is, so that the
 * build does not have to depend on the erasure log that supplies it.</p>
 *
 * @author ExcaliburDB
 */
public interface PublishFence {

    /**
     * No obligations: withholds nothing and admits every swap.
     *
     * <p>For a build with no erasure log behind it - a tool, a test, a staging directory that is
     * not a namespace's.</p>
     */
    PublishFence NONE = new PublishFence() {
        @Override
        public StagedErasure withheld() {
            return StagedErasure.NOTHING;
        }

        @Override
        public int obligations() {
            return 0;
        }

        @Override
        public void swap(Swap swap) throws IOException {
            swap.run();
        }

        @Override
        public String toString() {
            return "PublishFence.NONE";
        }
    };

    /**
     * What the build must leave out: every staged record an obligation owed when this fence was
     * taken covers.
     *
     * @return The records to withhold
     */
    StagedErasure withheld();

    /**
     * How many obligations {@link #withheld()} stands for.
     *
     * @return The count, zero when nothing was owed
     */
    int obligations();

    /**
     * Runs the renames that put a build in place, unless an obligation was recorded since this
     * fence was taken.
     *
     * <p>An obligation recorded while {@code swap} runs is ordered after it.</p>
     *
     * @param swap The renames
     * @throws PublishRefusedException if an obligation was recorded since this fence was taken;
     *                                 {@code swap} has not run
     * @throws IOException             if {@code swap} fails
     */
    void swap(Swap swap) throws IOException;

    /** The renames a publish consists of. */
    @FunctionalInterface
    interface Swap {

        /**
         * Moves the built graph into place.
         *
         * @throws IOException if a rename fails
         */
        void run() throws IOException;
    }
}
