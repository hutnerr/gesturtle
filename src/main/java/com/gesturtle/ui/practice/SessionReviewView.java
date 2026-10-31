package com.gesturtle.ui.practice;

import com.gesturtle.library.LibraryService;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.SessionSpec;
import com.gesturtle.ui.common.AsyncTask;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.ColumnFlow;
import com.gesturtle.ui.components.ImageTile;
import com.gesturtle.ui.components.TileGrid;
import com.gesturtle.ui.components.TileStyle;
import com.gesturtle.ui.components.Page;
import com.gesturtle.ui.components.Toolbar;
import com.gesturtle.ui.gallery.GalleryActions;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Shown after a session ends (finished or exited early): a grid of every
 * image that came up, each with a star to favorite it, and multi-select to
 * tag several at once, the moment you know which references were worth
 * keeping. Replaces the old "Session complete" and "Save as preset?" popups.
 */
public class SessionReviewView extends Page {
    private static final int TILE_SIZE = 180;

    private final LibraryService library;
    private final List<Integer> imageIds;
    private final FlowPane grid = new FlowPane(14, 14);
    /** Masonry cards (see TileStyle) go in columns instead of the FlowPane's rows. */
    private final ColumnFlow columns = new ColumnFlow(TILE_SIZE, 50).gaps(14, 14).fixedWidth();
    private final StackPane gridHolder = new StackPane();
    private final TileStyle style;
    private final Label selectionLabel = new Label();
    private final AppButton tagButton = AppButton.icon("tag", "Tag the selected images", this::tagSelected);
    private final Set<Integer> selection = new LinkedHashSet<>();
    private List<ImageRecord> records = List.of();
    private Integer lastClickedIndex;

    public SessionReviewView(LibraryService library, SessionSpec spec, List<Integer> imageIds,
                             Runnable onDone, Consumer<SessionSpec> onPracticeAgain) {
        super("Session complete");
        this.library = library;
        this.imageIds = imageIds;
        getStyleClass().add("session-review-view");
        setSubtitle(imageIds.size() + (imageIds.size() == 1 ? " image" : " images")
                + " shown. Star or tag the keepers.");

        List<javafx.scene.Node> actions = new ArrayList<>();
        if (spec.presetId() == null) actions.add(AppButton.ghost("Save as preset…", () -> saveAsPreset(spec)).icon("bookmark"));
        actions.add(AppButton.secondary("Practice again", () -> onPracticeAgain.accept(spec)));
        actions.add(AppButton.primary("Done", onDone));
        setActions(actions.toArray(javafx.scene.Node[]::new));

        selectionLabel.getStyleClass().add("muted-label");
        Toolbar bar = new Toolbar().plain()
                .left(selectionLabel)
                .right(AppButton.icon("check", "Select all", this::selectAll),
                        AppButton.icon("close", "Clear the selection", this::clearSelection),
                        Toolbar.divider(), tagButton);
        grid.setAlignment(Pos.TOP_LEFT);
        style = TileStyle.load(library.settings(), TileStyle.REVIEW);
        gridHolder.setAlignment(Pos.TOP_LEFT);
        gridHolder.getChildren().setAll(style.layout() == TileGrid.Layout.MASONRY ? columns : grid);
        setScrollingBody(false, bar, gridHolder);
        updateSelectionLabel();
        reload();
    }

    private void reload() {
        AsyncTask.runDb(
                () -> imageIds.stream().map(id -> library.images().findById(id).orElse(null))
                        .filter(r -> r != null && !r.isDeleted()).toList(),
                list -> {
                    records = list;
                    selection.retainAll(list.stream().map(ImageRecord::id).collect(Collectors.toSet()));
                    render();
                },
                err -> Dialogs.error("Could not load images", err.getMessage()));
    }

    private void render() {
        javafx.scene.layout.Pane target = style.layout() == TileGrid.Layout.MASONRY ? columns : grid;
        target.getChildren().clear();
        for (int i = 0; i < records.size(); i++) {
            target.getChildren().add(buildTile(records.get(i), i));
        }
        updateSelectionLabel();
    }

    private VBox buildTile(ImageRecord record, int index) {
        ImageTile tile = new ImageTile(library.thumbnails().load(library.ref(), record), TILE_SIZE,
                style.heightFor(record, TILE_SIZE), style.fill());
        tile.setSelected(selection.contains(record.id()));

        tile.favoriteToggle(record.favorite(), true, favorite ->
                AsyncTask.runDb(() -> library.images().setFavorite(record.id(), favorite), this::reload,
                        err -> Dialogs.error("Could not update favorite", err.getMessage())));

        // Same selection gestures as the Gallery grid: click, Ctrl-click to toggle, Shift-click for a range.
        tile.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (e.isShiftDown() && lastClickedIndex != null) {
                int from = Math.min(lastClickedIndex, index), to = Math.max(lastClickedIndex, index);
                for (int i = from; i <= to; i++) selection.add(records.get(i).id());
            } else if (e.isControlDown() || e.isMetaDown()) {
                if (!selection.add(record.id())) selection.remove(record.id());
            } else if (selection.size() == 1 && selection.contains(record.id())) {
                selection.clear();
            } else {
                selection.clear();
                selection.add(record.id());
            }
            lastClickedIndex = index;
            render();
        });

        Label tags = new Label(record.tagIds().isEmpty() ? "No tags"
                : record.tagIds().stream().map(id -> library.tags().get(id).map(t -> t.name()).orElse("?"))
                        .collect(Collectors.joining(", ")));
        tags.getStyleClass().add("hint-label");
        tags.setMaxWidth(TILE_SIZE);
        tags.setWrapText(true);

        return new VBox(6, tile, tags);
    }

    private void updateSelectionLabel() {
        selectionLabel.setText(selection.isEmpty()
                ? "Click to select, Ctrl or Shift-click for more"
                : selection.size() + " selected");
        tagButton.setDisable(selection.isEmpty());
    }

    private void selectAll() {
        records.forEach(r -> selection.add(r.id()));
        render();
    }

    private void clearSelection() {
        selection.clear();
        render();
    }

    private void tagSelected() {
        List<ImageRecord> selected = records.stream().filter(r -> selection.contains(r.id())).toList();
        if (GalleryActions.editTags(library, selected)) reload();
    }

    private void saveAsPreset(SessionSpec spec) {
        Dialogs.prompt("Save preset", "Preset name", "My preset").ifPresent(name ->
                AsyncTask.runDb(
                        () -> library.presets().create(name, spec),
                        id -> Dialogs.info("Preset saved", "\"" + name + "\" saved to Home."),
                        err -> Dialogs.error("Could not save preset", err.getMessage())));
    }
}
