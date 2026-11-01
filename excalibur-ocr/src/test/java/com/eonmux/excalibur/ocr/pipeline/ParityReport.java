package com.eonmux.excalibur.ocr.pipeline;

import com.eonmux.excalibur.ocr.Deadline;
import com.eonmux.excalibur.ocr.IndraOptions;
import com.eonmux.excalibur.ocr.ReadMode;
import com.eonmux.excalibur.ocr.testing.Models;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How closely this port's output follows Indra OCR's own on a folder of PDFs: the character
 * error rate of each document's markdown against the markdown {@code indra-ocr} wrote for it.
 *
 * <p>Run with {@code ./gradlew :excalibur-ocr:parity -PparityDir=<dir>}, where the folder holds
 * {@code pdfs/<name>.pdf} and, per mode, {@code python/<mode>/<name>.md} written by
 * {@code indra-ocr --mode <mode> --max-pages 5}. The first five pages of each PDF are read here
 * too, from the file itself, and joined as the command line joins them. The report names
 * documents and numbers only, never their text, so it may be run on documents that are not to
 * be shared.</p>
 */
@Tag("parity")
@DisplayName("Parity with Indra OCR on a folder of PDFs")
class ParityReport {

    /** The pages Indra was asked for. */
    private static final int MAX_PAGES = 5;

    @Test
    @DisplayName("reports each document's character error rate against Indra's markdown")
    void report() throws Exception {
        String configured = System.getProperty("excalibur.ocr.parityDir", "");
        assertThat(configured).as("-PparityDir").isNotBlank();
        Path dir = Path.of(configured);
        List<Path> pdfs;
        try (Stream<Path> files = Files.list(dir.resolve("pdfs"))) {
            pdfs = files.filter(p -> p.toString().endsWith(".pdf")).sorted().toList();
        }
        for (String mode : List.of("auto", "ocr")) {
            Path reference = dir.resolve("python").resolve(mode);
            if (!Files.isDirectory(reference)) {
                continue;
            }
            IndraOptions options = new IndraOptions(ReadMode.of(mode), 200, false, false, true,
                0);
            List<Double> rates = new ArrayList<>();
            int exact = 0;
            try (PdfPages pages = PdfPages.load(Models.dir(), options)) {
                for (Path pdf : pdfs) {
                    String name = pdf.getFileName().toString().replaceFirst("\\.pdf$", "");
                    Path md = reference.resolve(name + ".md");
                    if (!Files.isRegularFile(md)) {
                        continue;
                    }
                    String want = Files.readString(md, StandardCharsets.UTF_8);
                    long start = System.nanoTime();
                    Read got = read(pages, pdf);
                    double rate = errorRate(got.markdown(), want);
                    rates.add(rate);
                    boolean same = got.markdown().equals(want);
                    exact += same ? 1 : 0;
                    System.out.printf(Locale.ROOT,
                        "%-5s %-12s pages=%d failed=%d cer=%.4f %s %.1fs%n", mode, name,
                        got.pages(), got.failed(), rate, same ? "exact" : "differs",
                        (System.nanoTime() - start) / 1e9);
                }
            }
            double mean = rates.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double max = rates.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            System.out.printf(Locale.ROOT,
                "%-5s documents=%d exact=%d mean-cer=%.4f max-cer=%.4f%n", mode, rates.size(),
                exact, mean, max);
        }
    }

    /** The first pages of a PDF as the command line writes them, pages in parallel. */
    private static Read read(PdfPages pages, Path pdf) throws Exception {
        int n;
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            n = Math.min(doc.getNumberOfPages(), MAX_PAGES);
        }
        String[] parts = new String[n];
        int[] failed = new int[1];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int index = i;
            threads.add(Thread.ofPlatform().start(() -> {
                try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                    parts[index] = pages.read(doc, index, Deadline.NONE).part();
                } catch (Exception e) {
                    parts[index] = "";
                    synchronized (failed) {
                        failed[0]++;
                    }
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        return new Read(String.join("\n\n", parts) + "\n", n, failed[0]);
    }

    /** Edit distance in code points over the reference's length. */
    static double errorRate(String got, String want) {
        int[] a = got.codePoints().toArray();
        int[] b = want.codePoints().toArray();
        if (b.length == 0) {
            return a.length == 0 ? 0 : 1;
        }
        int[] prev = new int[b.length + 1];
        int[] cur = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length; i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int sub = prev[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return (double) prev[b.length] / b.length;
    }

    private record Read(String markdown, int pages, int failed) {
    }
}
