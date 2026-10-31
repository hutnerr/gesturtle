package com.gesturtle.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileScanTest {

    @Test
    void listsFilesWithOrWithoutSubfolders(@TempDir Path dir) throws IOException {
        Path top = Files.writeString(dir.resolve("a.png"), "a");
        Path nested = Files.writeString(Files.createDirectories(dir.resolve("sub/deeper")).resolve("b.png"), "b");

        assertEquals(List.of(top), FileScan.files(dir, false));
        assertEquals(List.of(top, nested), FileScan.files(dir, true));
    }

    @Test
    void aMissingFolderIsAnError(@TempDir Path dir) {
        assertThrows(IOException.class, () -> FileScan.files(dir.resolve("gone"), true));
    }
}
