package com.gesturtle.meta;

import com.gesturtle.filter.Filter;
import com.gesturtle.library.LibraryService;
import com.gesturtle.model.OrderMode;
import com.gesturtle.model.Segment;
import com.gesturtle.model.SessionSpec;
import com.gesturtle.model.Tag;
import com.gesturtle.model.TickBehavior;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaStoreTest {
    private static final String HASH_A = "aa11" + "0".repeat(60);
    private static final String HASH_B = "bb22" + "0".repeat(60);

    private static LibraryService open(Path root) {
        return new LibraryService(root.toString());
    }

    private static int addImage(LibraryService library, String hash, String name) {
        return library.images().insert(hash, "jpg", name, 100, 200, 1234, 1000, "/from/" + name);
    }

    private static JsonObject read(Path root, String metaPath) throws IOException {
        return JsonParser.parseString(Files.readString(root.resolve("meta").resolve(metaPath))).getAsJsonObject();
    }

    private static String imagePath(String hash) {
        return MetaFiles.path(MetaFiles.IMAGE, hash);
    }

    private static Tag tagNamed(LibraryService library, String name) {
        return library.tags().listAll().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    /** Copies one library's meta/ over another's, the way a git pull would bring it. */
    private static void copyMeta(Path from, Path to) throws IOException {
        Path source = from.resolve("meta");
        try (var walk = Files.walk(source)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve("meta").resolve(source.relativize(p).toString());
                Files.createDirectories(target.getParent());
                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** meta/-relative path → contents. */
    private static java.util.Map<String, String> contents(Path root) throws IOException {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        Path meta = root.resolve("meta");
        try (var walk = Files.walk(meta)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) out.put(meta.relativize(p).toString(), Files.readString(p));
        }
        return out;
    }

    /** Brings over only the files one side changed since base, as a clean git merge would. */
    private static void copyChanged(Path from, Path to, java.util.Map<String, String> base) throws IOException {
        for (var e : contents(from).entrySet()) {
            if (e.getValue().equals(base.get(e.getKey()))) continue;
            Path target = to.resolve("meta").resolve(e.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, e.getValue());
        }
    }

    @Test
    void editsAreWrittenToMetaFilesNamedByHashAndUid(@TempDir Path root) throws Exception {
        try (LibraryService library = open(root)) {
            int image = addImage(library, HASH_A, "pose.jpg");
            int figure = library.tags().create("figure", null);
            int hands = library.tags().create("hands", figure);
            library.images().addTags(image, List.of(hands));
            library.images().setFavorite(image, true);
            library.images().setDetails(image, "someone", "nice light");
            library.flushMeta();

            JsonObject json = read(root, imagePath(HASH_A));
            assertEquals("pose.jpg", json.get("name").getAsString());
            assertTrue(json.get("favorite").getAsBoolean());
            assertEquals("someone", json.get("credit").getAsString());
            String handsUid = json.getAsJsonArray("tags").get(0).getAsString();
            JsonObject handsFile = read(root, MetaFiles.path(MetaFiles.TAG, handsUid));
            assertEquals("hands", handsFile.get("name").getAsString());
            assertNotNull(handsFile.get("parent"), "the parent is named by uid");
            assertEquals("figure", read(root, MetaFiles.path(MetaFiles.TAG, handsFile.get("parent").getAsString()))
                    .get("name").getAsString());
            assertFalse(json.has("seen_count") || json.has("seen"), "stats never go in meta/");
        }
    }

    @Test
    void practicingChangesNoMetaFile(@TempDir Path root) throws Exception {
        try (LibraryService library = open(root)) {
            int image = addImage(library, HASH_A, "pose.jpg");
            int preset = library.presets().create("Warmup", SessionSpec.single(
                    Segment.filterSegment(new Filter.All(), 5, OrderMode.RANDOM, 30, TickBehavior.ADVANCE_IMMEDIATELY, 0)));
            library.flushMeta();
            Path imageFile = root.resolve("meta").resolve(imagePath(HASH_A));
            String before = Files.readString(imageFile);
            long modified = Files.getLastModifiedTime(imageFile).toMillis();

            int session = library.views().startSession(preset, 5000);
            library.images().recordView(image, session, 6000, 30000);
            library.views().endSession(session, 7000, 1);
            library.presets().recordUsed(preset);
            assertFalse(hasDirty(root), "nothing is waiting to be written");
            library.flushMeta();

            assertEquals(before, Files.readString(imageFile));
            assertEquals(modified, Files.getLastModifiedTime(imageFile).toMillis());
        }
    }

    private static boolean hasDirty(Path root) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("gesturtle.db"));
             Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM meta_dirty")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Test
    void aFreshCloneRebuildsEverythingFromMeta(@TempDir Path machineA, @TempDir Path machineB) throws Exception {
        try (LibraryService a = open(machineA)) {
            int image1 = addImage(a, HASH_A, "one.jpg");
            int image2 = addImage(a, HASH_B, "two.jpg");
            int figure = a.tags().create("figure", null);
            int hands = a.tags().create("hands", figure);
            a.tags().setColor(hands, "RED");
            a.images().addTags(image1, List.of(hands));
            a.images().addTags(image2, List.of(figure));
            int playlist = a.playlists().create("Study");
            a.playlists().addItem(playlist, image2);
            a.playlists().addItem(playlist, image1);
            a.playlists().setCover(playlist, image1);
            int view = a.savedViews().create("Hands only", new Filter.TagPredicate(hands, true));
            a.presets().create("Mixed", new SessionSpec(List.of(
                    Segment.playlistSegment(playlist, 2, OrderMode.SEQUENTIAL, 60, TickBehavior.BREAK, 5),
                    Segment.viewSegment(view, null, OrderMode.RANDOM, 120, TickBehavior.ADVANCE_IMMEDIATELY, 0)
                            .withRampToSeconds(30)), null));
            a.settings().set("gallery_default_view", String.valueOf(view));
            a.settings().set("gallery_sort", "NAME");
        }
        copyMeta(machineA, machineB);

        try (LibraryService b = open(machineB)) {
            Tag hands = tagNamed(b, "hands");
            assertEquals(tagNamed(b, "figure").id(), hands.parentId());
            assertEquals("RED", hands.color());
            int image1 = b.images().findByHash(HASH_A).orElseThrow().id();
            int image2 = b.images().findByHash(HASH_B).orElseThrow().id();
            assertEquals(List.of(hands.id()), b.images().findById(image1).orElseThrow().tagIds());

            var playlist = b.playlists().listAll().get(0);
            assertEquals("Study", playlist.name());
            assertEquals(List.of(image2, image1), b.playlists().listImageIds(playlist.id(), false), "order is kept");
            assertEquals(image1, playlist.coverImageId());

            var view = b.savedViews().listAll().get(0);
            assertEquals(new Filter.TagPredicate(hands.id(), true), view.filter(), "the filter points at this machine's tag");
            assertEquals(1, b.images().count(view.filter()));

            var preset = b.presets().listAll().get(0);
            List<Segment> segments = b.presets().loadSpec(preset.id()).segments();
            assertEquals(playlist.id(), segments.get(0).playlistId());
            assertEquals(TickBehavior.BREAK, segments.get(0).tickBehavior());
            assertEquals(view.id(), segments.get(1).viewId());
            assertEquals(30, segments.get(1).rampToSeconds());

            assertEquals(String.valueOf(view.id()), b.settings().get("gallery_default_view").orElseThrow());
            assertTrue(b.settings().get("gallery_sort").isEmpty(), "per-machine settings don't travel");
        }
    }

    @Test
    void reopeningRewritesNothing(@TempDir Path root) throws Exception {
        try (LibraryService library = open(root)) {
            int image = addImage(library, HASH_A, "one.jpg");
            library.images().addTags(image, List.of(library.tags().create("hands", null)));
            library.savedViews().create("All", new Filter.All());
        }
        Set<String> before = snapshot(root);
        try (LibraryService library = open(root)) {
            assertFalse(library.openedWith().changed());
        }
        assertEquals(before, snapshot(root));
    }

    /** Every meta/ file with its contents and modified time. */
    private static Set<String> snapshot(Path root) throws IOException {
        try (var walk = Files.walk(root.resolve("meta"))) {
            return walk.filter(Files::isRegularFile).map(p -> {
                try {
                    return p + "|" + Files.readString(p) + "|" + Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toSet());
        }
    }

    @Test
    void changesFromAPullAreReadInAndRemovedFilesAreDropped(@TempDir Path root) throws Exception {
        try (LibraryService library = open(root)) {
            addImage(library, HASH_A, "one.jpg");
            addImage(library, HASH_B, "two.jpg");
            library.tags().create("old tag", null);
            library.flushMeta();
            assertFalse(library.metaChangedOnDisk());

            Path imageFile = root.resolve("meta").resolve(imagePath(HASH_A));
            JsonObject json = read(root, imagePath(HASH_A));
            json.addProperty("favorite", true);
            json.addProperty("notes", "from the other machine");
            Files.writeString(imageFile, MetaWriter.GSON.toJson(json) + "\n");
            Files.delete(root.resolve("meta").resolve(imagePath(HASH_B)));
            String tagUid = tagUid(root, "old tag");
            Files.delete(root.resolve("meta").resolve(MetaFiles.path(MetaFiles.TAG, tagUid)));

            assertTrue(library.metaChangedOnDisk());
            MetaStore.SyncResult result = library.syncMeta();
            assertEquals(3, result.applied());
            assertTrue(result.unreadable().isEmpty());

            var image = library.images().findByHash(HASH_A).orElseThrow();
            assertTrue(image.favorite());
            assertEquals("from the other machine", image.notes());
            assertTrue(library.images().findByHash(HASH_B).isEmpty());
            assertTrue(library.tags().listAll().isEmpty());
            assertFalse(library.metaChangedOnDisk());
        }
    }

    private static String tagUid(Path root, String name) throws IOException {
        try (var files = Files.list(root.resolve("meta/tags"))) {
            for (Path p : files.toList()) {
                if (JsonParser.parseString(Files.readString(p)).getAsJsonObject().get("name").getAsString().equals(name)) {
                    return p.getFileName().toString().replace(".json", "");
                }
            }
        }
        throw new AssertionError("no tag file named " + name);
    }

    @Test
    void theSameTagMadeOnTwoMachinesBecomesOne(@TempDir Path machineA, @TempDir Path machineB) throws Exception {
        try (LibraryService a = open(machineA)) {
            addImage(a, HASH_A, "one.jpg");
            addImage(a, HASH_B, "two.jpg");
        }
        copyMeta(machineA, machineB);
        java.util.Map<String, String> base = contents(machineA);
        try (LibraryService a = open(machineA); LibraryService b = open(machineB)) {
            int aHands = a.tags().create("hands", null);
            a.tags().create("left", aHands);
            a.images().addTags(a.images().findByHash(HASH_A).orElseThrow().id(), List.of(aHands));
            int bHands = b.tags().create("hands", null);
            b.tags().create("left", bHands);
            b.images().addTags(b.images().findByHash(HASH_B).orElseThrow().id(), List.of(bHands));
            b.savedViews().create("Hands", new Filter.TagPredicate(bHands, false));
        }
        // A pulls B's changes: git merges them cleanly, since each side changed different files.
        copyChanged(machineB, machineA, base);

        try (LibraryService a = open(machineA)) {
            List<Tag> tags = a.tags().listAll();
            assertEquals(2, tags.size(), "one hands, one left under it: " + tags);
            Tag hands = tagNamed(a, "hands");
            assertEquals(hands.id(), tagNamed(a, "left").parentId());
            assertEquals(List.of(hands.id()), a.images().findByHash(HASH_A).orElseThrow().tagIds());
            assertEquals(List.of(hands.id()), a.images().findByHash(HASH_B).orElseThrow().tagIds());
            assertEquals(new Filter.TagPredicate(hands.id(), false), a.savedViews().listAll().get(0).filter());
            try (var files = Files.list(machineA.resolve("meta/tags"))) {
                assertEquals(2, files.count(), "the merged-away tag files are gone");
            }
        }
    }

    @Test
    void aFileWithConflictMarkersIsReportedAndLeftAlone(@TempDir Path root) throws Exception {
        try (LibraryService library = open(root)) {
            addImage(library, HASH_A, "one.jpg");
            library.images().setDetails(library.images().findByHash(HASH_A).orElseThrow().id(), null, "mine");
            library.flushMeta();
            Path file = root.resolve("meta").resolve(imagePath(HASH_A));
            Files.writeString(file, "<<<<<<< HEAD\n{\"notes\": \"mine\"}\n=======\n{\"notes\": \"theirs\"}\n>>>>>>> origin\n");

            MetaStore.SyncResult result = library.syncMeta();

            assertEquals(List.of(imagePath(HASH_A)), result.unreadable());
            assertEquals("mine", library.images().findByHash(HASH_A).orElseThrow().notes());
            assertTrue(Files.readString(file).startsWith("<<<<<<<"), "Gesturtle doesn't overwrite it");
        }
    }

    @Test
    void aFileOfTheWrongShapeIsReportedWithoutStoppingTheRest(@TempDir Path root) throws Exception {
        try (LibraryService library = open(root)) {
            addImage(library, HASH_A, "one.jpg");
            library.flushMeta();
            String viewPath = MetaFiles.path(MetaFiles.VIEW, "0123456789abcdef0123456789abcdef");
            Path view = root.resolve("meta").resolve(viewPath);
            Files.createDirectories(view.getParent());
            // Valid JSON, but the tag condition is missing its fields.
            Files.writeString(view, "{\"schemaVersion\": 1, \"name\": \"Broken\", \"filter\": {\"type\": \"tag\"}}\n");
            Path image = root.resolve("meta").resolve(imagePath(HASH_A));
            Files.writeString(image, "{\"schemaVersion\": 1, \"ext\": \"jpg\", \"width\": \"wide\", \"notes\": \"theirs\"}\n");

            MetaStore.SyncResult result = library.syncMeta();

            assertEquals(List.of(viewPath), result.unreadable());
            assertTrue(library.savedViews().listAll().isEmpty());
            assertEquals("theirs", library.images().findByHash(HASH_A).orElseThrow().notes());
            assertTrue(Files.readString(view).contains("Broken"), "Gesturtle doesn't overwrite it");
        }
    }

    @Test
    void readingAPullKeepsChangesNotYetWritten(@TempDir Path root) throws Exception {
        try (LibraryService library = open(root)) {
            addImage(library, HASH_A, "one.jpg");
            addImage(library, HASH_B, "two.jpg");
            library.flushMeta();
            library.images().setDetails(library.images().findByHash(HASH_A).orElseThrow().id(), null, "not flushed yet");
            Files.writeString(root.resolve("meta").resolve(imagePath(HASH_B)),
                    "{\"schemaVersion\": 1, \"ext\": \"jpg\", \"notes\": \"pulled\"}\n");

            library.syncMeta();
            library.flushMeta();

            assertTrue(Files.readString(root.resolve("meta").resolve(imagePath(HASH_A))).contains("not flushed yet"));
        }
    }

    @Test
    void filesCarryAVersionAndOnesFromANewerVersionAreLeftAlone(@TempDir Path root) throws Exception {
        try (LibraryService library = open(root)) {
            addImage(library, HASH_A, "one.jpg");
            library.images().setDetails(library.images().findByHash(HASH_A).orElseThrow().id(), null, "mine");
            library.flushMeta();
            Path file = root.resolve("meta").resolve(imagePath(HASH_A));
            assertTrue(Files.readString(file).startsWith("{\n  \"schemaVersion\": 1,"), Files.readString(file));

            String newer = "{\"schemaVersion\": 99, \"ext\": \"jpg\", \"notes\": \"from the future\"}\n";
            Files.writeString(file, newer);
            MetaStore.SyncResult result = library.syncMeta();
            assertEquals(List.of(imagePath(HASH_A)), result.tooNew());
            assertEquals("mine", library.images().findByHash(HASH_A).orElseThrow().notes());

            // A local edit doesn't write the older format over it either.
            library.images().setDetails(library.images().findByHash(HASH_A).orElseThrow().id(), null, "edited here");
            library.flushMeta();
            assertEquals(newer, Files.readString(file));
        }
    }
}
