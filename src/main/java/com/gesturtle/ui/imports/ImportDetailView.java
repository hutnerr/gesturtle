package com.gesturtle.ui.imports;

import com.gesturtle.library.LibraryService;
import com.gesturtle.model.ImportCandidate;
import com.gesturtle.model.Tag;
import com.gesturtle.ui.common.ImageLoader;
import com.gesturtle.ui.common.Shortcuts;
import com.gesturtle.ui.common.Sounds;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.Toolbar;
import com.gesturtle.ui.components.ViewerScreen;
import com.gesturtle.ui.gallery.EditPanel;
import com.gesturtle.ui.playlist.PlaylistChecklist;
import com.gesturtle.ui.tags.TagActions;
import com.gesturtle.ui.tags.TagPicker;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A staged file at full size, opened from the import list over the Import
 * page the way the Gallery's detail view opens over the Gallery, to look it
 * over and tag it before importing. It is always in what the Gallery calls
 * Edit mode: a staged file has no history or details to show yet, so the
 * EditPanel takes the side. Ticking a tag or playlist, or the favorite star
 * (F), applies it to the file showing straight away (tags on top of the
 * batch tags), so you can tag, press →, and tag the next. T jumps into the
 * tag tree.
 *
 * Unlike the detail view nothing is written to the library here: tags,
 * playlists and favorites stay on the ImportCandidate until the import
 * runs, so a tag deleted or merged from the tag menu has to be fixed up on
 * the staged files by hand.
 */
final class ImportDetailView extends ViewerScreen {
    private final LibraryService library;
    private final Label nameLabel = new Label();
    private final Label positionLabel = new Label();
    private final ToggleButton favoriteButton = AppButton.small(AppButton.favoriteToggle(AppButton.Variant.GHOST));
    private final TagPicker tagPicker;
    private final PlaylistChecklist playlists;
    private final Consumer<ImportCandidate> onShown;
    private final Runnable onChanged;
    private List<ImportCandidate> files = List.of();
    private int index;
    private ImportCandidate shownFile;

    /**
     * onShown hears each file as it comes up, so the list can follow along,
     * and onChanged hears each tag ticked or unticked and each favorite
     * change, so the list can show it. Playlists ticked are only kept on the
     * file, for the import to add it to.
     */
    ImportDetailView(LibraryService library, Runnable onClose, Consumer<ImportCandidate> onShown, Runnable onChanged) {
        super(onClose);
        this.library = library;
        this.onShown = onShown;
        this.onChanged = onChanged;

        nameLabel.setMinWidth(0);
        positionLabel.getStyleClass().add("muted-label");
        // The click has already flipped the button; this records it and makes the sound (the button is quiet).
        favoriteButton.setOnAction(e -> applyFavorite(favoriteButton.isSelected()));
        List<Node> viewerTools = viewer.toolButtons();
        AppButton backButton = AppButton.icon("arrow-left", "Back to the list (Esc)", this::close);
        Toolbar topBar = new Toolbar()
                .left(backButton, nameLabel)
                .center(AppButton.icon("chevron-left", Shortcuts.hint(Shortcuts.Action.PREVIOUS), () -> move(-1)), positionLabel,
                        AppButton.icon("chevron-right", Shortcuts.hint(Shortcuts.Action.NEXT), () -> move(1)))
                .right(viewerTools.toArray(Node[]::new))
                .right(Toolbar.divider(), favoriteButton, Toolbar.divider())
                .right(studyModeButtons())
                .collapsible(backButton)
                .overflowable(viewerTools);
        setTopBar(topBar);

        tagPicker = new TagPicker(library, true);
        tagPicker.setOnChange(this::applyTags);
        tagPicker.enableTagMenu(new TagActions(library, this::afterTagChange)
                .onMerged((source, target) -> {
                    for (ImportCandidate file : files) {
                        if (file.tagOverrides().remove(source) && !file.tagOverrides().contains(target)) {
                            file.tagOverrides().add(target);
                        }
                    }
                }));
        playlists = new PlaylistChecklist(library, "Add to playlists");
        playlists.setOnChange(this::applyPlaylists);
        setSidePanel(new EditPanel(tagPicker, playlists));
    }

    /**
     * Opens on files[startIndex]. The list is copied, so files that finish
     * probing (or drop out as duplicates) meanwhile don't shift what → steps to.
     */
    void open(List<ImportCandidate> files, int startIndex) {
        this.files = List.copyOf(files);
        this.index = startIndex;
        shownFile = null;
        // Tags and playlists may have been added or renamed elsewhere since the last opening.
        tagPicker.refresh();
        playlists.reload();
        render();
        enter();
    }

    @Override
    protected boolean handleAction(Shortcuts.Action action) {
        switch (action) {
            case TAG -> tagPicker.focusTree();
            case FAVORITE -> {
                favoriteButton.setSelected(!favoriteButton.isSelected());
                applyFavorite(favoriteButton.isSelected());
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void move(int delta) {
        int next = index + delta;
        if (next < 0 || next >= files.size()) return;
        index = next;
        render();
    }

    /** Matches the showing file's tags to the tree, like ImportView's bulk tag edit does for one file. */
    private void applyTags() {
        ImportCandidate file = files.get(index);
        Set<Integer> picked = tagPicker.getSelectedTagIds();
        file.tagOverrides().removeIf(id -> !picked.contains(id));
        // Appended, so on import a newly picked tag wins over an older sibling under a pick-one parent.
        for (int id : picked) {
            if (!file.tagOverrides().contains(id)) file.tagOverrides().add(id);
        }
        onChanged.run();
    }

    /**
     * After a change from the tag menu: the files' tags live only on the
     * files until import, so a deleted tag is dropped from them here, then
     * the tree (and the playlists, which "Convert to playlist" adds to) is
     * rebuilt for the file showing.
     */
    private void afterTagChange() {
        Set<Integer> existing = new HashSet<>();
        for (Tag tag : library.tags().listAll()) existing.add(tag.id());
        for (ImportCandidate file : files) file.tagOverrides().removeIf(id -> !existing.contains(id));
        tagPicker.refresh();
        playlists.reload();
        render();
        onChanged.run();
    }

    private void applyFavorite(boolean favorite) {
        files.get(index).setFavorite(favorite);
        Sounds.play(favorite ? Sounds.Sound.FAVORITE : Sounds.Sound.UNFAVORITE);
        flashHint(favorite ? "Favorited" : "Removed from favorites");
        onChanged.run();
    }

    private void applyPlaylists() {
        ImportCandidate file = files.get(index);
        file.playlistIds().clear();
        file.playlistIds().addAll(playlists.checked());
    }

    private void render() {
        ImportCandidate file = files.get(index);
        String dimensions = file.dimensionsText();
        nameLabel.setText(dimensions == null || dimensions.isBlank() ? file.fileName()
                : file.fileName() + "  ·  " + dimensions);
        positionLabel.setText((index + 1) + " / " + files.size());
        tagPicker.selectTagIds(new HashSet<>(file.tagOverrides()));
        playlists.setChecked(file.playlistIds());
        favoriteButton.setSelected(file.isFavorite());
        onShown.accept(file);
        // Only reload the picture when moving to another file, so a tag-menu change doesn't flicker it.
        if (file == shownFile) return;
        shownFile = file;
        viewer.setImage(null);
        viewer.fit();
        ImageLoader.load(file.sourcePath(), 0, 0, image -> {
            // Stepping on before a big file finishes decoding mustn't let it land over the next one.
            if (shownFile == file) viewer.setImage(image);
        });
    }
}
