/**
 * The manifest: what a snapshot claims to contain, in a form that can be checked.
 *
 * <p>Everything a restore needs to refuse an image before it overwrites anything - the
 * name, length and checksums of every file, the format versions the image was written by,
 * and the epoch the image is coherent at - lives here, in one file that carries a checksum
 * of itself.</p>
 */
package com.eonmux.excalibur.backup.manifest;
