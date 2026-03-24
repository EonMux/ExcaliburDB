package io.excalibur.core.memory;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * Adapter that wraps a Segment to implement the deprecated MemorySegment interface.
 * This provides backward compatibility for code that still uses MemorySegment.
 *
 * @author ExcaliburDB
 * @deprecated This is an internal adapter for backward compatibility only.
 */
@Deprecated(since = "1.1", forRemoval = true)
class MemorySegmentAdapter implements MemorySegment {

    private final Segment delegate;

    MemorySegmentAdapter(Segment delegate) {
        this.delegate = delegate;
    }

    @Override
    public long byteSize() {
        return delegate.byteSize();
    }

    @Override
    public Segment asSlice(long offset, long length) {
        return delegate.asSlice(offset, length);
    }

    @Override
    public byte getByte(long offset) {
        return delegate.getByte(offset);
    }

    @Override
    public void setByte(long offset, byte value) {
        delegate.setByte(offset, value);
    }

    @Override
    public byte[] getBytes(long offset, int length) {
        return delegate.getBytes(offset, length);
    }

    @Override
    public void setBytes(long offset, byte[] src, int srcOffset, int length) {
        delegate.setBytes(offset, src, srcOffset, length);
    }

    @Override
    public int getInt(long offset) {
        return delegate.getInt(offset);
    }

    @Override
    public void setInt(long offset, int value) {
        delegate.setInt(offset, value);
    }

    @Override
    public long getLong(long offset) {
        return delegate.getLong(offset);
    }

    @Override
    public void setLong(long offset, long value) {
        delegate.setLong(offset, value);
    }

    @Override
    public float getFloat(long offset) {
        return delegate.getFloat(offset);
    }

    @Override
    public void setFloat(long offset, float value) {
        delegate.setFloat(offset, value);
    }

    @Override
    public double getDouble(long offset) {
        return delegate.getDouble(offset);
    }

    @Override
    public void setDouble(long offset, double value) {
        delegate.setDouble(offset, value);
    }

    @Override
    public void copyTo(long srcOffset, byte[] dst, int dstOffset, int length) {
        delegate.copyTo(srcOffset, dst, dstOffset, length);
    }

    @Override
    public void copyFrom(byte[] src, int srcOffset, long dstOffset, int length) {
        delegate.copyFrom(src, srcOffset, dstOffset, length);
    }

    @Override
    public void copyTo(long srcOffset, Segment dst, long dstOffset, long length) {
        delegate.copyTo(srcOffset, dst, dstOffset, length);
    }

    @Override
    public void copyFrom(Segment src, long srcOffset, long dstOffset, long length) {
        delegate.copyFrom(src, srcOffset, dstOffset, length);
    }

    @Override
    public void read(long offset, ByteBuffer buffer, int length) {
        delegate.read(offset, buffer, length);
    }

    @Override
    public void write(long offset, ByteBuffer buffer) {
        delegate.write(offset, buffer);
    }

    @Override
    public void force() {
        delegate.force();
    }

    @Override
    public FileChannel toChannel() {
        return delegate.toChannel();
    }

    @Override
    public Path path() {
        return delegate.path();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public java.lang.foreign.MemorySegment nativeSegment() {
        return delegate.nativeSegment();
    }

    @Override
    public boolean isPanama() {
        return delegate.isPanama();
    }

    @Override
    public String toString() {
        return "MemorySegmentAdapter{delegate=" + delegate + "}";
    }
}
