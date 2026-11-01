package com.eonmux.excalibur.ocr.testing;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.array.Npz;
import com.eonmux.excalibur.ocr.image.GrayImage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Test fixtures generated from Indra OCR's Python implementation by the scripts in
 * {@code excalibur-ocr/tools/fixtures}, read from the test classpath.
 */
public final class Fixtures {

    private Fixtures() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The arrays of a fixture archive.
     *
     * @param path The archive under {@code fixtures/}, such as {@code foundation/rot90.npz}
     * @return Its arrays by name
     */
    public static Map<String, NdArray> npz(String path) {
        try (InputStream in = open(path)) {
            return Npz.read(in, path);
        } catch (IOException e) {
            throw new UncheckedIOException("Reading fixture " + path + " failed", e);
        }
    }

    /**
     * A text fixture, such as expected output or a JSON document.
     *
     * @param path The file under {@code fixtures/}
     * @return Its text, read as UTF-8
     */
    public static String text(String path) {
        try (InputStream in = open(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Reading fixture " + path + " failed", e);
        }
    }

    /**
     * A binary fixture, such as a PDF.
     *
     * @param path The file under {@code fixtures/}
     * @return Its bytes
     */
    public static byte[] bytes(String path) {
        try (InputStream in = open(path)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Reading fixture " + path + " failed", e);
        }
    }

    /**
     * A {@code uint8} array of shape {@code [H, W]} as an image.
     *
     * @param array The array
     * @return The image
     */
    public static GrayImage image(NdArray array) {
        int[] shape = array.shape();
        if (shape.length != 2) {
            throw new IllegalArgumentException("an image is [H, W], got " + array);
        }
        long[] values = array.longs();
        byte[] pixels = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            pixels[i] = (byte) values[i];
        }
        return GrayImage.of(shape[1], shape[0], pixels);
    }

    private static InputStream open(String path) {
        InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + path);
        if (in == null) {
            throw new IllegalArgumentException("No fixture " + path + " on the test classpath; "
                + "generate it with the scripts in excalibur-ocr/tools/fixtures");
        }
        return in;
    }
}
