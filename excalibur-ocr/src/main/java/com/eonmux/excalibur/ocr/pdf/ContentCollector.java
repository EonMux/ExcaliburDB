package com.eonmux.excalibur.ocr.pdf;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequence;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequenceWithProperties;
import org.apache.pdfbox.contentstream.operator.markedcontent.EndMarkedContentSequence;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetLineWidth;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.contentstream.operator.text.BeginText;
import org.apache.pdfbox.contentstream.operator.text.EndText;
import org.apache.pdfbox.contentstream.operator.text.MoveText;
import org.apache.pdfbox.contentstream.operator.text.MoveTextSetLeading;
import org.apache.pdfbox.contentstream.operator.text.NextLine;
import org.apache.pdfbox.contentstream.operator.text.SetCharSpacing;
import org.apache.pdfbox.contentstream.operator.text.SetTextHorizontalScaling;
import org.apache.pdfbox.contentstream.operator.text.SetTextLeading;
import org.apache.pdfbox.contentstream.operator.text.SetTextRenderingMode;
import org.apache.pdfbox.contentstream.operator.text.SetTextRise;
import org.apache.pdfbox.contentstream.operator.text.SetWordSpacing;
import org.apache.pdfbox.contentstream.operator.text.ShowText;
import org.apache.pdfbox.contentstream.operator.text.ShowTextAdjusted;
import org.apache.pdfbox.contentstream.operator.text.ShowTextLine;
import org.apache.pdfbox.contentstream.operator.text.ShowTextLineAndSpace;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a page's content streams into the objects pdfium's text page works from: one
 * {@link TextObject} per text-showing operator and one {@link ContentItem.Form} per form drawn,
 * in drawing order, with each form's objects in its own coordinates.
 *
 * <p>PDFBox runs the content and keeps the graphics and text state; at each text operator this
 * collector takes that state and lays the characters out itself with pdfium's widths, so that
 * positions agree with pdfium's to the rounding of its integer widths. Glyph procedures of Type 3
 * fonts and soft masks are not run: pdfium's text page does not look into them either.</p>
 */
final class ContentCollector extends PDFStreamEngine {

    /**
     * Stands for the missing properties of a {@code BMC} mark, so that two such marks compare
     * equal as pdfium's null parameters do.
     */
    static final COSDictionary NO_PROPERTIES = new COSDictionary();

    private final Map<COSDictionary, PdfFont> fonts = new IdentityHashMap<>();
    private final Deque<Frame> frames = new ArrayDeque<>();

    /** One content stream being run: the page's, or a form's. */
    private record Frame(List<ContentItem> items, FxMatrix formMatrix, FxMatrix toForm,
                         int level, Deque<COSDictionary> marks) {
    }

    private ContentCollector() {
        addOperator(new BeginText(this));
        addOperator(new Concatenate(this));
        addOperator(new DrawObject(this));
        addOperator(new EndText(this));
        addOperator(new SetGraphicsStateParameters(this));
        addOperator(new Save(this));
        addOperator(new Restore(this));
        addOperator(new NextLine(this));
        addOperator(new SetCharSpacing(this));
        addOperator(new MoveText(this));
        addOperator(new MoveTextSetLeading(this));
        addOperator(new PdfiumSetFont(this));
        addOperator(new ShowText(this));
        addOperator(new ShowTextAdjusted(this));
        addOperator(new SetTextLeading(this));
        addOperator(new SetMatrix(this));
        addOperator(new SetTextRenderingMode(this));
        addOperator(new SetTextRise(this));
        addOperator(new SetWordSpacing(this));
        addOperator(new SetTextHorizontalScaling(this));
        addOperator(new ShowTextLine(this));
        addOperator(new ShowTextLineAndSpace(this));
        addOperator(new SetLineWidth(this));
        addOperator(new BeginMarkedContentSequence(this));
        addOperator(new BeginMarkedContentSequenceWithProperties(this));
        addOperator(new EndMarkedContentSequence(this));
    }

    /**
     * The text objects and forms of a page.
     *
     * @param page The page
     * @return Its top-level items, in drawing order
     * @throws IOException if the content cannot be read
     */
    static List<ContentItem> collect(PDPage page) throws IOException {
        ContentCollector collector = new ContentCollector();
        List<ContentItem> items = new ArrayList<>();
        collector.frames.push(new Frame(items, FxMatrix.IDENTITY, FxMatrix.IDENTITY, 0,
            new ArrayDeque<>()));
        collector.processPage(page);
        return List.copyOf(items);
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        inFrame(form, () -> super.showForm(form));
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
        inFrame(form, () -> super.showTransparencyGroup(form));
    }

    private interface Body {
        void run() throws IOException;
    }

    /**
     * Runs a form's content in a frame of its own, then adds the form to its parent. As in
     * pdfium, the form's objects are kept in its own space, which starts at the form's
     * {@code /Matrix} ({@code CPDF_ContentParser} sets it as the form's initial CTM), and the
     * form's matrix to the page is the CTM where it was drawn ({@code CPDF_FormObject}'s
     * {@code form_matrix() * outer}). The split matters where pdfium compares the boxes of
     * objects from different forms, each in its own space, to decide where a line ends.
     */
    private void inFrame(PDFormXObject form, Body body) throws IOException {
        Frame parent = frames.peek();
        FxMatrix toPage = fx(getGraphicsState().getCurrentTransformationMatrix());
        List<ContentItem> items = new ArrayList<>();
        frames.push(new Frame(items, toPage, toPage.inverse(), parent.level() + 1,
            new ArrayDeque<>()));
        try {
            body.run();
        } finally {
            frames.pop();
        }
        parent.items().add(new ContentItem.Form(items, parent.level() + 1));
    }

