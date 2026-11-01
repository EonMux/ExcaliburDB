package com.eonmux.excalibur.bench.retrieval;

import com.eonmux.excalibur.api.bootstrap.DocumentDecoders;
import com.eonmux.excalibur.config.IngestConfig;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.ingest.graph.LlmRelationExtractor;
import com.eonmux.excalibur.ingest.processor.TextChunk;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.processor.DocumentDecoder;
import com.eonmux.excalibur.ingest.source.DocumentLimits;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.FilesystemSource;
import com.eonmux.excalibur.ingest.source.SourceException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The corpus as a node's ingestion reads it: the same crawl, decoding, chunking and chunk names.
 *
 * <p>Every strategy is judged against one qrels file. Some run on an index the harness builds
 * itself and some on an evaluation node that ingested the same directory, so the two must agree
 * on what the chunks are and what they are called - otherwise a node-backed strategy is scored
 * against names it never returns, which reads as a strategy that retrieves nothing. Reading the
 * corpus with the ingestion pipeline's own pieces is what makes them agree: the filesystem source
 * decides which files are documents, the node's {@link DocumentDecoder} reads their text, the
 * configured {@link TextChunker} cuts them, and {@link ChunkIdentifiers} names the pieces.
 *
 * <p>It also answers the one question the graph strategies need that no index can: which document
 * a fact came from. Extraction stamps each fact with a number derived from its document's key, and
 * {@link #documentOf(Provenance)} runs that derivation backwards over the documents read here.
 */
final class EvaluationCorpus {

    /** The source id the corpus is ingested under, and so the first part of every chunk name. */
    static final String SOURCE_ID = "corpus";

    /** How long the decoder is given to free its resources; no conversion is left by then. */
    private static final Duration RELEASE_WAIT = Duration.ofSeconds(30);

    /** One chunk: its name as ingestion assigns it, and its text. */
    record Chunk(String id, String text) {
        Chunk {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(text, "text must not be null");
        }
    }

    private final List<Chunk> chunks;
    private final int documents;
    private final Map<Long, String> documentByProvenance;
    private final long characters;

    private EvaluationCorpus(List<Chunk> chunks, int documents,
                             Map<Long, String> documentByProvenance, long characters) {
        this.chunks = List.copyOf(chunks);
        this.documents = documents;
        this.documentByProvenance = documentByProvenance;
        this.characters = characters;
    }

    /**
     * Reads and chunks every document under a directory.
     *
     * @param root      The corpus directory
     * @param ingest    The ingestion settings whose chunk sizing and PDF reader a node would use
     * @param indexRoot The directory the evaluation nodes keep their data under, which the PDF
     *                  reader's models must lie outside of, as they must for a node
     * @throws IllegalArgumentException if the root is not a directory, or the PDF reader is
     *                                  configured as no node would start with
     * @throws IOException              if the directory cannot be crawled or a file read
     */
    static EvaluationCorpus read(Path root, IngestConfig ingest, Path indexRoot)
            throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("the corpus must be a directory: " + root);
        }
        TextChunker chunker =
            new TextChunker(ingest.getChunkTargetTokens(), ingest.getChunkOverlapTokens());
        // The decoder the node builds from the same configuration, so a PDF in the corpus is read
        // here exactly as ingestion reads it.
        DocumentDecoder decoder = DocumentDecoders.from(ingest, indexRoot);
        List<Chunk> chunks = new ArrayList<>();
        Set<String> documents = new HashSet<>();
        Map<Long, String> byProvenance = new HashMap<>();
        long characters = 0;

        // The real path, as the node's source factory resolves it, so a corpus reached through a
        // symlink is crawled as the same set of files the node would crawl.
        FilesystemSource source = FilesystemSource.builder()
            .sourceId(SOURCE_ID)
            .rootPath(root.toRealPath())
            .build();
        try {
            List<String> documentIds;
            try (Stream<String> crawl = source.crawl()) {
                // Sorted so chunks are indexed in the same order every run.
                documentIds = crawl.sorted().toList();
            }
            for (String documentId : documentIds) {
                FetchedDocument fetched = source.fetch(documentId);
                if (DocumentLimits.exceeds(fetched)) {
                    continue;
                }
                String text = decode(decoder, fetched);
                if (text == null) {
                    continue;
                }
                String documentKey = ChunkIdentifiers.documentKey(SOURCE_ID, documentId);
                List<TextChunk> pieces = chunker.chunk(documentKey, text);
                // Ingestion drops such a document whole, so indexing it here would give the
                // strategies the harness runs itself a document the node never holds.
                if (pieces.size() > DocumentLimits.MAX_CHUNKS_PER_DOCUMENT) {
                    continue;
                }
                for (TextChunk chunk : pieces) {
                    chunks.add(new Chunk(ChunkIdentifiers.chunkIdForDocumentKey(documentKey,
                        chunk.getChunkIndex()), chunk.getText()));
                }
                documents.add(documentKey);
                byProvenance.putIfAbsent(LlmRelationExtractor.sourceIdOf(documentKey),
                    documentKey);
                characters += text.length();
            }
        } catch (SourceException e) {
            throw new IOException("could not read the corpus under " + root + ": "
                + e.getMessage(), e);
        } finally {
            source.close();
            release(decoder);
        }
        return new EvaluationCorpus(chunks, documents.size(), Map.copyOf(byProvenance),
            characters);
    }

    /** Every chunk, document by document in path order, each document's in its own order. */
    List<Chunk> chunks() {
        return chunks;
    }

    /** How many text documents the corpus holds, empty ones included. */
    int documents() {
        return documents;
    }

    /** Characters of text across every document. */
    long characters() {
        return characters;
    }

    /**
     * The document a fact was extracted from.
     *
     * @return Its document key, or null when the fact came from something other than a document
     *         of this corpus
     */
    String documentOf(Provenance provenance) {
        if (provenance == null || provenance.sourceType() != SourceType.DOCUMENT) {
            return null;
        }
        return documentByProvenance.get(provenance.sourceId());
    }

    /** Frees the decoder's resources: with Indra OCR, its loaded models. */
    private static void release(DocumentDecoder decoder) throws InterruptedIOException {
        try {
            decoder.release(RELEASE_WAIT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted releasing the PDF reader");
        }
    }

    /**
     * Reads one document as ingestion would, or reports that ingestion would pass it over.
     *
     * @return Its text, or null when the node would not index it
     * @throws InterruptedIOException if the thread is interrupted while a PDF is converted
     */
    private static String decode(DocumentDecoder decoder, FetchedDocument fetched)
            throws InterruptedIOException {
        try {
            DocumentDecoder.Decoded decoded = decoder.decode(fetched);
            return decoded.isText() ? decoded.text() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted reading " + fetched.getDocumentId());
        }
    }
}
