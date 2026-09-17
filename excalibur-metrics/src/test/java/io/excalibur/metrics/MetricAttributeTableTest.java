package io.excalibur.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.management.AttributeNotFoundException;
import javax.management.MBeanAttributeInfo;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the table every metrics MBean answers its reads from.
 *
 * <p>The table exists so that what a bean advertises and what it can actually read are one
 * list rather than two that agree by inspection. These tests hold it to that: the descriptors
 * and the readable names have to be the same set, in the same order, and a read has to reach
 * the live accessor rather than a value captured when the bean was built.</p>
 *
 * @author ExcaliburDB
 */
class MetricAttributeTableTest {

    @Test
    @DisplayName("advertises exactly the attributes it can read, in declaration order")
    void advertisesWhatItCanRead() {
        MetricAttributeTable table = MetricAttributeTable.builder()
            .read("depth", "long", "How deep", () -> 1L)
            .read("ready", "boolean", "Whether ready", () -> true)
            .read("rate", "double", "How fast", () -> 2.5)
            .build();

        List<String> advertised = List.of(table.descriptors()).stream()
            .map(MBeanAttributeInfo::getName).toList();
        assertEquals(List.of("depth", "ready", "rate"), advertised,
            "descriptors should keep declaration order so a console lists them predictably");
        assertEquals(advertised.size(), table.names().size(),
            "every advertised attribute should be a readable one");
        assertTrue(table.names().containsAll(advertised));
    }

    @Test
    @DisplayName("publishes each attribute as readable and not writable")
    void publishesReadOnlyDescriptors() {
        MetricAttributeTable table = MetricAttributeTable.builder()
            .read("depth", "long", "How deep", () -> 1L)
            .build();

        MBeanAttributeInfo info = table.descriptors()[0];
        assertEquals("depth", info.getName());
        assertEquals("long", info.getType());
        assertEquals("How deep", info.getDescription());
        assertTrue(info.isReadable());
        assertFalse(info.isWritable(),
            "a measurement a console can overwrite is not a measurement");
        assertFalse(info.isIs());
    }

    @Test
    @DisplayName("reads through to the accessor on every read, not once at build time")
    void readsAreLive() throws Exception {
        AtomicLong source = new AtomicLong(1);
        MetricAttributeTable table = MetricAttributeTable.builder()
            .read("depth", "long", "How deep", source::get)
            .build();

        assertEquals(1L, table.read("depth"));
        source.set(42);
        assertEquals(42L, table.read("depth"),
            "a scrape must see the current value, not the one the bean was built with");
    }

    @Test
    @DisplayName("names an attribute it does not have rather than returning null for it")
    void unknownAttributeIsRefused() {
        MetricAttributeTable table = MetricAttributeTable.builder()
            .read("depth", "long", "How deep", () -> 1L)
            .build();

        AttributeNotFoundException thrown =
            assertThrows(AttributeNotFoundException.class, () -> table.read("nope"));
        assertTrue(thrown.getMessage().contains("nope"),
            "the message should name the attribute the caller asked for");
    }

    @Test
    @DisplayName("hands out a name set a caller cannot edit")
    void namesAreUnmodifiable() {
        MetricAttributeTable table = MetricAttributeTable.builder()
            .read("depth", "long", "How deep", () -> 1L)
            .build();

        assertThrows(UnsupportedOperationException.class, () -> table.names().add("extra"));
    }
}
