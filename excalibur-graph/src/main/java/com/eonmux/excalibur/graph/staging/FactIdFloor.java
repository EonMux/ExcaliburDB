package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.file.LogCorruptionException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * The highest fact id a staging store has handed out, kept on disk so no id is handed out twice.
 *
 * <h2>Why the staged facts are not enough</h2>
 * <p>A fact id is the later of one past the last id and the clock in microseconds, and the last id
 * could be read back from the asserted facts still staged. It could not be read back from the ones
 * that are not: a retraction or an entity erasure removes a fact from staging, so after the newest
 * assertions have been erased, a restart with the clock set back would number from an older fact
 * and hand out an id a client still holds - one a retraction it sends later would then apply to.
 * This file is written before any id in it is returned, and never lowered, so the ids stay unique
 * whatever staging has since lost.</p>
 *
 * <h2>Format</h2>
 * <pre>
 * int  magic  "EXFI"
 * long id     the highest fact id handed out
 * int  crc32  over the twelve bytes before it
 * </pre>
 * <p>Little-endian, like the staging logs. Replaced whole through a temporary file and a durable
 * rename, so a reader sees the old value or the new one.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the store serialises its writes.</p>
 */
public final class FactIdFloor {

    /** The file, inside a staging directory. */
    public static final String FILE_NAME = "asserted.last-id";

    /** The replacement a write builds beside it before renaming it over the file. */
    static final String REWRITE_NAME = FILE_NAME + ".writing";

    private static final int MAGIC = 0x49465845;
    private static final int BYTES = Integer.BYTES + Long.BYTES + Integer.BYTES;

    private FactIdFloor() {
    }

    /**
     * Reads the highest fact id handed out.
     *
     * @param file The file
     * @return The id, or 0 when no file has been written
     * @throws LogCorruptionException if the file is not one this class wrote, or its checksum fails
     * @throws IOException if it cannot be read
     */
    public static long read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return 0;
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length != BYTES) {
            throw damaged(file, "it is " + bytes.length + " bytes long, not " + BYTES);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt(0) != MAGIC) {
            throw damaged(file, "it does not start with the marker this file is written with");
        }
        if (buffer.getInt(BYTES - Integer.BYTES) != checksum(bytes)) {
            throw damaged(file, "its checksum does not match what it holds");
        }
        long id = buffer.getLong(Integer.BYTES);
        if (id < 0) {
            throw damaged(file, "it holds a negative id, " + id);
        }
        return id;
    }

    /**
     * Records the highest fact id handed out, durably, before any caller is given it.
     *
     * @param file The file
     * @param id   The id; never below what the file already holds
     * @throws IOException if it cannot be written, forced or renamed into place
     */
    static void write(Path file, long id) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC).putLong(id);
        buffer.putInt(checksum(buffer.array()));
        Path temp = file.resolveSibling(REWRITE_NAME);
        try {
            Files.write(temp, buffer.array());
            Durability.DEVICE.replaceDurably(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static int checksum(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, BYTES - Integer.BYTES);
        return (int) crc.getValue();
    }

    private static LogCorruptionException damaged(Path file, String why) {
        return new LogCorruptionException(file + " is damaged: " + why + ". It records the highest "
            + "fact id handed out, so that none is handed out again; until it is restored from a "
            + "backup, or removed - after which ids are numbered from the highest fact still "
            + "staged and the clock - no fact can be asserted in this namespace");
    }
}
