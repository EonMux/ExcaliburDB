package com.eonmux.excalibur.graph.file;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Durability} that really forces and also remembers, in order, what it was asked to.
 *
 * <p>Whether a write reached the device cannot be observed from Java, so what a test can check is
 * that the code asked - and, more importantly, the order it asked in. Ordering is the whole
 * content of these guarantees: contents before the name that points at them, and the replacement
 * before the copy it replaces is destroyed.
 */
public final class RecordingDurability implements Durability {

    /** Every file forced, in the order it was forced. */
    public final List<Path> files = new ArrayList<>();

    /** Every directory forced, in the order it was forced. */
    public final List<Path> directories = new ArrayList<>();

    @Override
    public void fsyncFile(Path file) throws IOException {
        files.add(file);
        Durability.super.fsyncFile(file);
    }

    @Override
    public void fsyncDirectory(Path directory) throws IOException {
        directories.add(directory);
        Durability.super.fsyncDirectory(directory);
    }

    /** Where {@code name} was forced in the sequence of forced files, or -1 if it never was. */
    public int indexOfFile(String name) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getFileName().toString().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** The names of every file forced, in order. */
    public List<String> fileNames() {
        return files.stream().map(path -> path.getFileName().toString()).toList();
    }
}
