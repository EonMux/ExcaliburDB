package com.eonmux.excalibur.graph.staging;

import java.io.IOException;

/**
 * A build's publish was refused because an erasure was recorded while it ran.
 *
 * <p>The build read staging before the obligation existed, so it may hold what the obligation
 * covers; publishing it would put that back in service. Nothing was moved: the graph served
 * before the build still is, and building again withholds the new obligation along with the
 * others. An {@link IOException} because it ends the build the way any failure to publish does,
 * and distinct so that a caller can say why.</p>
 *
 * @author ExcaliburDB
 */
public class PublishRefusedException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message Which obligation overtook the build
     */
    public PublishRefusedException(String message) {
        super(message);
    }
}
