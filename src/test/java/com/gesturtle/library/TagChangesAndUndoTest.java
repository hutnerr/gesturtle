package com.gesturtle.library;

import com.gesturtle.filter.Filter;
import com.gesturtle.model.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tag merge/delete edge cases, and undoing them and other destructive actions with UndoCapture. */
class TagChangesAndUndoTest {

    private static UndoCapture captureTags(LibraryService library) {
        return library.captureForUndo(UndoCapture.Table.all("tag", "id"), UndoCapture.Table.all("image_tag"),
                UndoCapture.Table.all("saved_view", "id"), UndoCapture.Table.all("segment", "id"));
    }

    private static int image(LibraryService library, String hash) {
        return library.images().insert(hash, "png", hash + ".png", 1, 1, 1, 0, null);
    }

    @Test
    void mergingIntoOwnChildIsRefusedAndChangesNothing(@TempDir Path dir) {
        try (LibraryService library = new LibraryService(dir.resolve("lib").toString())) {
            int animal = library.tags().create("animal", null);
            int dog = library.tags().create("dog", animal);
            int img = image(library, "a".repeat(64));
            library.images().addTags(img, List.of(animal));

            assertThrows(IllegalStateException.class, () -> library.tags().merge(animal, dog));
            assertEquals(2, library.tags().listAll().size());
            assertEquals(List.of(animal), library.images().findById(img).orElseThrow().tagIds());
        }
    }

    @Test
    void mergeCombinesSameNamedChildrenAndRepointsSavedViews(@TempDir Path dir) {
        try (LibraryService library = new LibraryService(dir.resolve("lib").toString())) {
            int people = library.tags().create("people", null);
            int peopleHands = library.tags().create("hands", people);
            int figures = library.tags().create("figures", null);
            int figuresHands = library.tags().create("hands", figures);
            int img = image(library, "b".repeat(64));
            library.images().addTags(img, List.of(peopleHands));
            int view = library.savedViews().create("Hands", new Filter.TagPredicate(peopleHands, true));

            library.tags().merge(people, figures);

            assertTrue(library.tags().get(people).isEmpty());
            assertTrue(library.tags().get(peopleHands).isEmpty(), "merged into figures / hands");
            assertEquals(Set.of(figuresHands), Set.copyOf(library.images().findById(img).orElseThrow().tagIds()));
            assertEquals(new Filter.TagPredicate(figuresHands, true),
                    library.savedViews().listAll().stream().filter(v -> v.id() == view).findFirst().orElseThrow().filter());
        }
    }

    @Test
    void deletingATagWhoseChildNameIsTakenAboveMergesThem(@TempDir Path dir) {
        try (LibraryService library = new LibraryService(dir.resolve("lib").toString())) {
            int hands = library.tags().create("hands", null);
            int poses = library.tags().create("poses", null);
            int posesHands = library.tags().create("hands", poses);
            int img = image(library, "c".repeat(64));
            library.images().addTags(img, List.of(posesHands));

            library.tags().deleteReassignChildren(poses);

            assertEquals(List.of(hands), library.tags().listAll().stream().map(Tag::id).toList());
            assertEquals(List.of(hands), library.images().findById(img).orElseThrow().tagIds());
        }
    }

    @Test
    void undoingADeleteBringsBackTheTagItsChildrenAndItsImages(@TempDir Path dir) {
        try (LibraryService library = new LibraryService(dir.resolve("lib").toString())) {
            int animal = library.tags().create("animal", null);
            int dog = library.tags().create("dog", animal);
            int img = image(library, "d".repeat(64));
            library.images().addTags(img, List.of(animal));
            Set<String> filesBefore = tagFiles(library);

            UndoCapture capture = captureTags(library);
            library.tags().deleteReassignChildren(animal);
            Runnable undo = capture.after();
            assertEquals(1, tagFiles(library).size());
            int other = image(library, "e".repeat(64));
            library.images().addTags(other, List.of(dog)); // made after: undo leaves it alone
            undo.run();

            Tag restored = library.tags().get(animal).orElseThrow();
            assertEquals("animal", restored.name());
            assertEquals(filesBefore, tagFiles(library), "same uid, so its meta/ file comes back too");
            assertEquals(animal, library.tags().get(dog).orElseThrow().parentId());
            assertEquals(List.of(animal), library.images().findById(img).orElseThrow().tagIds());
            assertEquals(List.of(dog), library.images().findById(other).orElseThrow().tagIds());
        }
    }

