import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Writes scan.pdf, the image-only PDF DocumentParityTest reads: three pages of synthetic printed
 * text with no text layer - upright, tilted a few degrees, and turned a quarter turn. Each page
 * image is exactly the page's size at 200 dpi, so a renderer at that resolution copies it pixel
 * for pixel and every reader sees the same image.
 *
 * <p>Run with PDFBox 3.0.8 on the class path:
 * {@code java -cp <pdfbox jars> MakeScanPdfs.java <out dir>}</p>
 */
public final class MakeScanPdfs {

    private static final int W = 1700;
    private static final int H = 2200;

    private static final String[] TITLE = {"Field Notes on River Sediment"};
    private static final String[] BODY = {
        "Samples were taken at four stations along the lower reach,",
        "each at a depth of two metres, over three days in spring.",
        "Fine silt dominated the slow inner bends, while coarse sand",
        "and gravel lined the faster outer banks of the channel.",
        "1. Station A: mostly silt, with traces of organic matter.",
        "2. Station B: medium sand, well sorted, little clay.",
        "3. Station C: gravel bars exposed at low water.",
        "The results suggest that deposition follows flow speed",
        "closely, as expected for a meandering lowland river.",
    };

    public static void main(String[] args) throws IOException {
        File out = new File(args[0], "scan.pdf");
        try (PDDocument doc = new PDDocument()) {
            add(doc, page(0.0, 0));
            add(doc, page(2.5, 0));
            add(doc, page(0.0, 1));
            doc.save(out);
        }
    }

    /** A page of text tilted by degrees, or turned a quarter turn counter-clockwise. */
    private static BufferedImage page(double degrees, int quarters) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        if (quarters == 1) {
            // The lines run up the page, the first one on the left.
            g.translate(170, H - 300);
            g.rotate(-Math.PI / 2);
        } else {
            g.rotate(Math.toRadians(-degrees), W / 2.0, H / 2.0);
            g.translate(170, 160);
        }
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SERIF, Font.BOLD, 64));
        int y = 100;
        for (String line : TITLE) {
            g.drawString(line, 0, y);
            y += 140;
        }
        g.setFont(new Font(Font.SERIF, Font.PLAIN, 40));
        for (String line : BODY) {
            g.drawString(line, 0, y);
            y += 70;
        }
        g.dispose();
        return image;
    }

    private static void add(PDDocument doc, BufferedImage image) throws IOException {
        PDRectangle size = new PDRectangle(W * 72f / 200f, H * 72f / 200f);
        PDPage page = new PDPage(size);
        doc.addPage(page);
        PDImageXObject x = LosslessFactory.createFromImage(doc, image);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(x, 0, 0, size.getWidth(), size.getHeight());
        }
    }
}
