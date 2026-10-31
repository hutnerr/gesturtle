package com.gesturtle.library;

import com.gesturtle.model.DuplicateAction;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.ImportCandidate;
import com.gesturtle.model.ImportStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the import staging/commit flow against a real temp library and
 * real PNG files: import new, duplicates, move vs copy, and cancel
 * rollback.
 */
class ImportServiceTest {

    private Path pngFile(Path dir, String name, int rgb) throws IOException {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 10; y++) img.setRGB(x, y, rgb);
        }
        Path file = dir.resolve(name);
        ImageIO.write(img, "png", file.toFile());
        return file;
    }

    private ImportCandidate stageAndProbe(LibraryService library, Path file) {
        ImportCandidate candidate = new ImportCandidate(file);
        candidate.applyProbe(library.importService().probe(file, library.images()));
        return candidate;
    }

    @Test
    void newImageIsCopiedAndPersisted(@TempDir Path tempDir) throws IOException {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            Path source = pngFile(tempDir, "a.png", 0xFF0000);
            ImportCandidate candidate = stageAndProbe(library, source);
            assertEquals(ImportStatus.NEW, candidate.getStatus());

            var result = library.importService().importBatch(List.of(candidate), Set.of(), false,
                    library.images(), null, i -> { });

            assertEquals(1, result.imported());
            ImageRecord record = library.images().findByHash(candidate.getHash()).orElseThrow();
            assertTrue(Files.isRegularFile(library.ref().imagePath(record.hash(), record.ext())));
            assertTrue(Files.isRegularFile(source), "copy must not remove the source file");
        }
    }

    @Test
    void importingAStaleNewRowAddsToTheExistingImageInsteadOfFailing(@TempDir Path tempDir) throws IOException {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            Path source = pngFile(tempDir, "a.png", 0x00FF00);
            ImportCandidate first = stageAndProbe(library, source);
            ImportCandidate stale = stageAndProbe(library, source);
            library.importService().importBatch(List.of(first), Set.of(), false, library.images(), null, i -> { });
            int tag = library.tags().create("figure", null);

            // Still marked NEW from before the first import.
            var result = library.importService().importBatch(List.of(stale), Set.of(tag), false,
                    library.images(), null, i -> { });

            assertEquals(1, result.updated());
            ImageRecord record = library.images().findByHash(first.getHash()).orElseThrow();
            assertTrue(Files.isRegularFile(library.ref().imagePath(record.hash(), record.ext())));
            assertEquals(Set.of(tag), Set.copyOf(record.tagIds()));
        }
    }

    @Test
    void linkCanGoIntoNotesAsWellAsCredit(@TempDir Path tempDir) throws IOException {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            Path source = pngFile(tempDir, "a.png", 0xABCDEF);
            ImportCandidate candidate = stageAndProbe(library, source);
            candidate.setCredit("https://example.com/a.png");
            candidate.setNotes("https://example.com/a.png");

            library.importService().importBatch(List.of(candidate), Set.of(), false, library.images(), null, i -> { });

            ImageRecord record = library.images().findByHash(candidate.getHash()).orElseThrow();
            assertEquals("https://example.com/a.png", record.credit());
            assertEquals("https://example.com/a.png", record.notes());
        }
    }

    @Test
    void withNoteAppendsOnceAndKeepsExistingNotes() {
        assertEquals("link", ImportService.withNote(null, "link"));
        assertEquals("model: Ann\nlink", ImportService.withNote("model: Ann", "link"));
        assertEquals("model: Ann\nlink", ImportService.withNote("model: Ann\nlink", "link"));
        assertEquals("model: Ann", ImportService.withNote("model: Ann", null));
    }

    @Test
    void webpIsProbedImportedAndThumbnailed(@TempDir Path tempDir) throws IOException {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            Path source = tempDir.resolve("sample.webp");
            try (var in = getClass().getResourceAsStream("/sample.webp")) {
                Files.copy(in, source);
            }
            ImportCandidate candidate = stageAndProbe(library, source);
            assertEquals(ImportStatus.NEW, candidate.getStatus());
            assertEquals(64, candidate.getWidth());
            assertEquals(48, candidate.getHeight());

            var result = library.importService().importBatch(List.of(candidate), Set.of(), false,
                    library.images(), null, i -> { });

            assertEquals(1, result.imported());
            ImageRecord record = library.images().findByHash(candidate.getHash()).orElseThrow();
            assertEquals("webp", record.ext());
            assertTrue(Files.isRegularFile(library.ref().thumbPath(record.hash())));
        }
    }

    @Test
    void moveDeletesSourceAfterImport(@TempDir Path tempDir) throws IOException {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            Path source = pngFile(tempDir, "a.png", 0x00FF00);
            ImportCandidate candidate = stageAndProbe(library, source);

            library.importService().importBatch(List.of(candidate), Set.of(), true,
                    library.images(), null, i -> { });

            assertFalse(Files.exists(source), "move must remove the source file");
        }
    }

    @Test
    void duplicateWithinBatchIsSkipped(@TempDir Path tempDir) throws IOException {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            Path a = pngFile(tempDir, "a.png", 0x123456);
            Path b = pngFile(tempDir, "b.png", 0x123456); // identical pixels -> identical hash

            ImportCandidate ca = stageAndProbe(library, a);
            ImportCandidate cb = stageAndProbe(library, b);
            library.importService().markBatchDuplicates(List.of(ca, cb));
            assertEquals(ImportStatus.DUPLICATE_IN_BATCH, cb.getStatus());

            var result = library.importService().importBatch(List.of(ca, cb), Set.of(), false,
                    library.images(), null, i -> { });

            assertEquals(1, result.imported());
            assertEquals(1, result.skipped());
        }
    }

    @Test
    void reimportingAnAlreadyPresentHashTagsTheExistingImageInstead(@TempDir Path tempDir) throws IOException {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            Path a = pngFile(tempDir, "a.png", 0xABCDEF);
            ImportCandidate first = stageAndProbe(library, a);
            library.importService().importBatch(List.of(first), Set.of(), false,
                    library.images(), null, i -> { });

            Path aAgain = pngFile(tempDir, "a-again.png", 0xABCDEF);
            ImportCandidate second = stageAndProbe(library, aAgain);
            assertEquals(ImportStatus.ALREADY_IN_LIBRARY, second.getStatus());
            second.includedProperty().set(true);
            int tagId = library.tags().create("study", null);
            second.tagOverrides().add(tagId);

            var result = library.importService().importBatch(List.of(second), Set.of(), false,
                    library.images(), null, i -> { });

            assertEquals(1, result.updated());
            assertEquals(0, result.imported());
            ImageRecord existing = library.images().findByHash(first.getHash()).orElseThrow();
            assertTrue(existing.tagIds().contains(tagId));
        }
    }

    @Test
    void overwritingADuplicateReplacesItsTagsAndSource(@TempDir Path tempDir) throws IOException {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            int oldTag = library.tags().create("old", null);
            int newTag = library.tags().create("new", null);
            ImportCandidate first = stageAndProbe(library, pngFile(tempDir, "a.png", 0x0F0F0F));
            library.importService().importBatch(List.of(first), Set.of(oldTag), false,
                    library.images(), null, i -> { });

            ImportCandidate second = stageAndProbe(library, pngFile(tempDir, "renamed.png", 0x0F0F0F));
            second.includedProperty().set(true);
            second.setDuplicateAction(DuplicateAction.OVERWRITE);
            var result = library.importService().importBatch(List.of(second), Set.of(newTag), false,
                    library.images(), null, i -> { });

            assertEquals(1, result.overwritten());
            ImageRecord existing = library.images().findByHash(first.getHash()).orElseThrow();
            assertEquals(List.of(newTag), existing.tagIds());
            assertEquals("renamed.png", existing.originalName());
        }
    }

    @Test
    void skippingADuplicateLeavesItUntouched(@TempDir Path tempDir) throws IOException {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            ImportCandidate first = stageAndProbe(library, pngFile(tempDir, "a.png", 0x0A0B0C));
            library.importService().importBatch(List.of(first), Set.of(), false,
                    library.images(), null, i -> { });

            ImportCandidate second = stageAndProbe(library, pngFile(tempDir, "b.png", 0x0A0B0C));
            second.includedProperty().set(true);
            second.setDuplicateAction(DuplicateAction.SKIP);
            int tagId = library.tags().create("study", null);
            var result = library.importService().importBatch(List.of(second), Set.of(tagId), false,
                    library.images(), null, i -> { });

            assertEquals(1, result.skipped());
            ImageRecord existing = library.images().findByHash(first.getHash()).orElseThrow();
            assertTrue(existing.tagIds().isEmpty());
            assertEquals("a.png", existing.originalName());
        }
    }

    /**
     * Regression test: a soft-deleted image's hash still occupies the
     * UNIQUE(hash) slot, so re-importing the same bytes must restore it
     * rather than being classified NEW and hitting a constraint violation.
     */
    @Test
    void reimportingADeletedImageRestoresItInstead(@TempDir Path tempDir) throws IOException {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            Path a = pngFile(tempDir, "a.png", 0x55AA33);
            ImportCandidate first = stageAndProbe(library, a);
            library.importService().importBatch(List.of(first), Set.of(), false,
                    library.images(), null, i -> { });

            ImageRecord existing = library.images().findByHash(first.getHash()).orElseThrow();
            // What ImageTrash.delete does, minus sending a test file to the real system trash.
            Files.delete(library.ref().imagePath(existing.hash(), existing.ext()));
            library.images().softDelete(existing.id(), System.currentTimeMillis());

            Path aAgain = pngFile(tempDir, "a-again.png", 0x55AA33);
            ImportCandidate second = stageAndProbe(library, aAgain);
            assertEquals(ImportStatus.ALREADY_IN_LIBRARY, second.getStatus(),
                    "a deleted image's hash must not be reported as NEW");
            second.includedProperty().set(true);

            var result = library.importService().importBatch(List.of(second), Set.of(), false,
                    library.images(), null, i -> { });

            assertEquals(1, result.updated());
            assertEquals(0, result.failed());
            ImageRecord restored = library.images().findByHash(first.getHash()).orElseThrow();
            assertFalse(restored.isDeleted());
            assertTrue(Files.isRegularFile(library.ref().imagePath(restored.hash(), restored.ext())));
        }
    }

    @Test
    void cancellingAMoveMidBatchKeepsEverySourceFile(@TempDir Path tempDir) throws IOException {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            Path first = pngFile(tempDir, "first.png", 0x112233);
            Path second = pngFile(tempDir, "second.png", 0x445566);
            Path third = pngFile(tempDir, "third.png", 0x778899);
            List<ImportCandidate> batch = List.of(stageAndProbe(library, first), stageAndProbe(library, second),
                    stageAndProbe(library, third));
            AtomicBoolean cancelFlag = new AtomicBoolean(false);

            // Cancelled with two images in: their library copies are removed again, so their sources must stay.
            var result = library.importService().importBatch(batch, Set.of(), true, library.images(), cancelFlag,
                    i -> cancelFlag.set(i == 1));

            assertTrue(result.cancelled());
            assertTrue(Files.isRegularFile(first), "a moved file must survive a cancelled import");
            assertTrue(Files.isRegularFile(second));
            assertTrue(Files.isRegularFile(third));
            assertFalse(library.images().existsByHash(batch.get(0).getHash()));
        }
    }

    @Test
    void cancellingMidBatchLeavesNoTrace(@TempDir Path tempDir) throws IOException {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            Path a = pngFile(tempDir, "a.png", 0x999999);
            ImportCandidate candidate = stageAndProbe(library, a);
            AtomicBoolean cancelFlag = new AtomicBoolean(true);

            var result = library.importService().importBatch(List.of(candidate), Set.of(), false,
                    library.images(), cancelFlag, i -> { });

            assertTrue(result.cancelled());
            assertTrue(library.images().findByHash(candidate.getHash()).isEmpty());
        }
    }

    @Test
    void failuresAreReportedPerCandidateAndDoNotBlockTheRest(@TempDir Path tempDir) throws IOException {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            ImportCandidate ok = stageAndProbe(library, pngFile(tempDir, "ok.png", 0x00FF00));
            Path vanishing = pngFile(tempDir, "gone.png", 0x0000FF);
            ImportCandidate gone = stageAndProbe(library, vanishing);
            Files.delete(vanishing); // moved or deleted between staging and importing

            var result = library.importService().importBatch(List.of(ok, gone), Set.of(), false,
                    library.images(), null, i -> { });

            assertEquals(1, result.imported());
            assertEquals(1, result.failed());
            assertTrue(result.failures().containsKey(gone));
            assertTrue(library.images().findByHash(ok.getHash()).isPresent());
        }
    }

    @Test
    void deleteLibraryFilesRemovesOnlyGesturtlesOwnData(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("lib");
        LibraryService library = new LibraryService(root.toString());
        ImportCandidate candidate = stageAndProbe(library, pngFile(tempDir, "a.png", 0x123456));
        library.importService().importBatch(List.of(candidate), Set.of(), false, library.images(), null, i -> { });
        Path backup = Files.writeString(library.ref().exportsDir().resolve("backup.zip"), "zip");
        Path unrelated = Files.writeString(root.resolve("notes.txt"), "mine");
        library.close();

        LibraryService.deleteLibraryFiles(library.ref());

        assertTrue(Files.notExists(library.ref().dbFile()));
        assertTrue(Files.notExists(library.ref().imagesDir()));
        assertTrue(Files.notExists(library.ref().thumbsDir()));
        assertTrue(Files.isRegularFile(backup), "exports are backups and must survive");
        assertTrue(Files.isRegularFile(unrelated), "files Gesturtle didn't create must survive");

        // The folder works as a fresh, empty library afterwards.
        try (LibraryService fresh = new LibraryService(root.toString())) {
            assertEquals(0, fresh.images().count(new com.gesturtle.filter.Filter.All()));
        }
    }
}
