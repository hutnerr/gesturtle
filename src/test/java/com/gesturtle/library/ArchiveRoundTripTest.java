package com.gesturtle.library;

import com.gesturtle.filter.Filter;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.OrderMode;
import com.gesturtle.model.Playlist;
import com.gesturtle.model.Preset;
import com.gesturtle.model.Segment;
import com.gesturtle.model.SessionSpec;
import com.gesturtle.model.SourceKind;
import com.gesturtle.model.Tag;
import com.gesturtle.model.TickBehavior;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that exporting and importing into an empty library produces an
 * equivalent DB, end to end, including the session
 * features (a playlist segment and a ramped timer) a preset might carry.
 */
class ArchiveRoundTripTest {
    // Real hashes of the fake bytes written below: import re-hashes every file it copies in.
    private static final String HASH_A = sha256("fake-png-bytes-a");
    private static final String HASH_B = sha256("fake-png-bytes-b");

    @Test
    void exportingAndImportingReproducesImagesTagsPlaylistsAndPresets(@TempDir Path tempDir) throws IOException {
        try (LibraryService source = new LibraryService(tempDir.resolve("source").toString())) {
            int dogTag = source.tags().create("animal", null);
            int huskyTag = source.tags().create("dog", dogTag);
            source.tags().setGroupOnly(dogTag, true);
            source.tags().setExclusiveChildren(dogTag, true);

            int imageA = source.images().insert(HASH_A, "png", "a.png", 100, 100, 1000,
                    System.currentTimeMillis(), null);
            source.images().addTags(imageA, List.of(huskyTag));
            source.images().setFavorite(imageA, true);
            source.images().setDetails(imageA, "Photo by Sam", "model: Ana");
            source.tags().setColor(huskyTag, "BLUE");
            source.tags().setDescription(huskyTag, "Any dog breed");
            Files.createDirectories(source.ref().imagePath(HASH_A, "png").getParent());
            Files.writeString(source.ref().imagePath(HASH_A, "png"), "fake-png-bytes-a");

            int imageB = source.images().insert(HASH_B, "png", "b.png", 100, 100, 1000,
                    System.currentTimeMillis(), null);
            Files.createDirectories(source.ref().imagePath(HASH_B, "png").getParent());
            Files.writeString(source.ref().imagePath(HASH_B, "png"), "fake-png-bytes-b");

            int playlistId = source.playlists().create("Warm-up set");
            source.playlists().addItem(playlistId, imageB);
            source.playlists().addItem(playlistId, imageA);
            source.playlists().setCover(playlistId, imageA);

            Segment filterSegment = Segment.filterSegment(
                    new Filter.Not(new Filter.TagPredicate(huskyTag, true)), 5, OrderMode.SEQUENTIAL,
                    30, TickBehavior.ADVANCE_IMMEDIATELY, 0).withRampToSeconds(10);
            Segment playlistSegment = Segment.playlistSegment(playlistId, null, OrderMode.SEQUENTIAL,
                    0, TickBehavior.ADVANCE_IMMEDIATELY, 0);
            source.presets().create("Queue A", new SessionSpec(List.of(filterSegment, playlistSegment), null));

            Path archive = tempDir.resolve("export.zip");
            ArchiveExporter.export(source, archive, new ArchiveExporter.Options(false, false));

            try (LibraryService dest = new LibraryService(tempDir.resolve("dest").toString())) {
                ArchiveImporter.Summary summary = ArchiveImporter.dryRun(dest, archive);
                assertEquals(2, summary.newImages());
                assertEquals(0, summary.alreadyPresentImages());
                assertEquals(2, summary.newTags());
                assertEquals(1, summary.totalPlaylists());
                assertEquals(1, summary.totalPresets());

                ArchiveImporter.Result result = ArchiveImporter.apply(dest, archive);
                assertEquals(2, result.newImages());
                assertEquals(0, result.taggedExisting());
                assertEquals(2, result.newTagsCreated());
                assertEquals(1, result.playlistsImported());
                assertEquals(1, result.presetsImported());

                assertEquals(2, dest.images().count(new Filter.All()));
                ImageRecord restoredA = dest.images().findByHash(HASH_A).orElseThrow();
                assertEquals("Photo by Sam", restoredA.credit());
                assertEquals("model: Ana", restoredA.notes());
                assertTrue(restoredA.favorite());
                assertTrue(Files.isRegularFile(dest.ref().imagePath(HASH_A, "png")));

                List<Tag> destTags = dest.tags().listAll();
                Optional<Tag> destHusky = destTags.stream().filter(t -> t.name().equals("dog")).findFirst();
                assertTrue(destHusky.isPresent());
                assertEquals("animal / dog", dest.tags().fullPath(destHusky.get().id()));
                Tag destAnimal = dest.tags().get(destHusky.get().parentId()).orElseThrow();
                assertTrue(destAnimal.groupOnly());
                assertTrue(destAnimal.exclusiveChildren());
                assertFalse(destHusky.get().groupOnly());
                assertEquals("BLUE", destHusky.get().color());
                assertEquals("Any dog breed", destHusky.get().description());
                assertTrue(restoredA.tagIds().contains(destHusky.get().id()));

                Playlist destPlaylist = dest.playlists().listAll().get(0);
                assertEquals("Warm-up set", destPlaylist.name());
                assertTrue(destPlaylist.explicitCover());
                assertEquals(restoredA.id(), destPlaylist.coverImageId());
                List<Integer> destPlaylistImageIds = dest.playlists().listImageIds(destPlaylist.id(), false);
                assertEquals(2, destPlaylistImageIds.size());
                assertEquals(HASH_B, dest.images().findById(destPlaylistImageIds.get(0)).orElseThrow().hash());
                assertEquals(HASH_A, dest.images().findById(destPlaylistImageIds.get(1)).orElseThrow().hash());

                Preset destPreset = dest.presets().listAll().get(0);
                assertEquals("Queue A", destPreset.name());
                SessionSpec destSpec = dest.presets().loadSpec(destPreset.id());
                assertEquals(2, destSpec.segments().size());
                Segment destFilterSegment = destSpec.segments().get(0);
                assertEquals(SourceKind.FILTER, destFilterSegment.sourceKind());
                assertEquals(10, destFilterSegment.rampToSeconds());
                assertEquals(new Filter.Not(new Filter.TagPredicate(destHusky.get().id(), true)), destFilterSegment.filter());
                Segment destPlaylistSegment = destSpec.segments().get(1);
                assertEquals(SourceKind.PLAYLIST, destPlaylistSegment.sourceKind());
                assertEquals(destPlaylist.id(), destPlaylistSegment.playlistId());
            }
        }
    }

