package io.excalibur.retrieval.lexical.native_;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * {@link Durability} against the real filesystem.
 *
 * <p>The only implementation the engine runs with; the interface exists so the order of these
 * three calls can be asserted, not so the steps can be swapped for something else.</p>
 */
final class FileSystemDurability implements Durability {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileSystemDurability.class);

    @Override
    public void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    @Override
    public void publish(Path replacement, Path target) throws IOException {
        try {
            Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            LOGGER.warn("This filesystem cannot rename atomically, so {} is published with a "
                + "plain replace; a crash during it can leave the file truncated", target);
            Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            LOGGER.warn("Could not force the directory entry for {}; a host crash immediately "
                + "after this write can lose the rename that published it: {}",
                directory, e.getMessage());
        }
    }
}
