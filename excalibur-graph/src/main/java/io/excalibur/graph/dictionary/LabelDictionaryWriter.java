package io.excalibur.graph.dictionary;

import io.excalibur.graph.format.EdgeStoreDescriptor;

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
 * present. Both files are written beside their destination and moved into place, so a build that
 * fails partway leaves the previous dictionary intact rather than a half-written one that opens
 * and returns wrong names.
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

            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(dataTemp))) {
                for (byte[] bytes : encoded) {
                    out.write(bytes);
                }
            }

            ByteBuffer index = ByteBuffer
                .allocate(LabelDictionary.HEADER_BYTES + ids.size() * LabelDictionary.ENTRY_BYTES)
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
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(indexTemp));
                 DataOutputStream data = new DataOutputStream(out)) {
                data.write(index.array());
            }

            // Data first: a reader that finds an index entry pointing into a data file that is
            // not there yet would read a name out of nothing. The index is what makes an entry
            // visible, so it goes last.
            Files.move(dataTemp, directory.resolve(name + ".dat"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.move(indexTemp, directory.resolve(name + ".idx"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(indexTemp);
            Files.deleteIfExists(dataTemp);
        }
    }
}
