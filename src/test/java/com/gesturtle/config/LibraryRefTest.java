package com.gesturtle.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryRefTest {
    @Test
    void layoutIncludesGitIgnoreForCachesAndSideFiles(@TempDir Path tempDir) throws Exception {
        new LibraryRef(tempDir).ensureLayout();

        String ignore = Files.readString(tempDir.resolve(".gitignore"));
        assertTrue(ignore.contains("/thumbs/"));
        assertTrue(ignore.contains("/gesturtle.db*"), "the database is local, rebuilt from meta/");
        String attributes = Files.readString(tempDir.resolve(".gitattributes"));
        assertTrue(attributes.contains("/images/** binary"));
        assertTrue(attributes.contains("/meta/** text eol=lf"));
        assertTrue(Files.isDirectory(tempDir.resolve("meta")));
    }

    @Test
    void anEditedGitIgnoreStillGetsTheDatabaseLeftOut(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), "/thumbs/\n/my-notes.txt");

        new LibraryRef(tempDir).ensureLayout();
        new LibraryRef(tempDir).ensureLayout();

        String ignore = Files.readString(tempDir.resolve(".gitignore"));
        assertTrue(ignore.startsWith("/thumbs/\n/my-notes.txt\n"), "the user's rules stay as they were");
        assertEquals(1, ignore.lines().filter("/gesturtle.db*"::equals).count(), "added once, not on every open");
    }

    @Test
    void existingGitFilesAreLeftAlone(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve(".gitattributes"), "images/** filter=lfs diff=lfs merge=lfs -text\n");

        new LibraryRef(tempDir).ensureLayout();

        assertEquals("images/** filter=lfs diff=lfs merge=lfs -text\n", Files.readString(tempDir.resolve(".gitattributes")));
    }

    @Test
    void thumbnailsFromAnOlderVersionAreDroppedOnceOnOpen(@TempDir Path tempDir) throws Exception {
        LibraryRef ref = new LibraryRef(tempDir);
        Path old = ref.thumbPath("abcdef0123");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "old thumb");

        ref.ensureLayout();
        assertFalse(Files.exists(old));
        assertTrue(Files.isDirectory(ref.thumbsDir()));

        Path fresh = ref.thumbPath("abcdef0123");
        Files.createDirectories(fresh.getParent());
        Files.writeString(fresh, "new thumb");
        ref.ensureLayout();
        assertTrue(Files.exists(fresh), "current thumbnails survive a reopen");
    }
}
