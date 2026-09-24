package com.eonmux.excalibur.core.datadir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Exclusive ownership of a data directory, for as long as the owning process lives.
 *
 * <h2>Why a directory needs an owner</h2>
 * <p>Nothing in the engine orders one process's writes against another's. Two nodes started on
 * the same directory each map the same segments, append to the same write-ahead logs and publish
 * the same catalog by rename, and the directory ends up describing neither of them. The same is
 * true of {@code excalibur-fsck --repair} moving files out from under a running node, and of a
 * restore replacing the files a node has mapped. Every one of those is a process that could have
 * been refused at the door.</p>
 *
 * <h2>How</h2>
 * <p>An operating-system lock - {@link FileChannel#tryLock()} - on {@value #FILE_NAME} inside the
 * directory. The kernel releases it when the holder exits, however it exits, so a crash never
 * leaves a directory that has to be unlocked by hand; and a pid file alone could not give that,
 * because a pid is reused and a check of it races the process it names. The holder writes its
 * pid, what it is and when it started into the file ({@link LockHolder}), but only so that a
 * refusal can name it: the record is informational and the lock is the truth.</p>
 *
 * <p>The file itself is never deleted. Deleting a locked file and creating a new one would let a
 * second process lock the new file while the first still holds the old, which is exactly the
 * exclusion failing; a lock file that outlives its holder is harmless, since the next holder
 * locks it and rewrites the record.</p>
 *
 * <h2>Within one process</h2>
 * <p>POSIX record locks belong to a process, not to a channel, and closing <em>any</em> channel
 * a process has open on a file drops every lock that process holds on it. So a second claim from
 * inside a process that already holds the directory must never reach the file: it would be
 * granted by the kernel, or - once refused by the JDK's own bookkeeping - its channel's close
 * would release the first owner's lock. The claims this process holds are therefore kept in a
 * table and a second one is refused from it before any file is opened. The table is also what
 * keeps a held lock reachable: a channel that became garbage would be closed by its cleaner,
 * and the lock with it.</p>
 *
 * <h2>Limits</h2>
 * <p>The exclusion holds between processes that take this lock, which in this build is the
 * node, {@code excalibur-fsck --repair} and {@code excalibur-backup snapshot} and
 * {@code restore}; a process that writes the directory without asking is not stopped. It holds
 * across containers that mount the same volume, since they share the kernel that keeps the
 * lock. Over a network filesystem it is as good as that filesystem's lock support - NFSv4 and
 * SMB honour it, some configurations silently do not.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. {@link #acquire} and {@link #probe} serialise on the process-wide table, and
 * a lock's own record is written under its monitor.</p>
 *
 * @author ExcaliburDB
 */
public final class DataDirectoryLock implements AutoCloseable {

    /** The file inside the data directory that is locked. */
    public static final String FILE_NAME = ".excalibur-lock";

    /** Every claim this process holds, by the real path of its lock file. */
    private static final Map<Path, DataDirectoryLock> HELD = new HashMap<>();

    /** The directory as the caller named it. */
    private final Path directory;

    /** The real path of the lock file, the key into {@link #HELD}. */
    private final Path file;

    /** The channel the lock was taken through; closing it would release the lock. */
    private final FileChannel channel;

    /** The operating-system lock. */
    private final FileLock lock;

    /** What this holder recorded about itself. */
    private LockHolder holder;

    /** Whether {@link #close()} has run. */
    private boolean released;

    /**
     * Creates the handle for a lock just taken.
     *
     * @param directory The directory as named
     * @param file      The lock file's real path
     * @param channel   The channel the lock was taken through
     * @param lock      The lock
     * @param holder    The record written into the file
     */
    private DataDirectoryLock(Path directory, Path file, FileChannel channel, FileLock lock,
                              LockHolder holder) {
        this.directory = directory;
        this.file = file;
        this.channel = channel;
        this.lock = lock;
        this.holder = holder;
    }

    /**
     * Claims a data directory for this process.
     *
     * <p>Never waits. A directory another process holds is refused at once, because the only
     * holders are a node - which holds it until it is stopped - and the operator tools, and
     * blocking behind either would be a hang rather than an answer.</p>
     *
     * @param directory The data directory, which must exist
     * @param holder    What this process is, one line, for the record a refused claimant reads
     * @return The claim; closing it releases the directory
     * @throws DataDirectoryLockedException if another process, or another owner in this one,
     *                                      holds the directory
     * @throws NoSuchFileException          if the directory does not exist
     * @throws IOException                  if the lock file cannot be created, locked or
     *                                      written
     * @throws IllegalArgumentException     if holder is blank or spans lines
     */
    public static DataDirectoryLock acquire(Path directory, String holder) throws IOException {
        Objects.requireNonNull(directory, "directory");
        String description = requireOneLine(holder, "holder");
        Path file = lockFileOf(directory);
        synchronized (HELD) {
            DataDirectoryLock existing = HELD.get(file);
            if (existing != null) {
                throw new DataDirectoryLockedException(directory, existing.holder(), true);
            }
            FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                FileLock lock = tryLock(channel, false);
                if (lock == null) {
                    LockHolder recorded = readRecord(channel);
                    channel.close();
                    throw new DataDirectoryLockedException(directory, recorded, false);
                }
                LockHolder record = new LockHolder(ProcessHandle.current().pid(), description,
                    Optional.of(Instant.now().toString()), Optional.empty(), Optional.empty());
                DataDirectoryLock claim =
                    new DataDirectoryLock(directory, file, channel, lock, record);
                claim.rewrite(record);
                HELD.put(file, claim);
                return claim;
            } catch (IOException | RuntimeException e) {
                closeQuietly(channel, e);
                throw e;
            }
        }
    }

    /**
     * Reports whether some process holds a data directory, without claiming it or writing
     * anything.
     *
     * <p>For a caller that must not change the directory - a read-only check, a snapshot of a
     * directory it cannot write - and only wants to know whether a node may be changing files
     * underneath it. The file is opened for reading only and a lock file that does not exist is
     * not created: no process of this build can be holding a directory with no lock file in it.
     * The probe does take a shared lock for the instant it tests, so a node starting at that
     * exact instant is refused and says so; that is the one trace it can leave.</p>
     *
     * @param directory The data directory
     * @return The holder's record if the directory is held - with an unknown holder if the
     *         record could not be read - or empty if it is free
     * @throws IOException if the lock file exists and cannot be opened or tested
     */
    public static Optional<LockHolder> probe(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path file = lockFileOf(directory);
        synchronized (HELD) {
            DataDirectoryLock existing = HELD.get(file);
            if (existing != null) {
                return Optional.of(existing.holder());
            }
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                FileLock lock = tryLock(channel, true);
                if (lock == null) {
                    LockHolder recorded = readRecord(channel);
                    return Optional.of(recorded != null ? recorded : LockHolder.parse(""));
                }
                lock.release();
                return Optional.empty();
            }
        }
    }

    /**
     * Records where a node that holds this directory is listening.
     *
     * <p>Appended to the record in one write rather than rewriting it, so a reader never finds
     * the file empty; a reader that races the append sees the record with or without the new
     * lines. Written into the lock file rather than a file of its own because it describes the
     * holder and has the holder's lifetime: the next process to take the directory replaces it,
     * and there is no second file that a crash could leave pointing at a node that is gone.</p>
     *
     * @param listening Where the node is listening, e.g. {@code https://0.0.0.0:8443}
     * @param health    Where a probe on this host reaches the node's liveness route
     * @throws IOException              if the record cannot be written
     * @throws IllegalStateException    if this claim has been released
     * @throws IllegalArgumentException if either value is blank or spans lines
     */
    public synchronized void advertise(String listening, String health) throws IOException {
        String url = requireOneLine(listening, "listening");
        String healthUrl = requireOneLine(health, "health");
        if (released) {
            throw new IllegalStateException("The lock on " + directory + " has been released");
        }
        String appended = LockHolder.KEY_URL + '=' + url + '\n'
            + LockHolder.KEY_HEALTH_URL + '=' + healthUrl + '\n';
        ByteBuffer bytes = ByteBuffer.wrap(appended.getBytes(StandardCharsets.UTF_8));
        long position = channel.size();
        while (bytes.hasRemaining()) {
            position += channel.write(bytes, position);
        }
        channel.force(true);
        holder = holder.withEndpoint(url, healthUrl);
    }

    /**
     * Returns the directory this claim holds.
     *
     * @return The directory, as the caller named it
     */
    public Path directory() {
        return directory;
    }

    /**
     * Returns what this holder recorded about itself.
     *
     * @return The record
     */
    public synchronized LockHolder holder() {
        return holder;
    }

    /**
     * Releases the directory.
     *
     * <p>The file stays, for the reason the class description gives. Idempotent.</p>
     *
     * @throws IOException if the lock or its channel cannot be released
     */
    @Override
    public void close() throws IOException {
        synchronized (HELD) {
            synchronized (this) {
                if (released) {
                    return;
                }
                released = true;
                HELD.remove(file);
                try {
                    lock.release();
                } finally {
                    channel.close();
                }
            }
        }
    }

    /**
     * Resolves the lock file of a directory to the one path every spelling of it shares.
     *
     * @param directory The data directory
     * @return The real path of its lock file
     * @throws NoSuchFileException if the directory does not exist
     * @throws IOException         if it is not a directory or cannot be resolved
     */
    private static Path lockFileOf(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            throw new NoSuchFileException(directory.toString(), null,
                "there is no data directory there to lock");
        }
        if (!Files.isDirectory(directory)) {
            throw new IOException("A data directory is a directory, and " + directory
                + " is not one");
        }
        return directory.toRealPath().resolve(FILE_NAME);
    }

    /**
     * Tries the lock without waiting.
     *
     * @param channel The channel to lock through
     * @param shared  Whether a shared lock is enough
     * @return The lock, or null when another holder has it
     * @throws IOException if the platform cannot lock the file
     */
    private static FileLock tryLock(FileChannel channel, boolean shared) throws IOException {
        try {
            return channel.tryLock(0L, Long.MAX_VALUE, shared);
        } catch (OverlappingFileLockException e) {
            // Something else in this JVM locked the file without going through this class. It
            // is held, by a holder the table cannot name.
            return null;
        }
    }

    /**
     * Reads the holder's record through the channel already open on it.
     *
     * <p>Through that channel and not a new one: a second channel's close would, on POSIX,
     * release any lock this process held on the file.</p>
     *
     * @param channel An open channel on the lock file
     * @return The record, or null if the file could not be read
     */
    private static LockHolder readRecord(FileChannel channel) {
        try {
            long size = Math.min(channel.size(), 64 * 1024L);
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining() && channel.read(buffer, buffer.position()) > 0) {
                // Read until the buffer is full or the file ends.
            }
            return LockHolder.parse(new String(buffer.array(), 0, buffer.position(),
                StandardCharsets.UTF_8));
        } catch (IOException e) {
            // On a platform whose locks are mandatory the holder's record is unreadable while
            // it is held; the refusal is still right, and only less specific.
            return null;
        }
    }

    /**
     * Replaces the record in the file with this holder's.
     *
     * @param record What to write
     * @throws IOException if it cannot be written or forced
     */
    private void rewrite(LockHolder record) throws IOException {
        channel.truncate(0L);
        ByteBuffer bytes = ByteBuffer.wrap(record.render().getBytes(StandardCharsets.UTF_8));
        long position = 0L;
        while (bytes.hasRemaining()) {
            position += channel.write(bytes, position);
        }
        channel.force(true);
    }

    /**
     * Requires a value to be one non-blank line, since the record is line-oriented.
     *
     * @param value The value
     * @param name  What it is, for the message
     * @return The value, trimmed
     * @throws IllegalArgumentException if it is blank or spans lines
     */
    private static String requireOneLine(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(name + " must be one non-blank line: '" + value
                + "'");
        }
        return trimmed;
    }

    /**
     * Closes a channel after a failure, attaching any failure of the close to it.
     *
     * @param channel The channel
     * @param failure What went wrong
     */
    private static void closeQuietly(FileChannel channel, Exception failure) {
        try {
            channel.close();
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }
}
