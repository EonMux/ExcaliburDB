package io.excalibur.core.memory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Field;

/**
 * VarHandle-based utility class for accessing object fields via reflection.
 *
 * <p>This class provides VarHandle access to object fields, which is useful
 * for atomic operations on heap-based objects. For MemorySegment atomic
 * operations, use {@link io.excalibur.core.util.SegmentVarHandle} or
 * {@link io.excalibur.core.atomic.AtomicOps}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>VarHandles created by this class support atomic operations when used
 * with the appropriate access modes (e.g., compareAndSet, getAndAdd).</p>
 *
 * @author ExcaliburDB
 * @see io.excalibur.core.util.SegmentVarHandle
 * @see io.excalibur.core.atomic.AtomicOps
 */
public final class ReflectionVarHandle {

    private ReflectionVarHandle() {
        // Utility class - prevent instantiation
    }

    /**
     * Gets a VarHandle for a field in the given class.
     *
     * <p>This method uses reflection to obtain a VarHandle for accessing
     * object fields. The VarHandle can then be used for atomic operations
     * like compareAndSet, getAndAdd, etc.</p>
     *
     * @param targetClass The class containing the field
     * @param fieldName The name of the field
     * @return A VarHandle for the field
     * @throws RuntimeException if the VarHandle cannot be created
     */
    public static VarHandle getVarHandle(Class<?> targetClass, String fieldName) {
        if (targetClass == null) {
            throw new NullPointerException("Target class cannot be null");
        }
        if (fieldName == null) {
            throw new NullPointerException("Field name cannot be null");
        }
        try {
            Field field = targetClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            return MethodHandles.lookup().unreflectVarHandle(field);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to create VarHandle for " + targetClass.getName() + "." + fieldName, e);
        }
    }
}
