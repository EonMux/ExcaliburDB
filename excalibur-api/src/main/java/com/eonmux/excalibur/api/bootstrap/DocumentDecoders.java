package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.IngestConfig;
import com.eonmux.excalibur.ingest.processor.DocumentDecoder;
import com.eonmux.excalibur.ingest.processor.IndraPdfReader;
import com.eonmux.excalibur.ingest.processor.PdfConverter;
import com.eonmux.excalibur.ocr.IndraOcr;
import com.eonmux.excalibur.ocr.IndraOptions;
import com.eonmux.excalibur.ocr.ReadMode;
import com.eonmux.excalibur.ocr.model.IndraModels;
import com.eonmux.excalibur.ocr.model.ModelFile;
import com.eonmux.excalibur.ocr.model.ModelStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds the decoder a node reads fetched documents with, from its ingest configuration.
 *
 * <p>Built once per node and handed to both ingestion and the reindexer, so a re-crawl reads a
 * document exactly as the crawl that first indexed it did, and so the bound on concurrent PDF
 * conversions holds across both. The node releases it at shutdown
 * ({@link DocumentDecoder#release}): an OCR reader holds its models and threads until then.</p>
 */
public final class DocumentDecoders {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentDecoders.class);

    private DocumentDecoders() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The decoder {@code excalibur.ingest.pdf-reader} and the settings beside it describe.
     *
     * <p>A reader that could not read a PDF is refused here, so a node that would fail every PDF
     * does not start: the operator asked for OCR, and a node that quietly fell back to the text
     * layer would index every scanned page as nothing. That covers a converter that cannot be
     * found, Indra's model licence not accepted, Indra's models missing from a directory the
     * node may not download them into, and a model directory inside the data directory, whose
     * every file the node accounts for and snapshots.</p>
     *
     * @param ingest        The node's ingest configuration
     * @param dataDirectory The node's data directory
     * @return The decoder
     * @throws IllegalArgumentException if the configured reader cannot read PDFs
     * @throws NullPointerException     if an argument is null
     */
    public static DocumentDecoder from(IngestConfig ingest, Path dataDirectory) {
        Objects.requireNonNull(ingest, "ingest must not be null");
        Objects.requireNonNull(dataDirectory, "dataDirectory must not be null");
        return switch (ingest.getPdfReader()) {
            case IngestConfig.PDF_READER_INDRA ->
                DocumentDecoder.reading(indra(ingest, dataDirectory));
            case IngestConfig.PDF_READER_COMMAND -> DocumentDecoder.reading(command(ingest));
            default -> {
                LOGGER.info("PDFs are read by their text layer; a scanned page has none. Set "
                    + "excalibur.ingest.pdf-reader=indra to read them with OCR");
                yield DocumentDecoder.builtIn();
            }
        };
    }

    private static PdfConverter command(IngestConfig ingest) {
        PdfConverter converter = PdfConverter.of(ingest.getPdfConverterCommand(),
            ingest.getPdfConverterArgs(), timeout(ingest), ingest.getPdfConverterParallelism());
        LOGGER.info("PDFs are converted by '{}', {} at a time, each given up to {}s",
            converter.identity(), ingest.getPdfConverterParallelism(),
            ingest.getPdfConverterTimeoutSeconds());
        return converter;
    }

    private static IndraPdfReader indra(IngestConfig ingest, Path dataDirectory) {
        if (!ingest.isIndraAcceptModelLicense()) {
            throw new IllegalArgumentException("excalibur.ingest.pdf-reader=indra reads PDFs with "
                + "Indra OCR's models, which are published under the " + IndraModels.LICENSE_NAME
                + " (" + IndraModels.LICENSE_URL + "); its terms restrict who may use them. Read "
                + "it, and set excalibur.ingest.indra-accept-model-license=true to accept it");
        }
        ModelStore store = modelStore(ingest);
        requireOutside(store.directory(), dataDirectory);
        IndraOptions options = new IndraOptions(ReadMode.of(ingest.getIndraMode()),
            ingest.getIndraDpi(), ingest.isIndraMath(), ingest.isIndraKeepFurniture(),
            ingest.isIndraImageOcr(), ingest.getIndraThreads());
        List<ModelFile> missing = store.missing(IndraModels.files(options.math()));
        if (!missing.isEmpty() && !store.downloads()) {
            throw new IllegalArgumentException("Indra OCR's models are missing from "
                + store.directory() + " (" + names(missing) + ") and "
                + "excalibur.ingest.indra-model-download=false; copy revision "
                + IndraModels.REVISION + " of " + IndraModels.REPOSITORY + " there, or allow the "
                + "download");
        }
        IndraPdfReader reader = IndraPdfReader.of(IndraOcr.create(store, options),
            timeout(ingest), ingest.getPdfConverterParallelism());
        LOGGER.info("PDFs are read by {}, {} at a time, each given up to {}s, {} pages at once; "
            + "models in {}{}", reader.identity(), ingest.getPdfConverterParallelism(),
            ingest.getPdfConverterTimeoutSeconds(), options.effectiveThreads(), store.directory(),
            missing.isEmpty() ? "" : ", downloaded from " + IndraModels.REPOSITORY
                + " when the first PDF is read");
        return reader;
    }

    private static ModelStore modelStore(IngestConfig ingest) {
        String configured = ingest.getIndraModelDir();
        Path directory;
        try {
            directory = configured.isEmpty() ? ModelStore.defaultDirectory()
                : Path.of(configured).toAbsolutePath();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("excalibur.ingest.indra-model-dir is not a path: "
                + e.getMessage(), e);
        }
        return ingest.isIndraModelDownload() ? ModelStore.downloading(directory)
            : ModelStore.local(directory);
    }

    /**
     * Refuses a model directory inside the data directory. Every file there is the node's to
     * account for - a check refuses what it does not recognise, and a snapshot would carry
     * models it can download again - and in the container image the node user's home, where
     * the default cache would be, is the data directory itself. Links are followed on both
     * sides, as far as the paths exist.
     */
    private static void requireOutside(Path models, Path dataDirectory) {
        Path data = resolved(dataDirectory);
        Path dir = resolved(models);
        if (dir.startsWith(data)) {
            throw new IllegalArgumentException("Indra OCR's model directory " + dir + " is inside "
                + "the data directory " + data + ", which holds only the node's own files; set "
                + "excalibur.ingest.indra-model-dir, or XDG_CACHE_HOME, to a directory outside it");
        }
    }

    /** The path with its longest existing prefix resolved to a real path. */
    private static Path resolved(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute));
        } catch (IOException e) {
            return absolute;
        }
    }

    private static Duration timeout(IngestConfig ingest) {
        return Duration.ofSeconds(ingest.getPdfConverterTimeoutSeconds());
    }

    private static String names(List<ModelFile> files) {
        return files.stream().map(ModelFile::name).collect(Collectors.joining(", "));
    }
}
