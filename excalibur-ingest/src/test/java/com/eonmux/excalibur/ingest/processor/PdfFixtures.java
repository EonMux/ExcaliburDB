package com.eonmux.excalibur.ingest.processor;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Real PDFs for tests, written by PDFBox rather than kept as opaque files in the tree.
 */
public final class PdfFixtures {

    private PdfFixtures() {
        throw new AssertionError("Static methods only");
    }

    /**
     * A typeset PDF, one page per argument, each page's text on one line.
     *
     * @param pages Each page's text; an empty string makes a page with no text at all
     * @return The PDF's bytes
     */
    public static byte[] withPages(String... pages) {
        return write(null, pages);
    }

    /**
     * A PDF with pages and no text on any of them: a scan, as far as its text layer goes.
     *
     * @param pages How many pages
     * @return The PDF's bytes
     */
    public static byte[] withoutText(int pages) {
        String[] empty = new String[pages];
        java.util.Arrays.fill(empty, "");
        return write(null, empty);
    }

    /**
     * A PDF that cannot be opened without a password.
     *
     * @param text The one page's text
     * @return The PDF's bytes
     */
    public static byte[] encrypted(String text) {
        return write("secret", text);
    }

    private static byte[] write(String userPassword, String... pages) {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text.isEmpty()) {
                    continue;
                }
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 11);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            if (userPassword != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner",
                    userPassword, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Writing a fixture PDF failed", e);
        }
    }
}
