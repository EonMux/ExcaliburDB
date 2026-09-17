package io.excalibur.graph.build;

/**
 * Signals that a graph build stopped because someone asked it to.
 *
 * <p>Not a failure, and the distinction is the whole reason this is its own type. A build that
 * was stopped published nothing and left the graph it would have replaced exactly as it was,
 * which is the outcome the operator who sent {@code DELETE /v1/graph/build} asked for. Reporting
 * it as a failure would file a deliberate stop as an outage, and reporting it as a completed
 * build would say the graph now holds facts it does not.</p>
 *
 * <p>Unchecked, because it is raised from inside the visitors, writers and loops a build streams
 * through - none of which have a way to declare it, and all of which are on a path that already
 * propagates {@link java.io.IOException}. It is caught by the runner that set the signal, and by
 * nothing in between: a {@code catch (RuntimeException)} that swallowed it would turn a stop into
 * a build that quietly carried on with part of its work missing.</p>
 *
 * @see BuildCancellation
 * @author ExcaliburDB
 */
public final class BuildCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the signal.
     *
     * @param message Where the build stopped, for the log
     */
    public BuildCancelledException(String message) {
        super(message);
    }
}
