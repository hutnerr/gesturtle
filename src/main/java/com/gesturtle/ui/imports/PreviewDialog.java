package com.gesturtle.ui.imports;

import com.gesturtle.library.LibraryService;
import com.gesturtle.model.ImportCandidate;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.common.ImageLoader;
import com.gesturtle.ui.common.Shortcuts;
import com.gesturtle.ui.common.Sounds;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.ImageViewer;
import com.gesturtle.ui.components.Toolbar;
import com.gesturtle.ui.tags.TagPicker;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.RowConstraints;
import javafx.stage.Window;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A staged file at full size, double-clicked from the import table, to look
 * it over and tag it before importing: the same viewer (zoom, pan, flip,
 * grid, adjustments) as the Gallery's detail view, with the tag tree and,
 * under it, the playlists docked on the left. Ticking a tag or playlist, or
 * the favorite star (F), applies it to the file showing straight away (tags
 * on top of the batch tags), so you can tag, press →, and tag the next. T
 * jumps into the tag tree.
 */
final class PreviewDialog {
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final ImageViewer viewer = new ImageViewer();
    private final Label positionLabel = new Label();
    private final ToggleButton favoriteButton = AppButton.small(AppButton.favoriteToggle(AppButton.Variant.GHOST));
    private final TagPicker tagPicker;
    private final PlaylistChecklist playlists;
    private final List<ImportCandidate> files;
    private final Consumer<ImportCandidate> onShown;
    private final Runnable onChanged;
    private int index;

    private PreviewDialog(LibraryService library, List<ImportCandidate> files, int startIndex,
                          Consumer<ImportCandidate> onShown, Runnable onChanged) {
        this.files = files;
        this.index = startIndex;
        this.onShown = onShown;
        this.onChanged = onChanged;

        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().getStyleClass().add("image-preview-dialog");

        viewer.setInset(16);
        positionLabel.getStyleClass().add("muted-label");
        // The click has already flipped the button; this records it and makes the sound (the button is quiet).
        favoriteButton.setOnAction(e -> applyFavorite(favoriteButton.isSelected()));
        List<Node> viewerTools = viewer.toolButtons();
        Toolbar toolbar = new Toolbar().plain()
                .left(AppButton.icon("chevron-left", Shortcuts.hint(Shortcuts.Action.PREVIOUS), () -> move(-1)), positionLabel,
                        AppButton.icon("chevron-right", Shortcuts.hint(Shortcuts.Action.NEXT), () -> move(1)),
                        Toolbar.divider(), favoriteButton)
                .right(viewerTools.toArray(Node[]::new))
                .overflowable(viewerTools);
        BorderPane viewerPane = new BorderPane(viewer);
        viewerPane.setTop(toolbar);
        BorderPane.setMargin(toolbar, new Insets(0, 0, 12, 0));

        tagPicker = new TagPicker(library, true);
        tagPicker.setPrefWidth(300);
        tagPicker.setOnChange(this::applyTags);
        playlists = new PlaylistChecklist(library);
        playlists.setOnChange(this::applyPlaylists);

        // Tags over playlists, 2:1: tagging is most of the work, playlists the odd extra.
        GridPane sidePanel = new GridPane();
        sidePanel.setVgap(16);
        sidePanel.setPrefWidth(300);
        sidePanel.setMinWidth(240);
        sidePanel.addColumn(0, tagPicker, playlists);
        RowConstraints tagsRow = new RowConstraints();
        tagsRow.setPercentHeight(200.0 / 3);
        RowConstraints playlistsRow = new RowConstraints();
        playlistsRow.setPercentHeight(100.0 / 3);
        sidePanel.getRowConstraints().addAll(tagsRow, playlistsRow);
        ColumnConstraints column = new ColumnConstraints();
        column.setPercentWidth(100);
        sidePanel.getColumnConstraints().add(column);

        BorderPane content = new BorderPane(viewerPane);
        content.setLeft(sidePanel);
        BorderPane.setMargin(sidePanel, new Insets(0, 16, 0, 0));
        dialog.getDialogPane().setContent(content);

        // A filter, so the shortcuts beat a focused toolbar button (which would take arrows for focus traversal).
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
    }

    /**
     * Opens on files[startIndex]; onShown hears each file as it comes up, so
     * the table can follow along, and onChanged hears each tag ticked or
     * unticked and each favorite change, so the table can show it. Playlists
     * ticked are only kept on the file, for the import to add it to.
     */
    static void show(LibraryService library, List<ImportCandidate> files, int startIndex,
                     Consumer<ImportCandidate> onShown, Runnable onChanged) {
        if (files.isEmpty()) return;
        PreviewDialog preview = new PreviewDialog(library, List.copyOf(files), startIndex, onShown, onChanged);
        Dialogs.prepare(preview.dialog);
        // Most of the app window, so the picture is worth opening.
        Window owner = preview.dialog.getOwner();
        double width = owner == null ? 1100 : owner.getWidth() * 0.85;
        double height = owner == null ? 800 : owner.getHeight() * 0.85;
        preview.dialog.getDialogPane().setPrefSize(width, height);
        preview.render();
        preview.dialog.showAndWait();
    }

    private void handleKey(KeyEvent e) {
        // Typing a new tag's name: letters and arrows are the text field's.
        if (dialog.getDialogPane().getScene().getFocusOwner() instanceof TextInputControl) return;
        if (e.getCode() == KeyCode.ESCAPE && viewer.closePanel()) {
            e.consume();
            return;
        }
        Shortcuts.Action action = Shortcuts.match(e, Shortcuts.Scope.VIEWER);
        if (action == null) return;
        switch (action) {
            case PREVIOUS -> move(-1);
            case NEXT -> move(1);
            case TAG -> tagPicker.focusTree();
            case FAVORITE -> {
                favoriteButton.setSelected(!favoriteButton.isSelected());
                applyFavorite(favoriteButton.isSelected());
            }
            default -> {
                if (!viewer.handle(action)) return;
            }
        }
        e.consume();
    }

    private void move(int delta) {
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

    private void applyFavorite(boolean favorite) {
        files.get(index).setFavorite(favorite);
        Sounds.play(favorite ? Sounds.Sound.FAVORITE : Sounds.Sound.UNFAVORITE);
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
        dialog.setTitle(dimensions == null || dimensions.isBlank() ? file.fileName()
                : file.fileName() + "  ·  " + dimensions);
        positionLabel.setText((index + 1) + " of " + files.size());
        tagPicker.selectTagIds(new HashSet<>(file.tagOverrides()));
        playlists.setChecked(file.playlistIds());
        favoriteButton.setSelected(file.isFavorite());
        onShown.accept(file);
        viewer.setImage(null);
        viewer.fit();
        ImageLoader.load(file.sourcePath(), 0, 0, image -> {
            // Stepping on before a big file finishes decoding mustn't let it land over the next one.
            if (files.get(index) == file) viewer.setImage(image);
        });
    }
}
