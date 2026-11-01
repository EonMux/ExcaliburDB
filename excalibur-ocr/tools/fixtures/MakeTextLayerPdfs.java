import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes the PDFs that the text-layer fixtures are read from: pages that exercise every branch of
 * Indra OCR's textlayer.py (columns, page rotation, crop boxes, sideways and sheared text,
 * invisible and inkless text, broken font maps, right-to-left text in either order, vertical
 * writing, tiny text, ligatures, superscripts, generated spaces and hyphens, duplicated text,
 * marked-content replacement text, glyph names from the Zapf Dingbats list and Symbol's private
 * use names, and text inside nested form XObjects). gen_textlayer.py then reads each page with Indra and stores
 * what it decides.
 *
 * <p>Run with PDFBox 3.0.8 on the class path:</p>
 * <pre>
 * java -cp pdfbox.jar:pdfbox-io.jar:fontbox.jar:commons-logging.jar \
 *     tools/fixtures/MakeTextLayerPdfs.java src/test/resources/fixtures/textlayer \
 *     DejaVuSans.ttf DroidSansFallbackFull.ttf LiberationSerif-Regular.ttf
 * </pre>
 *
 * <p>The three fonts give Latin, Hebrew and Arabic glyphs; CJK glyphs for vertical writing; and
 * the face drawn into the scanned-page image. Every embedded font is subset, so the PDFs stay
 * small.</p>
 */
public final class MakeTextLayerPdfs {

    private static final String PROSE = "The quick brown fox jumps over the lazy dog while the "
        + "five boxing wizards jump quickly and a wizard's job is to vex chumps quickly in fog "
        + "as pack my box with five dozen liquor jugs and sphinx of black quartz judge my vow";

    private final Path out;
    private final File sans;
    private final File cjk;
    private final File serif;
    private final List<PDType0Font> toSubset = new java.util.ArrayList<>();

