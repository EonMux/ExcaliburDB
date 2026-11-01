package com.eonmux.excalibur.ocr.pdf;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.contentstream.operator.OperatorProcessor;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;

import java.io.IOException;
import java.util.List;

/**
 * {@code Tf} as pdfium reads it ({@code CPDF_StreamContentParser::Handle_SetFont}): the size is
 * the last operand, the font the one before it, and a font missing from the resources, or not
 * named at all, is pdfium's stock Helvetica. PDFBox's own {@code Tf} ignores an operator with
 * too few operands and keeps the previous font, so the text that follows would be read and drawn
 * in another font than pdfium's.
 */
final class PdfiumSetFont extends OperatorProcessor {

    private PDFont stock;

    PdfiumSetFont(PDFStreamEngine context) {
        super(context);
    }

    @Override
    public void process(Operator operator, List<COSBase> arguments) throws IOException {
        int n = arguments.size();
        PDTextState ts = getContext().getGraphicsState().getTextState();
        ts.setFontSize(n > 0 && arguments.get(n - 1) instanceof COSNumber size
            ? size.floatValue() : 0);
        PDFont font = n > 1 && arguments.get(n - 2) instanceof COSName name
            ? getContext().getResources().getFont(name) : null;
        if (font == null) {
            if (stock == null) {
                stock = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            }
            font = stock;
        }
        ts.setFont(font);
    }

    @Override
    public String getName() {
        return OperatorName.SET_FONT_AND_SIZE;
    }
}
