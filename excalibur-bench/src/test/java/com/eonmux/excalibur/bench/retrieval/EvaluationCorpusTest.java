package com.eonmux.excalibur.bench.retrieval;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.IngestConfig;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.ingest.graph.LlmRelationExtractor;
import com.eonmux.excalibur.ingest.processor.TextChunk;
import com.eonmux.excalibur.ingest.processor.TextChunker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The corpus as the harness reads it.
 *
 * <p>Every strategy is judged against one qrels file, and the strategies that run on a node name
 * their chunks the way ingestion names them. A harness that cut the corpus any other way would
 * score a node-backed strategy against chunk names it never returns - which reads as a strategy
 * that retrieves nothing.
 */
@DisplayName("Evaluation corpus")
class EvaluationCorpusTest {

    private static final IngestConfig INGEST = new Config(Map.of()).ingest();

    /** The evaluation nodes' data root, which reading the corpus never writes to. */
    private static final Path INDEX = Path.of("/nonexistent/evaluation-index");

    @Test
    @DisplayName("names every chunk the way a node's ingestion of the same directory does")
    void namesChunksAsIngestionDoes(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("notes"));
        Files.writeString(root.resolve("notes/oom.txt"), "the OOM killer ran");
        Files.writeString(root.resolve("tls.txt"), "the handshake failed");

        EvaluationCorpus corpus = EvaluationCorpus.read(root, INGEST, INDEX);

        assertThat(corpus.chunks()).extracting(EvaluationCorpus.Chunk::id)
            .containsExactlyInAnyOrder(
                ChunkIdentifiers.chunkId(EvaluationCorpus.SOURCE_ID, "notes/oom.txt", 0),
                ChunkIdentifiers.chunkId(EvaluationCorpus.SOURCE_ID, "tls.txt", 0));
        assertThat(corpus.documents()).isEqualTo(2);
        assertThat(corpus.characters()).isEqualTo(
            "the OOM killer ran".length() + "the handshake failed".length());
    }

    @Test
    @DisplayName("cuts a long document where the ingestion chunker cuts it, overlap included")
    void cutsAsTheChunkerDoes(@TempDir Path root) throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; text.length() < 20_000; i++) {
            text.append("Sentence number ").append(i).append(" says something plain. ");
        }
        Files.writeString(root.resolve("long.txt"), text);

        EvaluationCorpus corpus = EvaluationCorpus.read(root, INGEST, INDEX);

        String documentKey = ChunkIdentifiers.documentKey(EvaluationCorpus.SOURCE_ID, "long.txt");
        List<TextChunk> expected = new TextChunker(INGEST.getChunkTargetTokens(),
            INGEST.getChunkOverlapTokens()).chunk(documentKey, text.toString());
        assertThat(expected).hasSizeGreaterThan(2);
        assertThat(corpus.chunks()).extracting(EvaluationCorpus.Chunk::text)
            .containsExactlyElementsOf(expected.stream().map(TextChunk::getText).toList());
        assertThat(corpus.chunks()).extracting(EvaluationCorpus.Chunk::id)
            .containsExactlyElementsOf(expected.stream()
                .map(chunk -> ChunkIdentifiers.chunkIdForDocumentKey(documentKey,
                    chunk.getChunkIndex()))
                .toList());
    }

    @Test
    @DisplayName("passes over what ingestion passes over: bytes that are not text, and dot-files")
    void skipsWhatIngestionSkips(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("kept.txt"), "plain words");
        Files.write(root.resolve("image.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0,
            (byte) 0xFF, (byte) 0xFE, 0, 1, 2, 3, 0, 0, 0});
        Files.writeString(root.resolve(".hidden.txt"), "never crawled");

        EvaluationCorpus corpus = EvaluationCorpus.read(root, INGEST, INDEX);

        assertThat(corpus.chunks()).extracting(EvaluationCorpus.Chunk::id)
            .containsExactly(ChunkIdentifiers.chunkId(EvaluationCorpus.SOURCE_ID, "kept.txt", 0));
    }

    @Test
    @DisplayName("an empty document has no chunks, and is still a document")
    void emptyDocuments(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("empty.txt"), "", StandardCharsets.UTF_8);

        EvaluationCorpus corpus = EvaluationCorpus.read(root, INGEST, INDEX);

        assertThat(corpus.chunks()).isEmpty();
        assertThat(corpus.documents()).isEqualTo(1);
    }

    @Test
    @DisplayName("a fact's provenance leads back to the document it was extracted from")
    void provenanceLeadsToTheDocument(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("ada.txt"), "Ada Lovelace wrote Note G");
        EvaluationCorpus corpus = EvaluationCorpus.read(root, INGEST, INDEX);
        String documentKey = ChunkIdentifiers.documentKey(EvaluationCorpus.SOURCE_ID, "ada.txt");

        Provenance extracted = Provenance.of(SourceType.DOCUMENT,
            LlmRelationExtractor.sourceIdOf(documentKey), 0.9, 1);

        assertThat(corpus.documentOf(extracted)).isEqualTo(documentKey);
        assertThat(corpus.documentOf(Provenance.of(SourceType.USER_ASSERTED,
            LlmRelationExtractor.sourceIdOf(documentKey), 0.9, 1)))
            .as("the same number from a conversation is not this document")
            .isNull();
        assertThat(corpus.documentOf(Provenance.of(SourceType.DOCUMENT, 42L, 0.9, 1)))
            .as("a document this corpus does not hold")
            .isNull();
    }

    @Test
    @DisplayName("a corpus that is not a directory is refused")
    void notADirectory(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("file.txt"), "x");

        assertThatThrownBy(() -> EvaluationCorpus.read(file, INGEST, INDEX))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("directory");
    }
}
