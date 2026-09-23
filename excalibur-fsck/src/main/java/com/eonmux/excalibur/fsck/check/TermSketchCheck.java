package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.store.TermSketchFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies a {@code segment_N.tfs} term-frequency sketch.
 *
 * <p>The store's own verifier reads the whole file, weighs a CRC32C over the header and
 * another over the body, and confirms the body decodes as a sketch; nothing about the layout
 * is repeated here. It reads into memory and writes nothing, so like every checker behind
 * {@link FileInspector} it can be pointed at a snapshot's payload where it lies.</p>
 *
 * <p>What this adds is what a damaged sketch means. The node compares every sketch with the
 * segment it describes when it opens that segment, and rebuilds it from the live entries if
 * it is missing, stale or fails its checksum - before the segment serves anything. A damaged
 * one is therefore reported as corrupt, because it is, and is not counted as damage to the
 * store: {@link FileCheck#isDamage()} consults {@link FileKind#rebuiltOnOpen()} for that, so
 * the decision lives with the kind rather than being bent into the status.</p>
 *
 * <p>The exemption is for corrupt bytes only. A sketch the operating system would not hand
 * over - an I/O error, a permission refused - is reported unreadable by the store's verifier,
 * and stays damage: the node would meet the same refusal on the segment beside it, and a
 * rebuild answers nothing about the device.</p>
 */
public final class TermSketchCheck {

    private TermSketchCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Verifies a term sketch file.
     *
     * @param file     The {@code .tfs} file
     * @param relative Its path relative to the data directory
     * @return The verdict, whose detail says what a damaged sketch costs
     */
    public static FileCheck check(Path file, String relative) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = -1;
        }
        FileCheck verdict = IntegrityReports.from(
            TermSketchFile.verifyIntegrity(file), file, relative, null, size);
        if (verdict.status() != CheckStatus.CORRUPT) {
            return verdict;
        }
        return new FileCheck(file, relative, verdict.format(), verdict.version(),
            verdict.status(), verdict.detail() + ". The node rebuilds this sketch from the "
                + "segment's live entries when it next opens the segment, so it costs a "
                + "rebuild at start-up rather than data, and it is not counted as damage",
            verdict.sizeBytes());
    }
}
