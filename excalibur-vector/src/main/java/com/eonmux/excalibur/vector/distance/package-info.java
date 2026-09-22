/**
 * Distance and similarity functions for vector comparison.
 *
 * <p>This package provides implementations of common distance metrics
 * and similarity functions used in vector search:</p>
 *
 * <ul>
 *   <li>{@link com.eonmux.excalibur.vector.distance.CosineSimilarity} - Cosine angle similarity [-1, 1]</li>
 *   <li>{@link com.eonmux.excalibur.vector.distance.L2Distance} - Euclidean (L2) distance [0, +inf)</li>
 *   <li>{@link com.eonmux.excalibur.vector.distance.DistanceFunction} - Common interface for all functions</li>
 * </ul>
 *
 * <h2>Vector API Acceleration with Graceful Fallback</h2>
 * <p>Implementations use the JDK Vector API (jdk.incubator.vector) for SIMD
 * acceleration when available at runtime. The vectorized path is automatically
 * selected for vectors above a size threshold (typically 8-64 elements depending
 * on the function).</p>
 *
 * <p><strong>Graceful Fallback:</strong> When the Vector API is unavailable
 * (e.g., JVM started without --add-modules=jdk.incubator.vector, or platform
 * doesn't support SIMD), implementations automatically fall back to optimized
 * scalar computation. The fallback is transparent to callers - the same API
 * works regardless of Vector API availability.</p>
 *
 * <p>Use {@code isVectorApiAvailable()} on each distance function to check
 * whether Vector API acceleration is active.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>All implementations in this package are thread-safe and support concurrent
 * invocation from multiple threads.</p>
 *
 * <h2>Domain Correctness</h2>
 * <p>Each function documents its output range and edge case handling.
 * Results are clamped or validated to ensure domain correctness:</p>
 * <ul>
 *   <li>Cosine similarity: always in [-1.0, 1.0]</li>
 *   <li>L2 distance: always &gt;= 0.0, identical vectors produce 0.0</li>
 *   <li>Zero vectors: return defined values (not undefined)</li>
 *   <li>NaN propagation: documented behavior</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
package com.eonmux.excalibur.vector.distance;
