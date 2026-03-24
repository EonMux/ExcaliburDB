package io.excalibur.core.memory;

import java.nio.ByteOrder;

/**
 * Layout descriptor for primitive types in memory segments.
 * This abstraction provides a unified API for accessing primitive values
 * regardless of the underlying implementation (Panama FFM or ByteBuffer fallback).
 *
 * <h2>Thread Safety</h2>
 * <p>Instances are immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class SegmentLayout {

    /**
     * Byte layout (1 byte).
     */
    public static final SegmentLayout BYTE = new SegmentLayout(1, Byte.class, ByteOrder.nativeOrder());

    /**
     * Int layout (4 bytes, big-endian).
     */
    public static final SegmentLayout INT = new SegmentLayout(4, Integer.class, ByteOrder.BIG_ENDIAN);

    /**
     * Int layout (4 bytes, unaligned, big-endian).
     */
    public static final SegmentLayout INT_UNALIGNED = new SegmentLayout(4, Integer.class, ByteOrder.BIG_ENDIAN);

    /**
     * Long layout (8 bytes, big-endian).
     */
    public static final SegmentLayout LONG = new SegmentLayout(8, Long.class, ByteOrder.BIG_ENDIAN);

    /**
     * Long layout (8 bytes, unaligned, big-endian).
     */
    public static final SegmentLayout LONG_UNALIGNED = new SegmentLayout(8, Long.class, ByteOrder.BIG_ENDIAN);

    /**
     * Float layout (4 bytes, big-endian).
     */
    public static final SegmentLayout FLOAT = new SegmentLayout(4, Float.class, ByteOrder.BIG_ENDIAN);

    /**
     * Float layout (4 bytes, unaligned, big-endian).
     */
    public static final SegmentLayout FLOAT_UNALIGNED = new SegmentLayout(4, Float.class, ByteOrder.BIG_ENDIAN);

    /**
     * Double layout (8 bytes, big-endian).
     */
    public static final SegmentLayout DOUBLE = new SegmentLayout(8, Double.class, ByteOrder.BIG_ENDIAN);

    /**
     * Double layout (8 bytes, unaligned, big-endian).
     */
    public static final SegmentLayout DOUBLE_UNALIGNED = new SegmentLayout(8, Double.class, ByteOrder.BIG_ENDIAN);

    private final int byteSize;
    private final Class<?> carrier;
    private final ByteOrder byteOrder;

    private SegmentLayout(int byteSize, Class<?> carrier, ByteOrder byteOrder) {
        this.byteSize = byteSize;
        this.carrier = carrier;
        this.byteOrder = byteOrder;
    }

    /**
     * Returns the size in bytes of this layout.
     *
     * @return The byte size
     */
    public int byteSize() {
        return byteSize;
    }

    /**
     * Returns the carrier type for this layout.
     *
     * @return The carrier class
     */
    public Class<?> carrier() {
        return carrier;
    }

    /**
     * Returns the byte order for this layout.
     *
     * @return The byte order
     */
    public ByteOrder byteOrder() {
        return byteOrder;
    }

    /**
     * Returns a new layout with the specified byte order.
     *
     * @param order The new byte order
     * @return A new layout with the specified order
     */
    public SegmentLayout withOrder(ByteOrder order) {
        return new SegmentLayout(byteSize, carrier, order);
    }

    @Override
    public String toString() {
        return String.format("SegmentLayout[size=%d, carrier=%s, order=%s]",
            byteSize, carrier.getSimpleName(), byteOrder);
    }
}
