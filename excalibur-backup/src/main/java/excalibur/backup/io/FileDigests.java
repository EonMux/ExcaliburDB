package excalibur.backup.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * Reads a file once and reports both of its checksums.
 *
 * <p>Both are computed in the same pass because the files in question are large and the
 * second pass would cost as much as the first. The pass covers every logical byte,
 * including the parts of a sparsely allocated file that occupy no blocks: a write-ahead log
 * is a 64 MiB mapping holding a few kilobytes of records, and a checksum that described
 * only the allocated part would change the moment the filesystem decided to fill a hole.</p>
 *
 * @author ExcaliburDB
 */
public final class FileDigests {

    /** Read size. Large enough to amortise the syscall, small enough not to matter to heap. */
    private static final int BLOCK_BYTES = 1 << 20;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private FileDigests() {
    }

    /**
     * Digests one file.
     *
     * @param file The file to read
     * @return Its size and both checksums
     * @throws IOException          if the file cannot be opened or read
     * @throws NullPointerException if file is null
     */
    public static FileDigest of(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        CRC32C crc = new CRC32C();
        MessageDigest sha256 = newSha256();
        long size = 0L;
        ByteBuffer buffer = ByteBuffer.allocate(BLOCK_BYTES);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (channel.read(buffer) > 0) {
                buffer.flip();
                crc.update(buffer.duplicate());
                sha256.update(buffer);
                size += buffer.position();
                buffer.clear();
            }
        }
        long declared = Files.size(file);
        if (size != declared) {
            throw new IOException("Read " + size + " bytes from " + file + " but it declares "
                + declared + "; it is being written to, or the filesystem is lying");
        }
        return new FileDigest(size, hex(toBytes(crc.getValue())), hex(sha256.digest()));
    }

    /**
     * Returns a SHA-256 digest.
     *
     * @return A fresh digest
     */
    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM is required to provide SHA-256", e);
        }
    }

    /**
     * Renders a CRC32C value as its four big-endian bytes.
     *
     * @param value The checksum, in the low 32 bits
     * @return Four bytes, most significant first
     */
    private static byte[] toBytes(long value) {
        return new byte[] {
            (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
    }

    /**
     * Renders bytes as lowercase hex.
     *
     * @param bytes The bytes
     * @return Two hex characters per byte
     */
    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }
}
