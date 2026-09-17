/**
 * The one CRC32C primitive and the one verdict type every persisted format in the engine uses.
 *
 * <p>Every format follows the same shape, which is the one
 * {@code io.excalibur.store.catalog.CatalogFile} established: a checksum over the payload and
 * a second one over the header, both written when the writer seals the file and both verified
 * before anything in the file is believed. A file that fails is refused by name. It is never
 * loaded partially, and never treated as absent - an index that silently reads as empty turns
 * a single bad sector into a corpus that has apparently disappeared.</p>
 *
 * <p>Each format additionally exposes a verification entry point that reaches a verdict
 * without loading the structure into service, so a checking tool can survey a node's files
 * without taking on the cost - or the risk - of opening them for use.</p>
 *
 * <p>It lives in core because the formats that need it are spread across modules that are
 * siblings: the HNSW graph and the product-quantisation codebook in {@code excalibur-vector},
 * the Cuckoo filter and the LSH table in {@code excalibur-filter}, the lexical segment columns
 * in {@code excalibur-retrieval}. Each of those already depends on core, and none of them may
 * depend on the others purely to borrow a checksum - so the primitive was written twice before
 * it was written here. A second copy is a second definition of what "intact" means, and two
 * modules that disagree about that produce a file one of them will not open.</p>
 */
package io.excalibur.core.integrity;