    @Test
    void anImageEntryWhosePathWouldLeaveImagesIsRefusedBeforeAnythingIsImported(@TempDir Path tempDir) throws IOException {
        Path archive = archiveWith(tempDir, HASH_A, "png/../../../escaped", "fake-png-bytes-a");
        try (LibraryService dest = new LibraryService(tempDir.resolve("dest").toString())) {
            assertThrows(IOException.class, () -> ArchiveImporter.dryRun(dest, archive));
            assertThrows(IOException.class, () -> ArchiveImporter.apply(dest, archive));
            assertEquals(0, dest.images().count(new Filter.All()));
            assertTrue(dest.tags().listAll().isEmpty());
        }
    }

    @Test
    void aFileWhoseContentDoesntMatchItsHashIsNotKept(@TempDir Path tempDir) throws IOException {
        Path archive = archiveWith(tempDir, HASH_A, "png", "something else entirely");
        try (LibraryService dest = new LibraryService(tempDir.resolve("dest").toString())) {
            assertThrows(IOException.class, () -> ArchiveImporter.apply(dest, archive));
            assertFalse(Files.exists(dest.ref().imagePath(HASH_A, "png")));
        }
    }

    @Test
    void anImageWhoseOriginalWasLostIsSkippedNotFatal(@TempDir Path tempDir) throws IOException {
        Path archive;
        try (LibraryService source = new LibraryService(tempDir.resolve("source").toString())) {
            source.images().insert(HASH_A, "png", "a.png", 1, 1, 1, 0, null);
            Files.createDirectories(source.ref().imagePath(HASH_A, "png").getParent());
            Files.writeString(source.ref().imagePath(HASH_A, "png"), "fake-png-bytes-a");
            // B's original is gone from disk, as if deleted outside Gesturtle.
            source.images().insert(HASH_B, "png", "b.png", 1, 1, 1, 0, null);
            archive = tempDir.resolve("export.zip");
            ArchiveExporter.export(source, archive, new ArchiveExporter.Options(false, false));
        }
        assertFalse(Files.exists(tempDir.resolve("export.zip.part")));

        try (LibraryService dest = new LibraryService(tempDir.resolve("dest").toString())) {
            assertEquals(1, ArchiveImporter.dryRun(dest, archive).newImages());
            assertEquals(1, ArchiveImporter.apply(dest, archive).newImages());
            assertTrue(dest.images().findByHash(HASH_A).isPresent());
            assertFalse(dest.images().findByHash(HASH_B).isPresent());
        }
    }

    /** A hand-made archive holding one image entry, the way a crafted or damaged one would look. */
    private static Path archiveWith(Path dir, String hash, String ext, String content) throws IOException {
        String manifest = """
                {"schemaVersion": 1, "tags": ["sneaky"], "playlists": [], "presets": [],
                 "images": [{"hash": "%s", "ext": "%s", "tagPaths": ["sneaky"], "width": 1, "height": 1,
                             "byteSize": 1, "importedAt": 0}]}""".formatted(hash, ext);
        Path archive = dir.resolve("crafted.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("images/" + hash + "." + ext));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return archive;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
