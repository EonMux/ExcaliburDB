package com.eonmux.excalibur.ocr.testing;

import com.eonmux.excalibur.ocr.model.IndraModels;
import com.eonmux.excalibur.ocr.model.ModelStore;
import com.eonmux.excalibur.ocr.model.ModelUnavailableException;

import java.nio.file.Path;

/**
 * Where tests find Indra OCR's model files: the directory named by the system property
 * {@code excalibur.ocr.modelDir} or the environment variable {@code INDRA_OCR_MODEL_DIR}, used as
 * it is; otherwise a node's default model directory, filled from Hugging Face the first time.
 * Either way every file is checked against its pinned digest.
 */
public final class Models {

    private static volatile Path dir;

    private Models() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The model directory, holding every file of the pinned set, math models included.
     *
     * @return The directory
     * @throws IllegalStateException if the models are not there and cannot be downloaded
     */
    public static Path dir() {
        Path resolved = dir;
        if (resolved != null) {
            return resolved;
        }
        synchronized (Models.class) {
            if (dir == null) {
                dir = resolve();
            }
            return dir;
        }
    }

    private static Path resolve() {
        String configured = System.getProperty("excalibur.ocr.modelDir",
            System.getenv("INDRA_OCR_MODEL_DIR"));
        ModelStore store = configured != null && !configured.isBlank()
            ? ModelStore.local(Path.of(configured))
            : ModelStore.downloading(ModelStore.defaultDirectory());
        try {
            return store.ensure(IndraModels.files(true));
        } catch (ModelUnavailableException e) {
            throw new IllegalStateException("Indra OCR's models are needed by this test: "
                + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching Indra OCR's models", e);
        }
    }
}
