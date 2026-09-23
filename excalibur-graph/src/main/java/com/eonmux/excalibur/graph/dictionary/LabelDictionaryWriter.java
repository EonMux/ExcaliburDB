package com.eonmux.excalibur.graph.dictionary;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.format.EdgeStoreDescriptor;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link LabelDictionary}.
 *
 * <p>Accumulates names in memory and writes them sorted by id, because the reader binary-searches
 * and a dictionary written in insertion order would silently return misses for ids that are
 * present. Both files are written beside their destination, forced to the device and then moved
 * into place, so a build that fails partway leaves the previous dictionary intact rather than a
 * half-written one that opens and returns wrong names.
 *
 * <p>A dictionary is two files and therefore two renames, which cannot be made one atomic act. A
 * crash between them is detected on the other side: the index, written last, ends with the
 * length and CRC32C of the text it was written beside and a CRC32C of itself, and
 * {@link LabelDictionary#open} refuses a pair that no longer matches them. The checksums are
 * computed from the bytes as they are handed to the file, before either is forced, so they
 * describe exactly what the build meant to publish.
 *
 * <p>Not thread-safe: build one, write it, discard it.
 */
public final class LabelDictionaryWriter {

    private final Map<Long, String> names = new HashMap<>();

    /**
     * Records a name, replacing any previous name for the same id.
     *
     * @param id   The entity or relation id
     * @param name The name
     * @return this writer
     * @throws IllegalArgumentException if the name is null or blank
     */
    public LabelDictionaryWriter put(long id, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank for id " + id);
        }
        names.put(id, name);
        return this;
    }

    /** The number of names recorded. */
    public int size() {
        return names.size();
    }

    /**
     * Writes the dictionary.
     *
     * @param directory The directory to write into; created if absent
     * @param name      The base name, without extension
     * @throws IOException if writing fails
     */
    public void write(Path directory, String name) throws IOException {
        Files.createDirectories(directory);

        List<Long> ids = new ArrayList<>(names.keySet());
        ids.sort(null);

        Path indexTemp = directory.resolve(name + ".idx.tmp");
        Path dataTemp = directory.resolve(name + ".dat.tmp");

        try {
            List<byte[]> encoded = new ArrayList<>(ids.size());
            long total = 0;
            for (long id : ids) {
                byte[] bytes = names.get(id).getBytes(StandardCharsets.UTF_8);
                encoded.add(bytes);
                total += bytes.length;
            }
            if (total > Integer.MAX_VALUE) {
                throw new IOException("dictionary text exceeds " + Integer.MAX_VALUE + " bytes");
            }

            java.util.zip.CRC32C textCrc = new java.util.zip.CRC32C();
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(dataTemp))) {
                for (byte[] bytes : encoded) {
                    out.write(bytes);
                    textCrc.update(bytes);
                }
            }

            ByteBuffer index = ByteBuffer
                .allocate(LabelDictionary.HEADER_BYTES + ids.size() * LabelDictionary.ENTRY_BYTES
                    + LabelDictionary.TRAILER_BYTES)
                .order(EdgeStoreDescriptor.COLUMN_BYTE_ORDER);
            index.putLong(LabelDictionary.MAGIC);
            index.putInt(LabelDictionary.VERSION);
            index.putInt(ids.size());
            int offset = 0;
            for (int i = 0; i < ids.size(); i++) {
                byte[] bytes = encoded.get(i);
                index.putLong(ids.get(i));
                index.putInt(offset);
                index.putInt(bytes.length);
                offset += bytes.length;
            }
            index.putLong(total);
            index.putInt((int) textCrc.getValue());
            index.putInt((int) IndexChecksum.of(index.array(), 0, index.position()));
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(indexTemp));
                 DataOutputStream data = new DataOutputStream(out)) {
                data.write(index.array());
            }

            // Both files are on the device before either name changes, so the pair that becomes
            // visible is a pair that exists. Renaming over contents still in the page cache would
            // publish an index describing text that a power loss can take away.
            Durability.DEVICE.fsyncFile(dataTemp);
            Durability.DEVICE.fsyncFile(indexTemp);

            // Data first: a reader that finds an index entry pointing into a data file that is
            // not there yet would read a name out of nothing. The index is what makes an entry
            // visible, so it goes last.
            //
            // The two renames are still two acts, and nothing can make them one. A crash between
            // them leaves the previous index beside the new text, and it is LabelDictionary.open
            // that catches the mismatch: the alternative to detecting it is serving one entity's
            // name for another's, which nobody would notice.
            Files.move(dataTemp, directory.resolve(name + ".dat"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.move(indexTemp, directory.resolve(name + ".idx"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Durability.DEVICE.fsyncDirectory(directory);
        } finally {
            Files.deleteIfExists(indexTemp);
            Files.deleteIfExists(dataTemp);
        }
    }
}
