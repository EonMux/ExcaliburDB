/**
 * ExcaliburDB in-process, without the HTTP listener.
 *
 * <p>{@link com.eonmux.excalibur.api.embedded.ExcaliburEmbedded} opens a node over a data
 * directory through the server's own assembly and exposes supplying, querying, reading back,
 * listing and erasing documents as method calls. Every type its methods take or return is either
 * a JDK type or one of this package's records, so an embedder compiles against
 * {@code excalibur-api} alone.</p>
 */
package com.eonmux.excalibur.api.embedded;
