/**
 * Product Quantization for vector compression and approximate nearest neighbor search.
 *
 * <p>Product Quantization (PQ) is a vector compression technique that decomposes
 * high-dimensional vectors into multiple sub-vectors, each of which is quantized
 * independently using a small codebook. This enables:</p>
 *
 * <ul>
 *   <li><strong>Compression:</strong> Reduce memory footprint by 8-32x (e.g., 128D float vector → 16 bytes)</li>
 *   <li><strong>Fast search:</strong> Asymmetric distance computation using precomputed lookup tables</li>
 *   <li><strong>Accuracy:</strong> Graceful degradation with configurable sub-space granularity</li>
 * </ul>
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li>{@link io.excalibur.vector.quantization.KMeansClusterer} - K-means++ clustering for codebook generation</li>
 *   <li>{@link io.excalibur.vector.quantization.CodebookTrainer} - Trains codebooks on sample vectors</li>
 *   <li>{@link io.excalibur.vector.quantization.ProductQuantizer} - Main quantization interface</li>
 *   <li>{@link io.excalibur.vector.quantization.PQDistanceTable} - Precomputed distance lookup tables</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All classes in this package are thread-safe for their intended use cases:</p>
 * <ul>
 *   <li>Codebook training is NOT thread-safe (single-threaded batch operation)</li>
 *   <li>Encoding is thread-safe for read-only access to trained codebooks</li>
 *   <li>Distance computation is fully thread-safe via immutable distance tables</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
package io.excalibur.vector.quantization;