    private MakeTextLayerPdfs(Path out, File sans, File cjk, File serif) {
        this.out = out;
        this.sans = sans;
        this.cjk = cjk;
        this.serif = serif;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: MakeTextLayerPdfs <out dir> <DejaVuSans.ttf> "
                + "<DroidSansFallbackFull.ttf> <LiberationSerif-Regular.ttf>");
        }
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        MakeTextLayerPdfs maker = new MakeTextLayerPdfs(out, new File(args[1]), new File(args[2]),
            new File(args[3]));
        maker.layout();
        maker.orient();
        maker.sideways();
        maker.hidden();
        maker.fonts();
        maker.scripts();
        maker.forms();
    }

    /** Content stream text, written operator by operator. */
    private static final class Content {
        private final StringBuilder ops = new StringBuilder();

        Content op(String format, Object... args) {
            ops.append(String.format(Locale.ROOT, format, args)).append('\n');
            return this;
        }

        Content text(String font, float size, float x, float y, byte[] codes) {
            return op("BT /%s %.2f Tf %.2f %.2f Td %s Tj ET", font, size, x, y, hex(codes));
        }

        void into(PDDocument doc, PDPage page) throws IOException {
            page.setContents(new PDStream(doc,
                new java.io.ByteArrayInputStream(ops.toString().getBytes(StandardCharsets.ISO_8859_1)),
                COSName.FLATE_DECODE));
        }
    }

    private static String hex(byte[] codes) {
        StringBuilder sb = new StringBuilder("<");
        for (byte b : codes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.append('>').toString();
    }

    /** Remembers an embedded font, to subset it when its document is saved. */
    private PDType0Font embed(PDDocument doc, PDType0Font font) {
        toSubset.add(font);
        return font;
    }

    /** Encodes text in an embedded font and adds its glyphs to the font's subset. */
    private static byte[] glyphs(PDType0Font font, String text) throws IOException {
        text.codePoints().forEach(font::addToSubset);
        return font.encode(text);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > width && line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        lines.add(line.toString());
        return lines;
    }

    private static PDPage page(PDDocument doc, PDResources res, float w, float h) {
        PDPage page = new PDPage(new PDRectangle(w, h));
        page.setResources(res);
        doc.addPage(page);
        return page;
    }

    private static PDResources standardFonts() {
        PDResources res = new PDResources();
        res.put(COSName.getPDFName("F1"), new PDType1Font(FontName.TIMES_ROMAN));
        res.put(COSName.getPDFName("F2"), new PDType1Font(FontName.HELVETICA));
        res.put(COSName.getPDFName("F3"), new PDType1Font(FontName.HELVETICA_BOLD));
        return res;
    }

    private void save(PDDocument doc, String name) throws IOException {
        for (PDType0Font font : toSubset) {
            font.subset();
        }
        toSubset.clear();
        doc.save(out.resolve(name).toFile());
        doc.close();
    }

    /** Columns, hyphenation, superscripts, tiny text, ligatures, a blank page, glyph placement. */
    private void layout() throws IOException {
        PDDocument doc = new PDDocument();
        PDResources res = standardFonts();
        PDType0Font dejavu = embed(doc, PDType0Font.load(doc, sans));
        res.put(COSName.getPDFName("F4"), dejavu);
        res.put(COSName.getPDFName("F5"), ligatureFont());

        Content c = new Content();
        c.text("F3", 18, 72, 720, ascii("A Single Column Page"));
        float y = 690;
        for (String line : wrap(PROSE + " " + PROSE + " " + PROSE, 80)) {
            c.text("F1", 11, 72, y, ascii(line));
            y -= 14;
        }
        c.text("F1", 11, 72, y, ascii("the last word of this line is broken by a hyphen, as in exam-"));
        c.text("F1", 11, 72, y - 14, ascii("ple, and the line goes on after it as usual."));
        // "7 Tf" without a font name is malformed on purpose: pdfium sets its stock Helvetica.
        c.op("BT /F1 11 Tf 72 %.2f Td (Water is H) Tj -2 Ts 7 Tf (2) Tj 0 Ts 11 Tf (O and energy is mc) Tj"
            + " 4 Ts 7 Tf (2) Tj 0 Ts 11 Tf (, as footnote) Tj 5 Ts 7 Tf (12) Tj 0 Ts 11 Tf ( says.) Tj ET",
            y - 42);
        c.into(doc, page(doc, res, 612, 792));

        c = new Content();
        List<String> left = wrap(PROSE + " " + PROSE, 45);
        for (int i = 0; i < left.size(); i++) {
            StringBuilder tj = new StringBuilder("[");
            for (String word : left.get(i).split(" ")) {
                tj.append('(').append(word.replace("'", "\\'")).append(") -280 ");
            }
            c.op("BT /F2 9 Tf 54 %.2f Td %s] TJ ET", 740f - 12 * i, tj);
        }
        List<String> right = wrap(PROSE + " " + PROSE, 45);
        for (int i = 0; i < right.size(); i++) {
            c.text("F2", 9, 320, 740 - 12 * i, ascii(right.get(i)));
        }
        c.into(doc, page(doc, res, 612, 792));

        c = new Content();
        c.text("F1", 3, 72, 700, ascii("this line is set in three point type and is hard to see"));
        c.text("F1", 1.5f, 72, 690, ascii("this line is set at one and a half points"));
        c.text("F5", 14, 72, 650, new byte[] {'o', (byte) 0x80, 'c', 'e', ' ', (byte) 0x81, 'o', 'w'});
        c.text("F4", 14, 72, 620, glyphs(dejavu, "eﬃcient ﬁnal ﬂow ﬀort"));
        c.text("F4", 12, 72, 590, glyphs(dejavu, "naïve café Über æsthetic"));
        c.into(doc, page(doc, res, 612, 792));

        c = new Content();
        c.op("0.2 g 72 600 200 100 re f 0 G 2 w 100 100 m 500 500 l S");
        c.into(doc, page(doc, res, 612, 792));

        c = new Content();
        float x = 72;
        for (char ch : "Each glyph here is its own object".toCharArray()) {
            if (ch != ' ') {
                c.text("F2", 12, x, 700, new byte[] {(byte) ch});
            }
            x += ch == ' ' ? 4.5f : 7f;
        }
        for (String line : wrap(PROSE, 60)) {
            c.op("0.25 0 0 rg");
            c.text("F3", 12, 72, 660, ascii(line));
            c.text("F3", 12, 72.3f, 660, ascii(line));
            break;
        }
        c.op("0 g /Span <</ActualText (replacement text) >> BDC");
        c.text("F2", 12, 72, 620, ascii("XQZXQZXQZ"));
        c.op("EMC");
        c.text("F2", 12, 72, 600, ascii("after the span"));
        c.into(doc, page(doc, res, 612, 792));
        save(doc, "layout.pdf");
    }

    /** A Helvetica whose codes 0x80 and 0x81 name the fi and fl ligatures. */
    private static PDFont ligatureFont() throws IOException {
        COSDictionary dict = new COSDictionary();
        dict.setItem(COSName.TYPE, COSName.FONT);
        dict.setItem(COSName.SUBTYPE, COSName.TYPE1);
        dict.setName(COSName.BASE_FONT, "Helvetica");
        COSDictionary enc = new COSDictionary();
        enc.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
        COSArray diffs = new COSArray();
        diffs.add(COSInteger.get(0x80));
        diffs.add(COSName.getPDFName("fi"));
        diffs.add(COSName.getPDFName("fl"));
        enc.setItem(COSName.DIFFERENCES, diffs);
        dict.setItem(COSName.ENCODING, enc);
        return new PDType1Font(dict);
    }

    /** Page rotation and crop boxes. */
    private void orient() throws IOException {
        PDDocument doc = new PDDocument();
        PDResources res = standardFonts();

        PDPage p = page(doc, res, 612, 792);
        p.setRotation(90);
        prose(72, 720, 13).into(doc, p);

        p = page(doc, res, 700, 900);
        p.setCropBox(new PDRectangle(50, 60, 612, 792));
        Content c = prose(100, 800, 14);
        c.text("F2", 10, 10, 30, ascii("outside the crop box"));
        c.text("F2", 10, 670, 400, ascii("also outside"));
        c.into(doc, p);

        p = page(doc, res, 612, 792);
        p.setRotation(270);
        c = new Content();
        float y = 72;
        for (String line : wrap(PROSE + " " + PROSE, 90)) {
            c.op("BT /F1 11 Tf 0 -1 1 0 %.2f 720 Tm (%s) Tj ET", y, line.replace("'", "\\'"));
            y += 14;
        }
        c.into(doc, p);

        p = page(doc, res, 612, 792);
        p.setRotation(180);
        prose(72, 720, 13).into(doc, p);
        save(doc, "orient.pdf");
    }

    private static Content prose(float x, float y, float leading) {
        Content c = new Content();
        for (String line : wrap(PROSE + " " + PROSE, 70)) {
            c.text("F1", 11, x, y, ascii(line));
            y -= leading;
        }
        return c;
    }

    /** Text turned a quarter turn, sideways labels, and sheared text. */
    private void sideways() throws IOException {
        PDDocument doc = new PDDocument();
        PDResources res = standardFonts();

        Content c = new Content();
        float x = 560;
        String[] cells = {"Region", "North", "South", "East", "West", "Total"};
        for (int row = 0; row < 14; row++) {
            for (int col = 0; col < cells.length; col++) {
                String cell = row == 0 ? cells[col] : cells[col] + " " + (row * 17 + col * 3);
                c.op("BT /F2 10 Tf 0 1 -1 0 %.2f %.2f Tm (%s) Tj ET", x, 80f + col * 110, cell);
            }
            x -= 22;
        }
        c.text("F3", 12, 72, 40, ascii("Table 3"));
        c.into(doc, page(doc, res, 612, 792));

        c = prose(100, 720, 14);
        c.op("BT /F2 9 Tf 0 1 -1 0 60 300 Tm (vertical axis label) Tj ET");
        c.op("BT /F2 9 Tf 0 -1 1 0 560 500 Tm (turned the other way) Tj ET");
        c.op("BT /F2 9 Tf -1 0 0 -1 400 200 Tm (upside down text) Tj ET");
        c.into(doc, page(doc, res, 612, 792));

        c = new Content();
        float y = 720;
        for (String line : wrap(PROSE, 60)) {
            c.op("BT /F1 12 Tf 1 0 0.3 1 72 %.2f Tm (%s) Tj ET", y, line.replace("'", "\\'"));
            y -= 16;
        }
        c.op("BT /F1 12 Tf 0.966 0.259 -0.259 0.966 72 400 Tm (this line is turned fifteen degrees) Tj ET");
        c.text("F1", 12, 72, 300, ascii("one upright line"));
        c.into(doc, page(doc, res, 612, 792));
        save(doc, "sideways.pdf");
    }

    /** An OCR layer over a scan, inkless text, and a little invisible text. */
    private void hidden() throws IOException {
        PDDocument doc = new PDDocument();
        PDResources res = standardFonts();
        PDImageXObject scan = LosslessFactory.createFromImage(doc, scanImage());
        res.put(COSName.getPDFName("Im1"), scan);

        Content c = new Content();
        c.op("q 612 0 0 792 0 0 cm /Im1 Do Q");
        float y = 720;
        for (String line : wrap(PROSE + " " + PROSE, 70)) {
            c.op("BT 3 Tr /F1 11 Tf 72 %.2f Td (%s) Tj ET", y, line.replace("'", "\\'"));
            y -= 14;
        }
        c.into(doc, page(doc, res, 612, 792));

        c = prose(72, 720, 14);
        c.op("1 g");
        c.text("F2", 10, 72, 200, ascii("white text that leaves no ink"));
        c.text("F2", 10, 72, 180, ascii("more white text"));
        c.text("F2", 10, 300, 180, ascii("and some more"));
        c.into(doc, page(doc, res, 612, 792));

        c = prose(72, 720, 14);
        c.op("BT 3 Tr /F2 10 Tf 72 100 Td (one invisible word) Tj ET");
        c.into(doc, page(doc, res, 612, 792));
        save(doc, "hidden.pdf");
    }

    private BufferedImage scanImage() throws IOException {
        BufferedImage img = new BufferedImage(850, 1100, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 850, 1100);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        try {
            g.setFont(Font.createFont(Font.TRUETYPE_FONT, serif).deriveFont(15.3f));
        } catch (java.awt.FontFormatException e) {
            throw new IOException("Cannot load " + serif, e);
        }
        int y = 112;
        for (String line : wrap(PROSE + " " + PROSE, 70)) {
            g.drawString(line, 100, y);
            y += 19;
        }
        g.dispose();
        return img;
    }

    /** Font maps whose codes are not text. */
    private void fonts() throws IOException {
        PDDocument doc = new PDDocument();
        PDResources res = standardFonts();
        res.put(COSName.getPDFName("F6"), unnamedGlyphFont());
        PDType0Font noMap = embed(doc, PDType0Font.load(doc, sans));
        res.put(COSName.getPDFName("F7"), noMap);

        Content c = new Content();
        float y = 720;
        for (int line = 0; line < 12; line++) {
            byte[] codes = new byte[40];
            for (int i = 0; i < codes.length; i++) {
                codes[i] = (byte) (1 + (line * 7 + i * 5) % 30);
            }
            c.text("F6", 11, 72, y, codes);
            y -= 14;
        }
        c.into(doc, page(doc, res, 612, 792));

        c = new Content();
        y = 720;
        for (String line : wrap(PROSE + " " + PROSE, 60)) {
            c.text("F7", 11, 72, y, glyphs(noMap, line));
            y -= 14;
        }
        c.into(doc, page(doc, res, 612, 792));

        res.put(COSName.getPDFName("F10"), dingbatNamesFont());
        res.put(COSName.getPDFName("F11"), new PDType1Font(FontName.SYMBOL));
        c = prose(72, 720, 14);
        c.text("F6", 11, 72, 100, new byte[] {(byte) 0xE0});
        c.text("F10", 11, 72, 140, new byte[] {8, 9, 10});
        c.text("F11", 11, 72, 160, new byte[] {'a', (byte) 0xE3, 'b'});
        c.into(doc, page(doc, res, 612, 792));
        save(doc, "fonts.tmp.pdf");

        try (PDDocument saved = org.apache.pdfbox.Loader.loadPDF(out.resolve("fonts.tmp.pdf").toFile())) {
            PDResources r = saved.getPage(1).getResources();
            r.getFont(COSName.getPDFName("F7")).getCOSObject().removeItem(COSName.TO_UNICODE);
            saved.save(out.resolve("fonts.pdf").toFile());
        }
        Files.delete(out.resolve("fonts.tmp.pdf"));
    }

    /** A Helvetica whose codes 8 to 10 carry ITC Zapf Dingbats glyph names. */
    private static PDFont dingbatNamesFont() throws IOException {
        COSDictionary dict = new COSDictionary();
        dict.setItem(COSName.TYPE, COSName.FONT);
        dict.setItem(COSName.SUBTYPE, COSName.TYPE1);
        dict.setName(COSName.BASE_FONT, "Helvetica");
        COSDictionary enc = new COSDictionary();
        COSArray diffs = new COSArray();
        diffs.add(COSInteger.get(8));
        diffs.add(COSName.getPDFName("a12"));
        diffs.add(COSName.getPDFName("a1"));
        diffs.add(COSName.getPDFName("a20"));
        enc.setItem(COSName.DIFFERENCES, diffs);
        dict.setItem(COSName.ENCODING, enc);
        return new PDType1Font(dict);
    }

    /** A Helvetica whose codes name glyphs that have no Unicode value. */
    private static PDFont unnamedGlyphFont() throws IOException {
        COSDictionary dict = new COSDictionary();
        dict.setItem(COSName.TYPE, COSName.FONT);
        dict.setItem(COSName.SUBTYPE, COSName.TYPE1);
        dict.setName(COSName.BASE_FONT, "Helvetica");
        COSDictionary enc = new COSDictionary();
        COSArray diffs = new COSArray();
        diffs.add(COSInteger.get(1));
        for (int i = 1; i <= 31; i++) {
            diffs.add(COSName.getPDFName("G" + i));
        }
        diffs.add(COSInteger.get(0xE0));
        diffs.add(COSName.getPDFName("Gx"));
        enc.setItem(COSName.DIFFERENCES, diffs);
        dict.setItem(COSName.ENCODING, enc);
        return new PDType1Font(dict);
    }

    /** Right-to-left text in logical and in visual order, and vertical writing. */
    private void scripts() throws IOException {
        PDDocument doc = new PDDocument();
        PDResources res = standardFonts();
        PDType0Font dejavu = embed(doc, PDType0Font.load(doc, sans));
        res.put(COSName.getPDFName("F4"), dejavu);
        PDType0Font vertical = embed(doc, PDType0Font.loadVertical(doc, cjk));
        res.put(COSName.getPDFName("F8"), vertical);
        PDType0Font cjkHorizontal = embed(doc, PDType0Font.load(doc, cjk));
        res.put(COSName.getPDFName("F9"), cjkHorizontal);

        String[] rtl = {
            "שלום עולם זהו משפט בעברית",
            "مرحبا بالعالم هذا نص عربي",
            "השורה השלישית בדף הזה",
        };
        Content c = new Content();
        float y = 720;
        for (String line : rtl) {
            String visual = new StringBuilder(line).reverse().toString();
            c.text("F4", 14, 72, y, glyphs(dejavu, visual));
            y -= 24;
        }
        c.text("F2", 11, 72, y, ascii("A Latin line between them."));
        c.into(doc, page(doc, res, 612, 792));

        c = new Content();
        y = 720;
        for (String line : rtl) {
            c.op("BT /F4 14 Tf 400 %.2f Td %s TJ ET", y, logicalRightToLeft(dejavu, line, 14));
            y -= 24;
        }
        c.into(doc, page(doc, res, 612, 792));

        c = new Content();
        String novel = "吾輩は猫である。名前はまだ"
            + "無い。どこで生れたかとんと見"
            + "当がつかぬ。";
        float x = 520;
        for (int col = 0; col < 4; col++) {
            c.text("F8", 14, x, 740, glyphs(vertical, novel));
            x -= 24;
        }
        c.text("F9", 16, 72, 60, glyphs(cjkHorizontal, "猫の本"));
        c.into(doc, page(doc, res, 612, 792));
        save(doc, "scripts.pdf");
    }

    /**
     * Text inside form XObjects: forms nested four deep, each with its own /Matrix, invisible text
     * over the visible lines at the third and fifth levels (an OCR layer counts objects three
     * forms deep, the text page reads every level), a form turned a quarter turn, and a form
     * inside replacement text.
     */
    private void forms() throws IOException {
        PDDocument doc = new PDDocument();
        PDResources fonts = standardFonts();

        String[] words = {"one", "two", "three"};
        Content deepest = new Content();
        for (int i = 0; i < words.length; i++) {
            deepest.op("BT 3 Tr /F2 10 Tf 0 %d Td (hidden %s five forms deep) Tj ET", -12 * i,
                words[i]);
        }
        PDFormXObject level4 = form(doc, fonts, deepest, null, new AffineTransform());
        Content third = new Content()
            .op("BT 3 Tr /F2 10 Tf 0 0 Td (hidden three forms deep) Tj ET")
            .op("/Fx Do");
        PDFormXObject level3 = form(doc, fonts, third, level4, new AffineTransform());
        Content second = new Content();
        for (int i = 0; i < words.length; i++) {
            second.text("F1", 11, 0, -12 * i, ascii("line " + words[i] + " two forms deep"));
        }
        second.op("/Fx Do");
        PDFormXObject level2 = form(doc, fonts, second, level3,
            AffineTransform.getTranslateInstance(0, -40));
        Content first = new Content();
        float y = 0;
        for (String line : wrap(PROSE + " " + PROSE + " " + PROSE, 40)) {
            first.text("F1", 11, 0, y, ascii(line));
            y -= 13;
        }
        first.op("q 1 0 0 1 0 %.2f cm /Fx Do Q", y - 10);
        PDFormXObject level1 = form(doc, fonts, first, level2,
            new AffineTransform(0.9, 0, 0, 0.9, 10, 0));

        PDResources res = standardFonts();
        res.put(COSName.getPDFName("Fx"), level1);
        Content c = new Content().op("q 1 0 0 1 62 740 cm /Fx Do Q");
        c.into(doc, page(doc, res, 612, 792));

        Content label = new Content().text("F2", 10, 0, 0, ascii("a label turned in its form"));
        PDFormXObject turned = form(doc, fonts, label, null,
            new AffineTransform(0, 1, -1, 0, 0, 0));
        Content span = new Content().text("F2", 12, 0, 0, ascii("XQZXQZ"));
        PDFormXObject replaced = form(doc, fonts, span, null, new AffineTransform());
        res = standardFonts();
        res.put(COSName.getPDFName("Fx"), turned);
        res.put(COSName.getPDFName("Fy"), replaced);
        c = prose(100, 720, 14);
        c.op("q 1 0 0 1 60 400 cm /Fx Do Q");
        c.op("/Span <</ActualText (text from a form) >> BDC q 1 0 0 1 100 200 cm /Fy Do Q EMC");
        c.text("F2", 12, 100, 180, ascii("after the form span"));
        c.into(doc, page(doc, res, 612, 792));
        save(doc, "forms.pdf");
    }

    /** A form XObject drawing {@code content}, with {@code inner} as its /Fx when not null. */
    private static PDFormXObject form(PDDocument doc, PDResources fonts, Content content,
                                      PDFormXObject inner, AffineTransform matrix)
        throws IOException {
        PDFormXObject form = new PDFormXObject(doc);
        form.setBBox(new PDRectangle(-1000, -1000, 3000, 3000));
        form.setMatrix(matrix);
        PDResources res = new PDResources();
        for (COSName name : fonts.getFontNames()) {
            res.put(name, fonts.getFont(name));
        }
        if (inner != null) {
            res.put(COSName.getPDFName("Fx"), inner);
        }
        form.setResources(res);
        try (java.io.OutputStream os = form.getContentStream().createOutputStream()) {
            os.write(content.ops.toString().getBytes(StandardCharsets.ISO_8859_1));
        }
        return form;
    }

    /**
     * A TJ array that draws a line's characters in logical order from right to left: each glyph
     * is followed by an adjustment that moves back over it and over the next one.
     */
    private static String logicalRightToLeft(PDType0Font font, String line, float size)
        throws IOException {
        StringBuilder tj = new StringBuilder("[");
        int[] cps = line.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            byte[] code = glyphs(font, new String(Character.toChars(cps[i])));
            float w = font.getWidth(font.readCode(new java.io.ByteArrayInputStream(code)));
            float next = 0;
            if (i + 1 < cps.length) {
                byte[] nc = glyphs(font, new String(Character.toChars(cps[i + 1])));
                next = font.getWidth(font.readCode(new java.io.ByteArrayInputStream(nc)));
            }
            tj.append(hex(code)).append(' ');
            tj.append(String.format(Locale.ROOT, "%.1f ", w + next));
        }
        return tj.append(']').toString();
    }
}
