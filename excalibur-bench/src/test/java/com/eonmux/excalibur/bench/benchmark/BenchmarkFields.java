package com.eonmux.excalibur.bench.benchmark;

import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflective access to benchmark state for the validation tests in this package.
 *
 * <p>JMH owns a benchmark's state in a real run, so the fields the tests inspect are private
 * and stay that way; the tests reach them the way the harness would, without widening the
 * benchmarks' surface for their sake.</p>
 *
 * @author ExcaliburDB
 */
final class BenchmarkFields {

    private BenchmarkFields() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reads a declared field of any visibility.
     *
     * @param target The object holding the field
     * @param name   The field name
     * @param <T>    The expected field type
     * @return The field's value
     */
    @SuppressWarnings("unchecked")
    static <T> T get(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no readable field '" + name + "' on "
                + target.getClass().getSimpleName(), e);
        }
    }

    /**
     * How many operations JMH divides one invocation of a benchmark method into.
     *
     * @param benchmark The benchmark class
     * @param method    The benchmark method, taking a single parameter of {@code parameter}
     * @param parameter The method's parameter type
     * @return The {@link OperationsPerInvocation} value, or 1 when the method has none
     */
    static int operationsPerInvocation(Class<?> benchmark, String method, Class<?> parameter) {
        try {
            Method m = benchmark.getMethod(method, parameter);
            OperationsPerInvocation ops = m.getAnnotation(OperationsPerInvocation.class);
            return ops == null ? 1 : ops.value();
        } catch (NoSuchMethodException e) {
            throw new AssertionError("no benchmark method " + method, e);
        }
    }

    /**
     * A Blackhole usable outside a JMH run, for benchmark methods that consume into one.
     *
     * @return A new Blackhole
     */
    static Blackhole blackhole() {
        return new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
    }
}
