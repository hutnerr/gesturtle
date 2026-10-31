package com.gesturtle.db;

import com.gesturtle.db.dao.ImageDao;
import com.gesturtle.db.dao.PlaylistDao;
import com.gesturtle.model.Playlist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistDaoTest {

    @Test
    void coverFallsBackToFirstImageAndEmptyPlaylistsHaveNone(@TempDir Path tempDir) {
        Database database = new Database(tempDir.resolve("test.db"));
        ImageDao images = new ImageDao(database.connection());
        PlaylistDao playlists = new PlaylistDao(database.connection());
        int a = images.insert("a", "png", "a.png", 1, 1, 1, 1, null);
        int b = images.insert("b", "png", "b.png", 1, 1, 1, 2, null);
        int empty = playlists.create("A empty");
        int full = playlists.create("B full");
        playlists.addItem(full, a);
        playlists.addItem(full, b);

        List<Playlist> listed = playlists.listAll();
        assertNull(listed.get(0).coverImageId());
        assertEquals(a, listed.get(1).coverImageId());
        assertFalse(listed.get(1).explicitCover());

        playlists.setCover(full, b);
        Playlist chosen = playlists.listAll().get(1);
        assertEquals(b, chosen.coverImageId());
        assertTrue(chosen.explicitCover());

        // A chosen cover that leaves the playlist falls back to the first image.
        playlists.removeItem(full, b);
        Playlist fallback = playlists.listAll().get(1);
        assertEquals(a, fallback.coverImageId());
        assertFalse(fallback.explicitCover());
        assertEquals(empty, listed.get(0).id());
        database.close();
    }
}
