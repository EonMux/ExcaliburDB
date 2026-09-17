package io.excalibur.metrics;

import javax.management.AttributeNotFoundException;
import javax.management.MBeanAttributeInfo;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The read-only attribute surface of one metrics MBean: each attribute's JMX description
 * bound to the accessor that answers it.
 *
 * <h2>Why the descriptor and the reader are one thing</h2>
 * <p>A {@code DynamicMBean} has to state its attributes twice - once as {@code MBeanInfo} for
 * a console to discover, once as a lookup in {@code getAttribute} to answer a read - and
 * nothing in the JMX API makes the two agree. Declared apart they drift: an attribute
 * advertised but not readable is a console showing a permanent error on a metric, and an
 * attribute readable but not advertised is a metric no dashboard can find. Binding the
 * descriptor to its reader at the point of declaration makes both lists the same list.</p>
 *
 * <h2>Ordering</h2>
 * <p>Declaration order is preserved and is the order {@code MBeanInfo} publishes, so a console
 * lists a bean's attributes in the order they were written rather than in hash order, and two
 * builds of the same bean describe themselves identically.</p>
 *
 * <h2>Thread-Safety</h2>
 * <p>Immutable once built and safe to read from any number of scrape threads. The readers it
 * holds are accessors on the concurrent metric state itself; this class adds no locking of its
 * own and takes none away.</p>
 *
 * @author ExcaliburDB
 */
final class MetricAttributeTable {

    /**
     * One attribute: what JMX is told about it, and what answers it.
     *
     * @param info   The descriptor published in {@code MBeanInfo}
     * @param reader The accessor evaluated on each read
     */
    private record Entry(MBeanAttributeInfo info, Supplier<Object> reader) {
    }

    private final Map<String, Entry> entries;
    private final MBeanAttributeInfo[] descriptors;

    /**
     * @param entries Attributes by name, in declaration order, already unmodifiable
     */
    private MetricAttributeTable(Map<String, Entry> entries) {
        this.entries = entries;
        this.descriptors = new MBeanAttributeInfo[entries.size()];
        int index = 0;
        for (Entry entry : entries.values()) {
            this.descriptors[index++] = entry.info();
        }
    }

    /**
     * Starts declaring a table.
     *
     * @return A fresh builder
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * The descriptors to publish in {@code MBeanInfo}.
     *
     * <p>Returned directly rather than copied because {@code MBeanInfo} clones what it is
     * given; the array never escapes to a caller that could write to it.</p>
     *
     * @return Attribute descriptors in declaration order
     */
    MBeanAttributeInfo[] descriptors() {
        return descriptors;
    }

    /**
     * The attribute names this table answers.
     *
     * @return An unmodifiable set of attribute names
     */
    Set<String> names() {
        return entries.keySet();
    }

    /**
     * Reads one attribute.
     *
     * @param name The attribute to read
     * @return Its current value
     * @throws AttributeNotFoundException if this table declares no such attribute
     */
    Object read(String name) throws AttributeNotFoundException {
        Entry entry = entries.get(name);
        if (entry == null) {
            throw new AttributeNotFoundException("Unknown attribute: " + name);
        }
        return entry.reader().get();
    }

    /**
     * Collects attribute declarations in the order they are written.
     */
    static final class Builder {

        private final Map<String, Entry> entries = new LinkedHashMap<>();

        /**
         * Starts an empty declaration.
         */
        private Builder() {
        }

        /**
         * Declares one readable, non-writable attribute.
         *
         * @param name        The attribute name, as a console and a dashboard spell it
         * @param type        The Java type name JMX publishes, such as {@code long}
         * @param description What it measures, in one line
         * @param reader      The accessor evaluated on each read
         * @return This builder
         */
        Builder read(String name, String type, String description, Supplier<Object> reader) {
            entries.put(name,
                new Entry(new MBeanAttributeInfo(name, type, description, true, false, false),
                    reader));
            return this;
        }

        /**
         * Freezes the declarations into a table.
         *
         * @return The immutable table
         */
        MetricAttributeTable build() {
            return new MetricAttributeTable(Collections.unmodifiableMap(entries));
        }
    }
}