    @Override
    public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
        frames.peek().marks().addLast(properties == null ? NO_PROPERTIES : properties);
    }

    @Override
    public void endMarkedContentSequence() {
        Deque<COSDictionary> marks = frames.peek().marks();
        if (!marks.isEmpty()) {
            marks.removeLast();
        }
    }

    @Override
    public void showTextString(byte[] string) throws IOException {
        if (string.length > 0) {
            record(List.of(string), new float[] {0}, 0);
        }
        super.showTextString(string);
    }

    /**
     * Records a {@code TJ} array as one object, as pdfium's
     * {@code Handle_ShowText_Positioning}: numbers before the first non-empty string shift the
     * start, and numbers after a string adjust the space after its last character.
     */
    @Override
    public void showTextStrings(COSArray array) throws IOException {
        List<byte[]> strings = new ArrayList<>();
        List<Float> after = new ArrayList<>();
        float initial = 0;
        for (int i = 0; i < array.size(); i++) {
            COSBase item = array.getObject(i);
            if (item instanceof COSString s && s.getBytes().length > 0) {
                strings.add(s.getBytes());
                after.add(0f);
            } else if (item instanceof COSNumber n) {
                if (strings.isEmpty()) {
                    initial += n.floatValue();
                } else {
                    after.set(after.size() - 1, after.getLast() + n.floatValue());
                }
            }
        }
        if (!strings.isEmpty()) {
            float[] kernings = new float[after.size()];
            for (int i = 0; i < kernings.length; i++) {
                kernings[i] = after.get(i);
            }
            record(strings, kernings, initial);
        }
        super.showTextStrings(array);
    }

    @Override
    protected void showFontGlyph(Matrix trm, PDFont font, int code, Vector displacement) {
        // The characters were taken from the operator as a whole; PDFBox only moves the text
        // matrix past them.
    }

    @Override
    protected void showType3Glyph(Matrix trm, PDType3Font font, int code, Vector displacement) {
        // A Type 3 glyph's procedure draws the glyph; it holds no text of the page's.
    }

    /** Adds the text object of one operator: pdfium's {@code AddTextObject}. */
    private void record(List<byte[]> strings, float[] segmentKerning, float initial)
        throws IOException {
        PDTextState ts = getGraphicsState().getTextState();
        if (ts.getFont() == null) {
            return;
        }
        PdfFont font = font(ts.getFont());
        List<int[]> decoded = new ArrayList<>();
        List<Float> kernings = new ArrayList<>();
        for (int s = 0; s < strings.size(); s++) {
            for (int[] code : font.decode(strings.get(s))) {
                decoded.add(code);
                kernings.add(0f);
            }
            if (s != strings.size() - 1 && !kernings.isEmpty()) {
                kernings.set(kernings.size() - 1, segmentKerning[s]);
            }
        }
        if (decoded.isEmpty()) {
            return;
        }
        int[] codes = new int[decoded.size()];
        int[] lengths = new int[codes.length];
        float[] kern = new float[codes.length];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = decoded.get(i)[0];
            lengths[i] = decoded.get(i)[1];
            kern[i] = kernings.get(i);
        }
        frames.peek().items().add(new TextObject(state(font, ts, initial), codes, lengths, kern));
    }

    private TextObject.TextState state(PdfFont font, PDTextState ts, float initial) {
        Frame frame = frames.peek();
        float size = ts.getFontSize();
        float scale = ts.getHorizontalScaling() / 100f;
        FxMatrix ctm = fx(getGraphicsState().getCurrentTransformationMatrix())
            .times(frame.toForm());
        FxMatrix text = fx(getGraphicsState().getTextMatrix()).times(ctm);
        float dx = 0;
        float dy = ts.getRise();
        if (initial != 0) {
            if (font.verticalWriting()) {
                dy -= initial * size / 1000;
            } else {
                dx -= initial * size / 1000 * scale;
            }
        }
        FxPoint pos = text.apply(new FxPoint(dx, dy));
        FxMatrix m = new FxMatrix(scale, 0, 0, 1, 0, 0).times(text);
        int mode = font.isType3() ? 0 : ts.getRenderingMode().intValue();
        return new TextObject.TextState(font, size, ts.getCharacterSpacing(),
            ts.getWordSpacing(), new FxMatrix(m.a(), m.b(), m.c(), m.d(), pos.x(), pos.y()),
            frame.formMatrix(), mode, getGraphicsState().getLineWidth(),
            new ArrayList<>(frame.marks()));
    }

    private PdfFont font(PDFont font) {
        return fonts.computeIfAbsent(font.getCOSObject(), d -> new PdfFont(font));
    }

    static FxMatrix fx(Matrix m) {
        return new FxMatrix(m.getScaleX(), m.getShearY(), m.getShearX(), m.getScaleY(),
            m.getTranslateX(), m.getTranslateY());
    }
}
