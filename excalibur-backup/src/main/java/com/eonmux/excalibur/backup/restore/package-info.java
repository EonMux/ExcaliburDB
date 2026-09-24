/**
 * Rebuilding a data directory from a snapshot.
 *
 * <p>Every refusal in this package is load-bearing: a restore writes over a data
 * directory, and there is no second chance at the target an operator typed by mistake.</p>
 *
 * <p>A restore works inside its target and never renames the target itself, so a data
 * directory that is a mount point - a container volume - is restored like any other. It holds
 * the target's lock throughout and journals each step, so a crash leaves a directory the node
 * refuses to start on and the next restore completes or undoes.</p>
 */
package com.eonmux.excalibur.backup.restore;
