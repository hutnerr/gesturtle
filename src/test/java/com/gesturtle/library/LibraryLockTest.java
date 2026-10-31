package com.gesturtle.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LibraryLockTest {

    @Test
    void aLibraryCanOnlyBeOpenOnceAtATime(@TempDir Path tempDir) {
        String root = tempDir.resolve("lib").toString();
        try (LibraryService first = new LibraryService(root)) {
            assertThrows(LibraryLock.AlreadyOpenException.class, () -> new LibraryService(root));
        }
        // Closing releases it.
        new LibraryService(root).close();
    }
}
