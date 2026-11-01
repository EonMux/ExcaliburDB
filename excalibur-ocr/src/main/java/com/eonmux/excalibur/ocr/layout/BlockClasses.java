// Ported from Indra OCR 1.1.2, order_post.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.layout;

import java.util.List;
import java.util.Set;

/**
 * The block classes the order model's layout head gives every line: Indra's
 * {@code BLOCK_CLASSES} and {@code GRID_LABELS}.
 */
public final class BlockClasses {

    /** The classes, in the order of the head's logits. */
    public static final List<String> LABELS = List.of("Text", "Section-Header", "Page-Header",
        "Page-Footer", "List-Group", "Table", "Caption", "Footnote", "Table-Of-Contents", "Form");

    /** The classes whose blocks are laid out as a grid of cells. */
    public static final Set<String> GRID = Set.of("Table", "Form", "Table-Of-Contents");

    /** How many classes there are. */
    public static final int COUNT = LABELS.size();

    static final int SECTION = index("Section-Header");
    static final int HEADER = index("Page-Header");
    static final int FOOTER = index("Page-Footer");
    static final int LIST = index("List-Group");
    static final int TABLE = index("Table");

    private BlockClasses() {
        throw new AssertionError("Static methods only");
    }

    /**
     * A class's index among the logits.
     *
     * @param label The class
     * @return Its index
     * @throws IllegalArgumentException if there is no such class
     */
    public static int index(String label) {
        int i = LABELS.indexOf(label);
        if (i < 0) {
            throw new IllegalArgumentException("no block class " + label);
        }
        return i;
    }
}
