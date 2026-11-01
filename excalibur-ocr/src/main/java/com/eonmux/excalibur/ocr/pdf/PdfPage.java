// Ported from Indra OCR 1.1.2, textlayer.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pdf;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * One page of a PDF as pdfium presents it to Indra OCR: its visible box, its rotation, its size
 * as rendered, and the map from PDF space to the rendered frame.
 *
 * <p>Indra reads pages through pypdfium2, so its coordinates and render sizes follow pdfium's
 * rules rather than PDFBox's. The visible box is the crop box clipped to the media box (the media
 * box alone when the crop box is missing or empty; US Letter when the media box is too), both
 * looked up through the page tree. The rotation is {@code /Rotate} divided by 90, so a value that
 * is not a multiple of 90 rounds toward zero. Sizes are single precision, as pdfium returns them.</p>
 *
 * <p>Immutable. Indra turns a page with {@code page.set_rotation(0)} and back; here
 * {@link #withRotation(int)} returns a view with another rotation and leaves the document alone.
 * The view is only as thread-safe as its {@link PDDocument}, which is to say one thread at a
 * time.</p>
 */
public final class PdfPage {

    private static final FxRect LETTER = new FxRect(0, 0, 612, 792);

    private final PDDocument document;
    private final int index;
    private final PDPage page;
    private final FxRect box;
    private final int quarter;

    private PdfPage(PDDocument document, int index, PDPage page, FxRect box, int quarter) {
        this.document = document;
        this.index = index;
        this.page = page;
        this.box = box;
        this.quarter = quarter;
    }

    /**
     * A page of an open document, with the rotation its dictionary gives.
     *
     * @param document The document
     * @param index    The page, from 0
     * @return The page
     * @throws IndexOutOfBoundsException if the document has no such page
     */
    public static PdfPage of(PDDocument document, int index) {
        Objects.requireNonNull(document, "document must not be null");
        Objects.checkIndex(index, document.getNumberOfPages());
        PDPage page = document.getPage(index);
        COSDictionary dict = page.getCOSObject();
        return new PdfPage(document, index, page, visibleBox(dict), quarterTurns(dict));
    }

    /**
     * pdfium's {@code CPDF_Page::UpdateDimensions} box: the crop box clipped to the media box.
     */
    private static FxRect visibleBox(COSDictionary dict) {
        FxRect media = box(inherited(dict, COSName.MEDIA_BOX));
        if (media.isEmpty()) {
            media = LETTER;
        }
        FxRect crop = box(inherited(dict, COSName.CROP_BOX));
        return crop.isEmpty() ? media : crop.intersect(media);
    }

    private static FxRect box(COSBase value) {
        if (!(value instanceof COSArray array) || array.size() != 4) {
            return FxRect.EMPTY;
        }
        float[] v = new float[4];
        for (int i = 0; i < 4; i++) {
            v[i] = array.getObject(i) instanceof COSNumber n ? n.floatValue() : 0f;
        }
        return new FxRect(v[0], v[1], v[2], v[3]).normalized();
    }

    /** pdfium's {@code GetPageRotation}: {@code /Rotate / 90 % 4}, made non-negative. */
    private static int quarterTurns(COSDictionary dict) {
        COSBase rotate = inherited(dict, COSName.ROTATE);
        int value = rotate instanceof COSNumber n ? n.intValue() : 0;
        int q = (value / 90) % 4;
        return q < 0 ? q + 4 : q;
    }

    /** An attribute of the page or of its nearest ancestor that has it: {@code GetPageAttr}. */
    private static COSBase inherited(COSDictionary dict, COSName key) {
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        COSDictionary node = dict;
        while (node != null && seen.add(node)) {
            COSBase value = node.getDictionaryObject(key);
            if (value != null) {
                return value;
            }
            node = node.getCOSDictionary(COSName.PARENT);
        }
        return null;
    }

    /**
     * The same page with another rotation: pypdfium2's {@code set_rotation}.
     *
     * @param degrees Clockwise rotation: 0, 90, 180 or 270
     * @return The rotated view
     * @throws IllegalArgumentException for any other angle
     */
    public PdfPage withRotation(int degrees) {
        if (degrees != 0 && degrees != 90 && degrees != 180 && degrees != 270) {
            throw new IllegalArgumentException("a page turns by 0, 90, 180 or 270 degrees, not "
                + degrees);
        }
        return degrees / 90 == quarter ? this
            : new PdfPage(document, index, page, box, degrees / 90);
    }

    /**
     * The clockwise rotation: pypdfium2's {@code get_rotation}.
     *
     * @return 0, 90, 180 or 270
     */
    public int rotation() {
        return quarter * 90;
    }

    /**
     * The rendered width in points, crop box and rotation applied: {@code get_size()[0]}.
     *
     * @return The width, a single-precision value
     */
    public double width() {
        return widthF();
    }

    /**
     * The rendered height in points: {@code get_size()[1]}.
     *
     * @return The height, a single-precision value
     */
    public double height() {
        return heightF();
    }

    float widthF() {
        return quarter % 2 == 0 ? box.width() : box.height();
    }

    float heightF() {
        return quarter % 2 == 0 ? box.height() : box.width();
    }

    /**
     * The document the page belongs to, which must stay open while the page is read.
     *
     * @return The document
     */
    public PDDocument document() {
        return document;
    }

    /**
     * The page's position in its document.
     *
     * @return The zero-based index
     */
    public int index() {
        return index;
    }

    PDPage pdPage() {
        return page;
    }

    /**
     * pdfium's page matrix: PDF space to the rotated box, origin at its lower left.
     */
    FxMatrix pageMatrix() {
        return switch (quarter) {
            case 1 -> new FxMatrix(0, -1, 1, 0, -box.bottom(), box.right());
            case 2 -> new FxMatrix(-1, 0, 0, -1, box.right(), box.top());
            case 3 -> new FxMatrix(0, 1, -1, 0, box.top(), -box.left());
            default -> new FxMatrix(1, 0, 0, 1, -box.left(), -box.bottom());
        };
    }

    /**
     * PDF space to a device rectangle {@code (0, 0)-(width, height)}, y down: pdfium's
     * {@code GetDisplayMatrixForRect} with no extra rotation.
     *
     * @param width  Device width
     * @param height Device height
     * @return The map, or the identity for a page with no area
     */
    FxMatrix displayMatrix(float width, float height) {
        float w = widthF();
        float h = heightF();
        if (w == 0 || h == 0) {
            return FxMatrix.IDENTITY;
        }
        return pageMatrix().times(new FxMatrix(width / w, 0, 0, -height / h, 0, height));
    }

    /**
     * The map pdfium's text page sorts objects by: {@code GetDisplayMatrix}.
     */
    FxMatrix displayMatrix() {
        return displayMatrix(widthF(), heightF());
    }

    /**
     * The map from PDF page space to the rendered frame, in points with a top-left origin, read
     * from pdfium's {@code FPDF_PageToDevice} at 1000 device units per point: Indra's
     * {@code _device_affine}.
     *
     * @return The map
     */
    public Affine deviceAffine() {
        double s = 1000;
        float w = (float) Math.rint(width() * s);
        float h = (float) Math.rint(height() * s);
        FxMatrix m = displayMatrix(w, h);
        double[] o = device(m, 0, 0);
        double[] ex = device(m, 1, 0);
        double[] ey = device(m, 0, 1);
        return new Affine((ex[0] - o[0]) / s, (ey[0] - o[0]) / s, o[0] / s,
            (ex[1] - o[1]) / s, (ey[1] - o[1]) / s, o[1] / s);
    }

    /** One point to integer device units, rounded half away from zero as {@code FXSYS_roundf}. */
    private static double[] device(FxMatrix m, float x, float y) {
        FxPoint p = m.apply(new FxPoint(x, y));
        return new double[] {roundf(p.x()), roundf(p.y())};
    }

    private static int roundf(float v) {
        if (Float.isNaN(v)) {
            return 0;
        }
        double r = Math.signum(v) * Math.floor(Math.abs((double) v) + 0.5);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, r));
    }

    @Override
    public String toString() {
        return "PdfPage[" + index + ", " + width() + "x" + height() + ", " + rotation() + "°]";
    }
}
