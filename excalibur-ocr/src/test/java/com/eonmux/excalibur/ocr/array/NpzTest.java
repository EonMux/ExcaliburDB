package com.eonmux.excalibur.ocr.array;

import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("numpy archives are read as numpy wrote them")
class NpzTest {

    @Test
    @DisplayName("every element type, in both byte orders, deflated")
    void everyType() {
        Map<String, NdArray> a = Fixtures.npz("foundation/dtypes.npz");

        assertThat(a.get("f4").shape()).containsExactly(2, 2);
        assertThat(a.get("f4").floats()).containsExactly(1.5f, -2.25f, 3.0f, 1e-7f);
        assertThat(a.get("f8").doubles()).containsExactly(0.1, -1e300);
        assertThat(a.get("i1").longs()).containsExactly(-128L, 127L);
        assertThat(a.get("u1").longs()).containsExactly(0L, 255L);
        assertThat(a.get("i2").longs()).containsExactly(-32768L, 32767L);
        assertThat(a.get("u2").longs()).containsExactly(0L, 65535L);
        assertThat(a.get("i4").longs()).containsExactly((long) Integer.MIN_VALUE,
            (long) Integer.MAX_VALUE);
        assertThat(a.get("u4").longs()).containsExactly(0L, 4294967295L);
        assertThat(a.get("i8").longs()).containsExactly(Long.MIN_VALUE, Long.MAX_VALUE);
        assertThat(a.get("u8").longs()).containsExactly(0L, -1L);
        assertThat(a.get("b1").booleans()).containsExactly(true, false, true);
        assertThat(a.get("scalar").scalarLong()).isEqualTo(42L);
        assertThat(a.get("scalar").shape()).isEmpty();
        assertThat(a.get("big").floats()).containsExactly(1.0f, 2.0f);
    }

    @Test
    @DisplayName("stored without compression too")
    void stored() {
        NdArray a = Fixtures.npz("foundation/stored.npz").get("a");

        assertThat(a.shape()).containsExactly(2, 3);
        assertThat(a.longs()).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(a.doubles()).containsExactly(0, 1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("refusing what it would misread")
    void refusals() throws Exception {
        assertThatThrownBy(() -> Npz.readNpy("not numpy".getBytes(StandardCharsets.UTF_8), "x"))
            .hasMessageContaining("not a .npy");
        assertThatThrownBy(() -> Npz.readNpy(npy("{'descr': '<f4', 'fortran_order': True, "
                + "'shape': (1,), }"), "x"))
            .hasMessageContaining("Fortran");
        assertThatThrownBy(() -> Npz.readNpy(npy("{'descr': '<U3', 'fortran_order': False, "
                + "'shape': (1,), }"), "x"))
            .hasMessageContaining("does not read");
        assertThatThrownBy(() -> Npz.readNpy(npy("{'descr': '<f4', 'fortran_order': False, "
                + "'shape': (4,), }"), "x"))
            .hasMessageContaining("truncated");
        assertThatThrownBy(() -> Npz.read(zip(), "empty"))
            .hasMessageContaining("no .npy");
        assertThatThrownBy(() -> new NdArray("f4", new int[] {3}, new float[2]))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NdArray("f4", new int[] {1}, new float[1]).longs())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("arrays compare by value")
    void equality() {
        NdArray a = new NdArray("f4", new int[] {2}, new float[] {1, 2});
        assertThat(a).isEqualTo(new NdArray("f4", new int[] {2}, new float[] {1, 2}))
            .hasSameHashCodeAs(new NdArray("f4", new int[] {2}, new float[] {1, 2}))
            .isNotEqualTo(new NdArray("f4", new int[] {2}, new float[] {1, 3}));
        assertThat(a.toString()).contains("f4", "[2]");
    }

    private static byte[] npy(String header) {
        byte[] h = header.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y', 1, 0,
            (byte) h.length, 0});
        out.writeBytes(h);
        return out.toByteArray();
    }

    private static byte[] zip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("readme.txt"));
            z.write(1);
            z.closeEntry();
        }
        return out.toByteArray();
    }
}
