package com.eonmux.excalibur.ocr.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.io.IOException;

/**
 * A PDFBox renderer that draws text where pdfium draws it.
 *
 * <p>It reads {@code Tf} as pdfium does ({@link PdfiumSetFont}), and places small filled glyphs on
 * whole pixels.</p>
 *
 * <p>pdfium draws a glyph whose em is at most 50 device pixels as a FreeType bitmap set at its
 * origin rounded to the nearest pixel ({@code CFX_RenderDevice::DrawNormalText}), so the baseline
 * of small text lies on a pixel edge and its strokes stay dark. PDFBox fills every glyph outline
 * at its exact position, which spreads a stroke a pixel thin over two rows at half the darkness.
 * At 72 dpi that difference decides whether text 1.5 to 2 points high leaves any pixel darker
 * than {@link TextLayers#DARK}, and at OCR resolutions it changes how sharp small print is.
 * Shifting each small glyph to pdfium's pixel origin before PDFBox fills it gives the same
 * placement; larger glyphs, stroked text and pattern fills are drawn as paths by pdfium too and
 * are left as they are.</p>
 */
final class PdfiumStyleRenderer extends PDFRenderer {

    /** The em size in device pixels above which pdfium draws glyphs as paths. */
    static final double PATH_TEXT_PIXELS = 50.0;

    PdfiumStyleRenderer(PDDocument document) {
        super(document);
    }

    @Override
    protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
        PageDrawer drawer = new GridDrawer(parameters);
        drawer.setAnnotationFilter(getAnnotationsFilter());
        return drawer;
    }

    /** The page drawer that reads {@code Tf} as pdfium and snaps glyph origins. */
    private static final class GridDrawer extends PageDrawer {

        GridDrawer(PageDrawerParameters parameters) throws IOException {
            super(parameters);
            addOperator(new PdfiumSetFont(this));
        }

        @Override
        protected void showFontGlyph(Matrix textRenderingMatrix, PDFont font, int code,
                                     Vector displacement) throws IOException {
            super.showFontGlyph(snapped(textRenderingMatrix), font, code, displacement);
        }

        /** The glyph's matrix moved so its origin falls on pdfium's pixel, when pdfium would. */
        private Matrix snapped(Matrix trm) {
            if (!drawnAsBitmap()) {
                return trm;
            }
            AffineTransform device = getGraphics().getTransform();
            AffineTransform glyph = trm.createAffineTransform();
            glyph.preConcatenate(device);
            if (Math.abs(glyph.getScaleX()) + Math.abs(glyph.getShearY()) > PATH_TEXT_PIXELS) {
                return trm;
            }
            Point2D origin = device.transform(
                new Point2D.Double(trm.getTranslateX(), trm.getTranslateY()), null);
            double dx = roundHalfAway(origin.getX()) - origin.getX();
            double dy = roundHalfAway(origin.getY()) - origin.getY();
            try {
                Point2D shift = device.createInverse()
                    .deltaTransform(new Point2D.Double(dx, dy), null);
                AffineTransform moved = trm.createAffineTransform();
                moved.preConcatenate(
                    AffineTransform.getTranslateInstance(shift.getX(), shift.getY()));
                return new Matrix(moved);
            } catch (NoninvertibleTransformException e) {
                return trm;
            }
        }

        /** Whether pdfium would draw the current text as glyph bitmaps: filled, not a pattern. */
        private boolean drawnAsBitmap() {
            PDGraphicsState state = getGraphicsState();
            RenderingMode mode = state.getTextState().getRenderingMode();
            return (mode == RenderingMode.FILL || mode == RenderingMode.FILL_CLIP)
                && !(state.getNonStrokingColorSpace() instanceof PDPattern);
        }

        /** {@code FXSYS_roundf}: the nearest integer, halves away from zero. */
        private static double roundHalfAway(double v) {
            return Math.copySign(Math.floor(Math.abs(v) + 0.5), v);
        }
    }
}
