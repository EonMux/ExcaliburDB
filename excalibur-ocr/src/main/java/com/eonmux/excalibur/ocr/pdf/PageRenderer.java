// Ported from Indra OCR 1.1.2, document.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pdf;

import com.eonmux.excalibur.ocr.image.GrayImage;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.util.Objects;

/**
 * Renders a page to an 8-bit gray image as pypdfium2's {@code page.render(scale=s,
 * grayscale=True)} does: Indra's {@code document._render}.
 *
 * <p>The output is {@code ceil(width * s)} by {@code ceil(height * s)} pixels, the size
 * pypdfium2 asks pdfium for, where PDFBox's own {@code renderImage} would floor. pdfium then
 * stretches the page to fill that bitmap exactly, so the scale differs slightly between the axes;
 * this renderer gives PDFBox that same page-to-device matrix, including the page's rotation and
 * crop box as {@link PdfPage} sees them, so page features land on the same pixels. Colors become
 * gray by pdfium's weighting, {@code (30 r + 59 g + 11 b) / 100}, and text is drawn in pdfium's
 * fonts and on pdfium's pixels ({@link PdfiumStyleRenderer}).</p>
 *
 * <p>Large pages render in horizontal bands of at most {@link #BAND_PIXELS} pixels, so a poster
 * at full resolution needs one gray buffer, not four bytes per pixel more.</p>
 */
public final class PageRenderer {

    /** The most pixels drawn in color at once; larger renders go band by band. */
    static final long BAND_PIXELS = 16L << 20;

    private PageRenderer() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The width in pixels of a render at a scale: pypdfium2's {@code ceil(width * scale)}.
     *
     * @param page  The page
     * @param scale Pixels per point
     * @return The width
     */
    public static int width(PdfPage page, double scale) {
        return (int) Math.ceil(page.width() * scale);
    }

    /**
     * The height in pixels of a render at a scale: pypdfium2's {@code ceil(height * scale)}.
     *
     * @param page  The page
     * @param scale Pixels per point
     * @return The height
     */
    public static int height(PdfPage page, double scale) {
        return (int) Math.ceil(page.height() * scale);
    }

    /**
     * Renders a page in gray: {@code _render(page, scale)}.
     *
     * @param page  The page, with the rotation to render it at
     * @param scale Pixels per point, positive
     * @return The image, rows top to bottom
     * @throws IOException if PDFBox cannot draw the page
     * @throws IllegalArgumentException if the scale is not positive or the image would be empty
     *                                  or too large
     */
    public static GrayImage render(PdfPage page, double scale) throws IOException {
        Objects.requireNonNull(page, "page must not be null");
        if (!(scale > 0) || Double.isInfinite(scale)) {
            throw new IllegalArgumentException("scale must be positive and finite, got " + scale);
        }
        int w = width(page, scale);
        int h = height(page, scale);
        if (w < 1 || h < 1 || (long) w * h > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("a " + page.width() + " x " + page.height()
                + " pt page at " + scale + " px/pt makes an image of " + w + " x " + h);
        }
        AffineTransform device = pdfiumDevice(page, w, h);
        AffineTransform toPdfbox = pdfboxDeviceInverse(page.pdPage());
        PDFRenderer renderer = new PdfiumStyleRenderer(page.document());
        byte[] gray = new byte[w * h];
        int band = (int) Math.max(1, Math.min(h, BAND_PIXELS / w));
        for (int y0 = 0; y0 < h; y0 += band) {
            int rows = Math.min(band, h - y0);
            drawBand(renderer, page.index(), device, toPdfbox, w, y0, rows, gray);
        }
        return GrayImage.of(w, h, gray);
    }

    private static void drawBand(PDFRenderer renderer, int index, AffineTransform device,
                                 AffineTransform toPdfbox, int w, int y0, int rows, byte[] gray)
        throws IOException {
        BufferedImage rgb = new BufferedImage(w, rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setBackground(Color.WHITE);
            g.clearRect(0, 0, w, rows);
            AffineTransform t = AffineTransform.getTranslateInstance(0, -y0);
            t.concatenate(device);
            t.concatenate(toPdfbox);
            g.setTransform(t);
            renderer.renderPageToGraphics(index, g, 1f, 1f);
        } finally {
            g.dispose();
        }
        int[] px = ((DataBufferInt) rgb.getRaster().getDataBuffer()).getData();
        int offset = y0 * w;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF;
            int gr = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            gray[offset + i] = (byte) ((r * 30 + gr * 59 + b * 11) / 100);
        }
    }

    /**
     * pdfium's page-to-bitmap matrix for a {@code w} by {@code h} bitmap, as Java2D applies it.
     */
    private static AffineTransform pdfiumDevice(PdfPage page, int w, int h) {
        FxMatrix m = page.displayMatrix(w, h);
        return new AffineTransform(m.a(), m.b(), m.c(), m.d(), m.e(), m.f());
    }

    /**
     * The inverse of the transform PDFBox puts between its graphics and PDF space when it is
     * asked to render a page at scale 1 ({@code PDFRenderer.transform} then
     * {@code PageDrawer.drawPage}), so that the graphics transform set before the call is the
     * whole page-to-device map.
     */
    private static AffineTransform pdfboxDeviceInverse(PDPage page) throws IOException {
        PDRectangle crop = page.getCropBox();
        int rotation = page.getRotation();
        AffineTransform t = new AffineTransform();
        if (rotation != 0) {
            float tx = 0;
            float ty = 0;
            switch (rotation) {
                case 90 -> tx = crop.getHeight();
                case 270 -> ty = crop.getWidth();
                case 180 -> {
                    tx = crop.getWidth();
                    ty = crop.getHeight();
                }
                default -> {
                }
            }
            t.translate(tx, ty);
            t.rotate(Math.toRadians(rotation));
        }
        t.translate(0, crop.getHeight());
        t.scale(1, -1);
        t.translate(-crop.getLowerLeftX(), -crop.getLowerLeftY());
        try {
            return t.createInverse();
        } catch (NoninvertibleTransformException e) {
            throw new IOException("PDFBox's page transform for a " + crop + " crop box cannot be "
                + "inverted", e);
        }
    }
}
