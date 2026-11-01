package com.eonmux.excalibur.ocr.pdf;

import java.util.List;

/**
 * A page object that pdfium's text page visits: a text object, or a form holding more objects.
 * Other objects (paths, images) play no part in the text layer and are not kept.
 */
sealed interface ContentItem permits TextObject, ContentItem.Form {

    /**
     * A form XObject drawn on the page or inside another form.
     *
     * @param items Its text objects and forms, in drawing order
     * @param level How deep it is nested: 1 for a form drawn by the page itself
     */
    record Form(List<ContentItem> items, int level) implements ContentItem {
        public Form {
            items = List.copyOf(items);
        }
    }
}
