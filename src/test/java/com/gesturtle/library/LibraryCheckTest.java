package com.gesturtle.library;

import com.gesturtle.model.ImageRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryCheckTest {

    @Test
    void findsMissingFilesAndRelinksThemByContent(@TempDir Path tempDir) throws Exception {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            HashService hashes = new HashService();
            Path elsewhere = Files.createDirectories(tempDir.resolve("backup/deep"));
            Path present = Files.writeString(tempDir.resolve("present.png"), "present-bytes");
            Path lost = Files.writeString(elsewhere.resolve("renamed-copy.png"), "lost-bytes");
            Files.writeString(elsewhere.resolve("decoy.png"), "lost-byte!"); // same size, different content

            String presentHash = hashes.hashFile(present);
            String lostHash = hashes.hashFile(lost);
            library.images().insert(presentHash, "png", "present.png", 1, 1, Files.size(present), 1, null);
            library.images().insert(lostHash, "png", "lost.png", 1, 1, Files.size(lost), 2, null);
            Files.createDirectories(library.ref().imagePath(presentHash, "png").getParent());
            Files.copy(present, library.ref().imagePath(presentHash, "png"));

            List<ImageRecord> missing = LibraryCheck.findMissing(library);
            assertEquals(List.of("lost.png"), missing.stream().map(ImageRecord::originalName).toList());

            LibraryCheck.RelinkResult result = LibraryCheck.relinkFrom(library, missing, tempDir.resolve("backup"));
            assertEquals(1, result.restored());
            assertEquals(0, result.stillMissing());
            assertTrue(Files.isRegularFile(library.ref().imagePath(lostHash, "png")));
            assertTrue(LibraryCheck.findMissing(library).isEmpty());
        }
    }

    @Test
    void dataUriImagesDecodeToAFile() throws Exception {
        byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        Path file = IncomingImages.download("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes));
        assertTrue(file.getFileName().toString().endsWith(".png"));
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }
}
