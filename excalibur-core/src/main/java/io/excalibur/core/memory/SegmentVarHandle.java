package io.excalibur.core.memory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

/**
 * VarHandle-based atomic operations on MemorySegment.
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are thread-safe. Use VarHandle for atomic operations
 * on primitive types stored in MemorySegment.</p>
 *
 * @author ExcaliburDB
 */
public final class SegmentVarHandle {

    private SegmentVarHandle() {
        // Utility class - prevent instantiation
    }

    /**
     * Gets a VarHandle for a field in the given class.
     *
     * @param targetClass The class containing the field
     * @param fieldName The name of the field
     * @return A VarHandle for the field
     * @throws RuntimeException if the VarHandle cannot be created
     */
    public static VarHandle getVarHandle(Class<?> targetClass, String fieldName) {
        try {
            Field field = targetClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            return MethodHandles.lookup().unreflectVarHandle(field);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to create VarHandle for " + targetClass.getName() + "." + fieldName, e);
        }
    }
}
