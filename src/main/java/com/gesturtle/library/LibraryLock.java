package com.gesturtle.library;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Keeps a library open in one Gesturtle at a time. Two copies on the same
 * folder would both write gesturtle.db (one getting "database is locked")
 * and both write meta/, each overwriting the other's changes with what it
 * last read. The lock is an OS file lock, so it goes away by itself if the
 * app crashes; the file stays behind, which is fine (it matches the
 * gitignored gesturtle.db* pattern).
 */
final class LibraryLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private LibraryLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static LibraryLock acquire(Path lockFile) {
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock != null) return new LibraryLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            // Already open in this same process.
        } catch (IOException e) {
            // Can't lock here (some network drives): carry on unguarded rather than refuse to open.
            closeQuietly(channel);
            return new LibraryLock(null, null);
        }
        closeQuietly(channel);
        throw new AlreadyOpenException();
    }

    @Override
    public void close() {
        try {
            if (lock != null) lock.release();
        } catch (IOException ignored) {
        }
        closeQuietly(channel);
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            if (channel != null) channel.close();
        } catch (IOException ignored) {
        }
    }

    public static final class AlreadyOpenException extends RuntimeException {
        AlreadyOpenException() {
            super("This library is already open in another Gesturtle window. Switch to that window, "
                    + "or close it and open the library again.");
        }
    }
}
