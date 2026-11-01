package com.eonmux.excalibur.ocr.runtime;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.eonmux.excalibur.ocr.array.NdArray;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One ONNX model, loaded into an ONNX Runtime session on the CPU, run on {@link NdArray}s.
 *
 * <p>Sessions are configured as Indra OCR configures its own: {@code threads} threads within an
 * operator and one across operators, every graph optimisation, and no CPU memory arena - with
 * line batches of changing shape an arena only grows, and buys no speed.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: ONNX Runtime runs one session from many threads at once.</p>
 */
public final class OnnxModel implements AutoCloseable {

    private final String name;
    private final OrtSession session;
    private final Set<String> inputNames;
    private final Set<String> outputNames;

    private OnnxModel(String name, OrtSession session) throws OrtException {
        this.name = name;
        this.session = session;
        this.inputNames = Set.copyOf(new LinkedHashSet<>(session.getInputNames()));
        this.outputNames = Set.copyOf(new LinkedHashSet<>(session.getOutputNames()));
    }

    /**
     * Loads a model.
     *
     * @param file    The {@code .onnx} file
     * @param threads Threads one operator may use, at least 1
     * @return The model
     * @throws IOException if the file cannot be loaded as a model
     */
    public static OnnxModel load(Path file, int threads) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1, got " + threads);
        }
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(threads);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setCPUArenaAllocator(false);
            return new OnnxModel(String.valueOf(file.getFileName()),
                env.createSession(file.toString(), options));
        } catch (OrtException e) {
            throw new IOException("Could not load the model " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * The model's file name, for messages.
     *
     * @return The name
     */
    public String name() {
        return name;
    }

    /**
     * The names of the model's inputs.
     *
     * @return The names
     */
    public Set<String> inputNames() {
        return inputNames;
    }

    /**
     * The names of the model's outputs.
     *
     * @return The names
     */
    public Set<String> outputNames() {
        return outputNames;
    }

    /**
     * Runs the model.
     *
     * @param inputs  Arrays by input name: {@code f4}, {@code i8}, {@code i4} or {@code b1}
     * @param outputs The outputs wanted, by name; empty for every output, in the model's order
     * @return The outputs, by name, in the order asked for
     * @throws ModelRunException if the model rejects the inputs or fails to run
     */
    public Map<String, NdArray> run(Map<String, NdArray> inputs, List<String> outputs) {
        Objects.requireNonNull(inputs, "inputs must not be null");
        Objects.requireNonNull(outputs, "outputs must not be null");
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        Map<String, OnnxTensor> tensors = new HashMap<>();
        try {
            for (Map.Entry<String, NdArray> input : inputs.entrySet()) {
                tensors.put(input.getKey(), toTensor(env, input.getValue()));
            }
            Set<String> wanted = outputs.isEmpty() ? outputNames : new LinkedHashSet<>(outputs);
            try (OrtSession.Result result = session.run(tensors, wanted)) {
                Map<String, NdArray> out = new LinkedHashMap<>();
                List<String> order = outputs.isEmpty() ? orderedOutputs() : outputs;
                for (String output : order) {
                    OnnxValue value = result.get(output).orElseThrow(() ->
                        new ModelRunException(name + " did not return " + output));
                    out.put(output, fromTensor((OnnxTensor) value));
                }
                return out;
            }
        } catch (OrtException e) {
            throw new ModelRunException("Running " + name + " failed: " + e.getMessage(), e);
        } finally {
            tensors.values().forEach(OnnxTensor::close);
        }
    }

    /**
     * Runs the model and returns every output in the model's order.
     *
     * @param inputs Arrays by input name
     * @return The outputs
     */
    public List<NdArray> run(Map<String, NdArray> inputs) {
        return new ArrayList<>(run(inputs, List.of()).values());
    }

    private List<String> orderedOutputs() throws OrtException {
        return new ArrayList<>(session.getOutputInfo().keySet());
    }

    private static OnnxTensor toTensor(OrtEnvironment env, NdArray array) throws OrtException {
        long[] shape = toLongs(array.shape());
        return switch (array.dtype()) {
            case "f4" -> OnnxTensor.createTensor(env, FloatBuffer.wrap(array.floats()), shape);
            case "i8" -> OnnxTensor.createTensor(env, LongBuffer.wrap(array.longs()), shape);
            case "i4" -> {
                long[] values = array.longs();
                int[] ints = new int[values.length];
                for (int i = 0; i < values.length; i++) {
                    ints[i] = Math.toIntExact(values[i]);
                }
                yield OnnxTensor.createTensor(env, IntBuffer.wrap(ints), shape);
            }
            case "b1" -> {
                boolean[] values = array.booleans();
                ByteBuffer bytes = ByteBuffer.allocateDirect(values.length)
                    .order(ByteOrder.nativeOrder());
                for (boolean v : values) {
                    bytes.put((byte) (v ? 1 : 0));
                }
                bytes.flip();
                yield OnnxTensor.createTensor(env, bytes, shape, OnnxJavaType.BOOL);
            }
            default -> throw new IllegalArgumentException("a " + array.dtype()
                + " array is not a model input this feeds");
        };
    }

    private NdArray fromTensor(OnnxTensor tensor) {
        TensorInfo info = tensor.getInfo();
        int[] shape = toInts(info.getShape());
        int count = NdArray.size(shape);
        return switch (info.type) {
            case FLOAT -> {
                float[] values = new float[count];
                tensor.getFloatBuffer().get(values);
                yield new NdArray("f4", shape, values);
            }
            case INT64 -> {
                long[] values = new long[count];
                tensor.getLongBuffer().get(values);
                yield new NdArray("i8", shape, values);
            }
            case INT32 -> {
                int[] ints = new int[count];
                tensor.getIntBuffer().get(ints);
                long[] values = new long[count];
                for (int i = 0; i < count; i++) {
                    values[i] = ints[i];
                }
                yield new NdArray("i4", shape, values);
            }
            case BOOL -> {
                ByteBuffer bytes = tensor.getByteBuffer();
                boolean[] values = new boolean[count];
                for (int i = 0; i < count; i++) {
                    values[i] = bytes.get(i) != 0;
                }
                yield new NdArray("b1", shape, values);
            }
            default -> throw new ModelRunException(name + " returned a " + info.type
                + " tensor, which this does not read");
        };
    }

    private static long[] toLongs(int[] shape) {
        long[] out = new long[shape.length];
        for (int i = 0; i < shape.length; i++) {
            out[i] = shape[i];
        }
        return out;
    }

    private static int[] toInts(long[] shape) {
        int[] out = new int[shape.length];
        for (int i = 0; i < shape.length; i++) {
            out[i] = Math.toIntExact(shape[i]);
        }
        return out;
    }

    /**
     * The names and shapes of the model's inputs, as the model declares them, for messages and
     * for code that adapts to an export (a dimension the model leaves open is -1).
     *
     * @return The declared shapes, by input name
     */
    public Map<String, long[]> inputShapes() {
        try {
            Map<String, long[]> shapes = new LinkedHashMap<>();
            for (Map.Entry<String, NodeInfo> input : session.getInputInfo().entrySet()) {
                shapes.put(input.getKey(), ((TensorInfo) input.getValue().getInfo()).getShape());
            }
            return shapes;
        } catch (OrtException e) {
            throw new ModelRunException("Reading " + name + "'s inputs failed: " + e.getMessage(),
                e);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            throw new ModelRunException("Closing " + name + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return "OnnxModel[" + name + "]";
    }
}
