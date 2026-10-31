package com.gesturtle.ui.gallery;

import com.gesturtle.library.ImageTrash;
import com.gesturtle.library.LibraryService;
import com.gesturtle.library.SystemTrash;
import com.gesturtle.library.UndoCapture;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.ui.common.UndoBar;
import com.gesturtle.ui.tags.TagPicker;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Shared tag/favorite/playlist/delete actions for the gallery grid/list, playlists, and the detail view. */
public final class GalleryActions {
    private GalleryActions() {
    }

    /**
     * Opens the tag picker pre-checked with the tags common to every given
     * image, then applies the diff to all of them: newly-checked tags are
     * added, newly-unchecked ones are removed. Returns whether anything was
     * applied (false if the picker was cancelled).
     */
    public static boolean editTags(LibraryService library, List<ImageRecord> images) {
        if (images.isEmpty()) return false;
        Set<Integer> common = images.stream()
                .map(r -> (Set<Integer>) new LinkedHashSet<>(r.tagIds()))
                .reduce((a, b) -> {
                    a.retainAll(b);
                    return a;
                })
                .orElseGet(LinkedHashSet::new);
        Optional<Set<Integer>> picked = TagPicker.pickMany(library, common);
        if (picked.isEmpty()) return false;
        Set<Integer> toAdd = new LinkedHashSet<>(picked.get());
        toAdd.removeAll(common);
        Set<Integer> toRemove = new LinkedHashSet<>(common);
        toRemove.removeAll(picked.get());
        String ids = String.join(", ", images.stream().map(r -> String.valueOf(r.id())).toList());
        UndoCapture undo = library.captureForUndo(
                UndoCapture.Table.where("image_tag", List.of(), "image_id IN (" + ids + ")"));
        for (ImageRecord r : images) {
            if (!toAdd.isEmpty()) library.images().addTags(r.id(), toAdd);
            for (int tagId : toRemove) library.images().removeTag(r.id(), tagId);
        }
        UndoBar.offer(images.size() == 1 ? "Changed the image's tags" : "Changed tags on " + images.size() + " images",
                undo.after());
        return true;
    }

    /** Favorites all the images if any aren't favorited yet, otherwise unfavorites all of them. */
    public static void toggleFavorite(LibraryService library, List<ImageRecord> images) {
        boolean favorite = images.stream().anyMatch(r -> !r.favorite());
        for (ImageRecord r : images) library.images().setFavorite(r.id(), favorite);
        com.gesturtle.ui.common.Sounds.play(favorite ? com.gesturtle.ui.common.Sounds.Sound.FAVORITE
                : com.gesturtle.ui.common.Sounds.Sound.UNFAVORITE);
    }

    /**
     * Asks which playlist (or a new one) and adds the images to it. Leaves
     * out the playlist they're being moved from, if any. Returns whether
     * anything was added.
     */
    public static boolean addToPlaylist(LibraryService library, List<ImageRecord> images, Integer exceptPlaylistId) {
        if (images.isEmpty()) return false;
        String newPlaylist = "New playlist…";
        List<com.gesturtle.model.Playlist> playlists = library.playlists().listAll().stream()
                .filter(p -> exceptPlaylistId == null || p.id() != exceptPlaylistId).toList();
        List<String> choices = new java.util.ArrayList<>(playlists.stream().map(com.gesturtle.model.Playlist::name).toList());
        choices.add(newPlaylist);
        javafx.scene.control.ChoiceDialog<String> dialog = new javafx.scene.control.ChoiceDialog<>(choices.get(0), choices);
        dialog.setTitle("Add to playlist");
        dialog.setContentText("Playlist:");
        com.gesturtle.ui.common.Dialogs.prepare(dialog);
        String choice = dialog.showAndWait().orElse(null);
        if (choice == null) return false;
        Integer playlistId;
        if (newPlaylist.equals(choice)) {
            String name = com.gesturtle.ui.common.Dialogs.prompt("New playlist", "Name", "").orElse(null);
            if (name == null) return false;
            playlistId = library.playlists().create(name);
        } else {
            playlistId = playlists.stream().filter(p -> p.name().equals(choice)).findFirst()
                    .map(com.gesturtle.model.Playlist::id).orElse(null);
        }
        if (playlistId == null) return false;
        for (ImageRecord r : images) library.playlists().addItem(playlistId, r.id());
        com.gesturtle.ui.common.Sounds.play(com.gesturtle.ui.common.Sounds.Sound.CONFIRM);
        return true;
    }

    /** Asks, then moves the images' files to the system trash. Returns whether any were moved. */
    public static boolean deleteToTrash(LibraryService library, List<ImageRecord> images) {
        if (images.isEmpty()) return false;
        String count = images.size() + (images.size() == 1 ? " image" : " images");
        if (!com.gesturtle.ui.common.Dialogs.confirmDanger("Delete images", "Move " + count + " to the " + SystemTrash.NAME + "?", "Delete")) {
            return false;
        }
        for (ImageRecord r : images) {
            if (!com.gesturtle.ui.common.Dialogs.tryRun(() -> ImageTrash.delete(library, r), "Could not delete an image")) break;
        }
        com.gesturtle.ui.common.Sounds.play(com.gesturtle.ui.common.Sounds.Sound.DELETE);
        return true;
    }
}
