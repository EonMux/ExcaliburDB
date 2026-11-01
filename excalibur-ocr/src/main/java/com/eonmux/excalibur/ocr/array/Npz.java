package com.eonmux.excalibur.ocr.array;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads numpy's {@code .npz} archives and {@code .npy} arrays.
 *
 * <p>An {@code .npz} is a zip archive of {@code .npy} files, stored or deflated. An {@code .npy}
 * is a magic string, a version, a header naming the element type, the order and the shape as a
 * Python dict literal, and then the values. Read here: format versions 1 to 3, C order, and the
 * element types {@code f4}, {@code f8}, {@code i1} to {@code i8}, {@code u1} to {@code u8} and
 * {@code b1}, in either byte order. Anything else - Fortran order, object arrays, strings - is
 * refused rather than misread.</p>
 */
public final class Npz {

    private static final byte[] MAGIC = {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y'};

    private static final Pattern DESCR = Pattern.compile("'descr'\\s*:\\s*'([<>|=])([a-zA-Z])(\\d+)'");
    private static final Pattern FORTRAN = Pattern.compile("'fortran_order'\\s*:\\s*(True|False)");
    private static final Pattern SHAPE = Pattern.compile("'shape'\\s*:\\s*\\(([^)]*)\\)");

    private Npz() {
        throw new AssertionError("Static methods only");
    }

    /**
     * Reads every array of an {@code .npz} file.
     *
     * @param file The archive
     * @return Its arrays by name, without the {@code .npy} suffix, in archive order
     * @throws IOException if the file cannot be read or holds an array this cannot read
     */
    public static Map<String, NdArray> read(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        try (InputStream in = Files.newInputStream(file)) {
            return read(in, file.toString());
        }
    }

    /**
     * Reads every array of an {@code .npz} archive from a stream.
     *
     * @param in   The archive's bytes
     * @param name What to call it in messages
     * @return Its arrays by name, without the {@code .npy} suffix, in archive order
     * @throws IOException if the stream cannot be read or holds an array this cannot read
     */
    public static Map<String, NdArray> read(InputStream in, String name) throws IOException {
        Map<String, NdArray> arrays = new LinkedHashMap<>();
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            String key = entry.getName();
            if (!key.endsWith(".npy")) {
                continue;
            }
            key = key.substring(0, key.length() - 4);
            arrays.put(key, readNpy(zip.readAllBytes(), name + ":" + key));
        }
        if (arrays.isEmpty()) {
            throw new IOException(name + " holds no .npy arrays");
        }
        return arrays;
    }

    /**
     * Reads one {@code .npy} array.
     *
     * @param bytes The file's bytes
     * @param name  What to call it in messages
     * @return The array
     * @throws IOException if it is not an array this can read
     */
    public static NdArray readNpy(byte[] bytes, String name) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (byte b : MAGIC) {
            if (!buf.hasRemaining() || buf.get() != b) {
                throw new IOException(name + " is not a .npy array");
            }
        }
        int major = buf.get() & 0xFF;
        buf.get();
        int headerLength = switch (major) {
            case 1 -> buf.getShort() & 0xFFFF;
            case 2, 3 -> buf.getInt();
            default -> throw new IOException(name + " is .npy format " + major
                + ", which this does not read");
        };
        String header = new String(bytes, buf.position(), headerLength,
            major == 3 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
        buf.position(buf.position() + headerLength);

        Matcher descr = DESCR.matcher(header);
        Matcher fortran = FORTRAN.matcher(header);
        Matcher shapeMatch = SHAPE.matcher(header);
        if (!descr.find() || !fortran.find() || !shapeMatch.find()) {
            throw new IOException(name + " has a header this cannot read: " + header.strip());
        }
        if (fortran.group(1).equals("True")) {
            throw new IOException(name + " is in Fortran order, which this does not read");
        }
        int[] shape = parseShape(shapeMatch.group(1), name);
        ByteOrder order = descr.group(1).equals(">") ? ByteOrder.BIG_ENDIAN
            : ByteOrder.LITTLE_ENDIAN;
        char kind = descr.group(2).charAt(0);
        int width = Integer.parseInt(descr.group(3));
        if (!supported(kind, width)) {
            throw new IOException(name + " holds " + kind + width + " values, which this does "
                + "not read");
        }
        ByteBuffer values = buf.slice().order(order);
        int count = NdArray.size(shape);
        if ((long) count * width > values.remaining()) {
            throw new IOException(name + " is truncated: " + count + " values of " + width
                + " bytes need " + ((long) count * width) + ", it holds " + values.remaining());
        }
        String dtype = "" + kind + width;
        return new NdArray(dtype, shape, decode(values, kind, width, count));
    }

    private static boolean supported(char kind, int width) {
        return switch (kind) {
            case 'f' -> width == 4 || width == 8;
            case 'i', 'u' -> width == 1 || width == 2 || width == 4 || width == 8;
            case 'b' -> width == 1;
            default -> false;
        };
    }

    private static Object decode(ByteBuffer values, char kind, int width, int count) {
        if (kind == 'f' && width == 4) {
            float[] out = new float[count];
            values.asFloatBuffer().get(out);
            return out;
        }
        if (kind == 'f') {
            double[] out = new double[count];
            values.asDoubleBuffer().get(out);
            return out;
        }
        if (kind == 'b') {
            boolean[] out = new boolean[count];
            for (int i = 0; i < count; i++) {
                out[i] = values.get() != 0;
            }
            return out;
        }
        long[] out = new long[count];
        boolean unsigned = kind == 'u';
        for (int i = 0; i < count; i++) {
            out[i] = switch (width) {
                case 1 -> unsigned ? values.get() & 0xFFL : values.get();
                case 2 -> unsigned ? values.getShort() & 0xFFFFL : values.getShort();
                case 4 -> unsigned ? values.getInt() & 0xFFFFFFFFL : values.getInt();
                default -> values.getLong();
            };
        }
        return out;
    }

    private static int[] parseShape(String text, String name) throws IOException {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return new int[0];
        }
        String[] parts = trimmed.split(",");
        int n = 0;
        int[] shape = new int[parts.length];
        for (String part : parts) {
            String p = part.strip();
            if (p.isEmpty()) {
                continue;
            }
            try {
                shape[n++] = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                throw new IOException(name + " has a shape this cannot read: (" + text + ")", e);
            }
        }
        int[] exact = new int[n];
        System.arraycopy(shape, 0, exact, 0, n);
        return exact;
    }

    /**
     * Reads an {@code .npz} archive held in memory, such as a test fixture.
     *
     * @param bytes The archive
     * @param name  What to call it in messages
     * @return Its arrays by name
     * @throws IOException if it is not an archive of arrays this can read
     */
    public static Map<String, NdArray> read(byte[] bytes, String name) throws IOException {
        return read(new ByteArrayInputStream(bytes), name);
    }
}