    @Test
    void deletingWithDescendantsRemovesTheWholeSubtreeAndUndoBringsItBack(@TempDir Path dir) {
        try (LibraryService library = new LibraryService(dir.resolve("lib").toString())) {
            int animal = library.tags().create("animal", null);
            int dog = library.tags().create("dog", animal);
            int husky = library.tags().create("husky", dog);
            int plant = library.tags().create("plant", null);
            int img = image(library, "f".repeat(64));
            library.images().addTags(img, List.of(husky, plant));
            Set<String> filesBefore = tagFiles(library);
            assertEquals(2, library.tags().countDescendants(animal));

            UndoCapture capture = captureTags(library);
            library.tags().deleteWithDescendants(animal);
            Runnable undo = capture.after();
            assertEquals(List.of(plant), library.tags().listAll().stream().map(Tag::id).toList());
            assertEquals(List.of(plant), library.images().findById(img).orElseThrow().tagIds());
            assertEquals(1, tagFiles(library).size(), "their meta/ files go too");
            undo.run();

            assertEquals(dog, library.tags().get(husky).orElseThrow().parentId());
            assertEquals(animal, library.tags().get(dog).orElseThrow().parentId());
            assertEquals(Set.of(husky, plant), Set.copyOf(library.images().findById(img).orElseThrow().tagIds()));
            assertEquals(filesBefore, tagFiles(library));
        }
    }

    @Test
    void playlistToTagTagsItsImagesAndKeepsOrDropsThePlaylist(@TempDir Path dir) {
        try (LibraryService library = new LibraryService(dir.resolve("lib").toString())) {
            int a = image(library, "1".repeat(64));
            int b = image(library, "2".repeat(64));
            int kept = library.playlists().create("Hands");
            library.playlists().addItem(kept, a);
            library.playlists().addItem(kept, b);

            int tag = PlaylistTagConversion.playlistToTag(library, kept, "Hands", true);
            assertEquals(List.of(tag), library.images().findById(a).orElseThrow().tagIds());
            assertEquals(List.of(tag), library.images().findById(b).orElseThrow().tagIds());
            assertEquals(1, library.playlists().listAll().size(), "kept: a copy");

            assertThrows(IllegalStateException.class, () -> PlaylistTagConversion.playlistToTag(library, kept, "Hands", false));
            assertEquals(1, library.playlists().listAll().size(), "a name clash changes nothing");

            PlaylistTagConversion.playlistToTag(library, kept, "Hands 2", false);
            assertTrue(library.playlists().listAll().isEmpty(), "converted: playlist gone");
        }
    }

    @Test
    void tagToPlaylistTakesImagesUnderTheTagOldestFirst(@TempDir Path dir) {
        try (LibraryService library = new LibraryService(dir.resolve("lib").toString())) {
            int animal = library.tags().create("animal", null);
            int dog = library.tags().create("dog", animal);
            int older = library.images().insert("3".repeat(64), "png", "a.png", 1, 1, 1, 100, null);
            int newer = library.images().insert("4".repeat(64), "png", "b.png", 1, 1, 1, 200, null);
            library.images().addTags(newer, List.of(animal));
            library.images().addTags(older, List.of(dog));

            int playlist = PlaylistTagConversion.tagToPlaylist(library, animal, "Animals", false);
            assertEquals(List.of(older, newer), library.playlists().listImageIds(playlist, false));
            assertTrue(library.tags().get(animal).isEmpty(), "converted: tag gone");
            assertNull(library.tags().get(dog).orElseThrow().parentId(), "its child moves up");
            assertThrows(IllegalStateException.class, () -> PlaylistTagConversion.tagToPlaylist(library, dog, "animals", true));
        }
    }

    @Test
    void undoingAPlaylistRemovalPutsImagesBackInOrder(@TempDir Path dir) {
        try (LibraryService library = new LibraryService(dir.resolve("lib").toString())) {
            int playlist = library.playlists().create("Warm-up");
            int a = image(library, "f".repeat(64));
            int b = image(library, "0".repeat(64));
            library.playlists().addItem(playlist, a);
            library.playlists().addItem(playlist, b);

            UndoCapture capture = library.captureForUndo(
                    UndoCapture.Table.where("playlist_item", List.of(), "playlist_id = ?", playlist));
            library.playlists().removeItem(playlist, a);
            capture.after().run();

            assertEquals(List.of(a, b), library.playlists().listImageIds(playlist, false));
        }
    }

    @Test
    void anActionThatChangedNothingOffersNoUndo(@TempDir Path dir) {
        try (LibraryService library = new LibraryService(dir.resolve("lib").toString())) {
            UndoCapture capture = captureTags(library);
            assertNull(capture.after());
            assertFalse(library.tags().listAll().iterator().hasNext());
        }
    }

    /** The tag files in meta/, after writing out pending changes. */
    private static Set<String> tagFiles(LibraryService library) {
        library.flushMeta();
        try (var files = java.nio.file.Files.list(library.ref().metaDir().resolve("tags"))) {
            return files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
