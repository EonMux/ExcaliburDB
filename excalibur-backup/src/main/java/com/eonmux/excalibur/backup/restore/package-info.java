/**
 * Rebuilding a data directory from a snapshot.
 *
 * <p>Every refusal in this package is load-bearing: a restore writes over a data
 * directory, and there is no second chance at the target an operator typed by mistake.</p>
 */
package com.eonmux.excalibur.backup.restore;
