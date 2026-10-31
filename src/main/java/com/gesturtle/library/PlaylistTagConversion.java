package com.gesturtle.library;

import com.gesturtle.filter.Filter;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.Playlist;
import com.gesturtle.model.SortOrder;

import java.util.List;

/**
 * Turns a playlist into a tag and a tag into a playlist, for when a set
 * outgrows how it started: a hand-picked playlist that should really be
 * filterable, or a tag that wants a fixed order. Each can keep the original,
 * which makes it a copy rather than a conversion.
 *
 * Both run as one transaction, so a name clash or other failure changes
 * nothing. DB thread only, like the DAOs.
 */
public final class PlaylistTagConversion {
    private PlaylistTagConversion() {
    }

    /**
     * Creates a top-level tag named tagName on every image in the playlist,
     * then deletes the playlist unless keepPlaylist. Returns the new tag's id.
     */
    public static int playlistToTag(LibraryService library, int playlistId, String tagName, boolean keepPlaylist) {
        return library.inTransaction(() -> {
            int tagId = library.tags().create(tagName, null);
            for (int imageId : library.playlists().listImageIds(playlistId, false)) {
                library.images().addTags(imageId, List.of(tagId));
            }
            if (!keepPlaylist) library.playlists().delete(playlistId);
            return tagId;
        });
    }

    /**
     * Creates a playlist named playlistName holding every image with the tag
     * or any tag under it, oldest import first, then deletes the tag (its
     * children move up a level) unless keepTag. Returns the new playlist's id.
     */
    public static int tagToPlaylist(LibraryService library, int tagId, String playlistName, boolean keepTag) {
        return library.inTransaction(() -> {
            boolean taken = library.playlists().listAll().stream().map(Playlist::name)
                    .anyMatch(playlistName::equalsIgnoreCase);
            if (taken) throw new IllegalStateException("A playlist named \"" + playlistName + "\" already exists");
            int playlistId = library.playlists().create(playlistName);
            for (ImageRecord image : library.images().query(new Filter.TagPredicate(tagId, true), SortOrder.OLDEST, null)) {
                library.playlists().addItem(playlistId, image.id());
            }
            if (!keepTag) library.tags().deleteReassignChildren(tagId);
            return playlistId;
        });
    }
}
