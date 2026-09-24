package com.eonmux.excalibur.graph.browse;

import java.util.List;
import java.util.Optional;

/**
 * One page of a catalog listing, and where the next one starts.
 *
 * @param items      What this page holds, in listing order
 * @param nextCursor The cursor that continues after the last item, or null when nothing follows
 * @param <T>        What is listed
 */
public record CatalogPage<T>(List<T> items, String nextCursor) {

    public CatalogPage {
        items = List.copyOf(items);
    }

    /**
     * The cursor for the next page.
     *
     * @return It, or empty when this is the last page
     */
    public Optional<String> next() {
        return Optional.ofNullable(nextCursor);
    }
}
