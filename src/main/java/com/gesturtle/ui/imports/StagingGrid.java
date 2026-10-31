package com.gesturtle.ui.imports;

import com.gesturtle.model.ImportCandidate;
import com.gesturtle.ui.components.Icon;
import com.gesturtle.ui.components.ImageTile;
import com.gesturtle.ui.components.TileGrid;
import com.gesturtle.ui.components.TileStyle;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The Import page's card view: the same staged files as the StagingTable,
 * as Gallery-style cards. It keeps no state of its own. The files, their
 * thumbnails, and the selection all belong to the table, so the selection
 * bar, the right-click menu, and the keyboard shortcuts work the same in
 * either view, and switching views keeps what's selected.
 *
 * Probing a big folder updates hundreds of files in a burst, so the grid is
 * re-laid out at most once a pulse, and only when files were added or
 * removed, or (in masonry) a file's size became known. Other changes (checks,
 * statuses, favorites) are patched onto the cards on screen.
 */
public class StagingGrid extends StackPane {
    public static final int DEFAULT_TILE_SIZE = 210;
    public static final int MIN_TILE_SIZE = 120;
    public static final int MAX_TILE_SIZE = 320;
    private static final double TILE_GAP = 14;
    private static final String STATE_KEY = "staging.card";

    private final StagingTable table;
    private final TileGrid<ImportCandidate> grid = new TileGrid<>(c -> c, this::buildTile);
    private final Consumer<ImportCandidate> onOpen;
    private final BiConsumer<ImportCandidate, javafx.scene.input.ContextMenuEvent> onMenu;
    /** Each laid-out file's pixel size, packed, so masonry re-lays out only when one changes. */
    private final Map<ImportCandidate, Long> laidOutSizes = new IdentityHashMap<>();
    private TileStyle style;
    private double tileSize = DEFAULT_TILE_SIZE;
    private int anchor = -1;
    private boolean syncQueued;
    private boolean structureChanged;

    /** The overlays on one card, patched in place when the file changes. */
    private record CardState(ImportCandidate item, ImageTile tile, CheckBox check, Label star, Label status) {
    }

    /** onOpen runs on a double-click; onMenu on a right-click, after the clicked card is selected. */
    public StagingGrid(StagingTable table, TileStyle style, Consumer<ImportCandidate> onOpen,
                       BiConsumer<ImportCandidate, javafx.scene.input.ContextMenuEvent> onMenu) {
        this.table = table;
        this.style = style;
        this.onOpen = onOpen;
        this.onMenu = onMenu;
        grid.setGap(TILE_GAP);
        grid.getStyleClass().add("gallery-grid");
        grid.setFocusTraversable(true);
        getChildren().add(grid);
        applyLayout();

        table.candidates().addListener((ListChangeListener<ImportCandidate>) c -> {
            boolean structural = false;
            while (c.next()) {
                if (c.wasAdded() || c.wasRemoved() || c.wasPermutated()) structural = true;
            }
            if (structural) structureChanged = true;
            queueSync();
        });
        selection().getSelectedIndices().addListener((ListChangeListener<Integer>) c -> refreshCards());

        grid.setOnKeyPressed(e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.A) {
                selection().selectAll();
                e.consume();
            } else if (e.getCode() == KeyCode.SPACE && !selection().isEmpty()) {
                table.toggleSelectedIncluded();
                e.consume();
            }
        });
        grid.setItems(table.candidates());
        remember();
    }

    public void setPlaceholder(Node placeholder) {
        grid.setPlaceholder(placeholder);
    }

    /** The node to give keyboard focus, so Space/T/F reach the grid's key handlers. */
    public Node focusTarget() {
        return grid;
    }

    public void setTileSize(double size) {
        if (size == tileSize) return;
        tileSize = size;
        grid.setTileSize(size);
    }

    public double tileSize() {
        return tileSize;
    }

    public void setStyle(TileStyle newStyle) {
        if (newStyle.equals(style)) return;
        style = newStyle;
        applyLayout();
        // Fill vs fit is baked into each card, so they're rebuilt.
        grid.setItems(table.candidates());
    }

    private void applyLayout() {
        grid.setLayout(style.layout(), tileSize, (c, width) -> style.heightFor(c.getWidth(), c.getHeight(), width));
    }

    private MultipleSelectionModel<ImportCandidate> selection() {
        return table.getSelectionModel();
    }

    /** Coalesces a burst of list changes (a folder's probes finishing) into one pass on the next pulse. */
    private void queueSync() {
        if (syncQueued) return;
        syncQueued = true;
        Platform.runLater(() -> {
            syncQueued = false;
            boolean relayout = structureChanged;
            structureChanged = false;
            if (!relayout && style.layout() == TileGrid.Layout.MASONRY) {
                relayout = table.candidates().stream().anyMatch(c -> !Long.valueOf(packedSize(c)).equals(laidOutSizes.get(c)));
            }
            if (relayout) {
                grid.setItems(table.candidates());
                remember();
            } else {
                refreshCards();
            }
        });
    }

    private void remember() {
        laidOutSizes.clear();
        for (ImportCandidate c : table.candidates()) laidOutSizes.put(c, packedSize(c));
    }

    private static long packedSize(ImportCandidate c) {
        return ((long) c.getWidth() << 32) | (c.getHeight() & 0xffffffffL);
    }

    /**
     * For changes the file list can't see: tags, favorites, and a probe
     * filling in a file's size, which fires no list update when its status
     * stays New, so masonry would keep the square card it started with.
     */
    public void refresh() {
        queueSync();
    }

    /** Re-reads every on-screen card's file: selection, checkbox, favorite, status. */
    private void refreshCards() {
        List<ImportCandidate> selected = selection().getSelectedItems();
        java.util.Set<ImportCandidate> selectedSet = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        selectedSet.addAll(selected);
        for (ImportCandidate c : table.candidates()) {
            if (grid.nodeFor(c) instanceof ImageTile tile && tile.getProperties().get(STATE_KEY) instanceof CardState state) {
                patch(state, selectedSet.contains(c));
            }
        }
    }

    private Node buildTile(ImportCandidate item, double width, double height) {
        ImageTile tile = new ImageTile(null, width, height, style.fill());
        int wanted = (int) Math.ceil(Math.max(width, height));
        table.thumbnail(item, wanted, image -> {
            if (image != null) tile.setThumbnail(image);
        });

        CheckBox check = new CheckBox();
        check.selectedProperty().bindBidirectional(item.includedProperty());
        check.getStyleClass().add("staged-card-check");
        check.setFocusTraversable(false);
        // Its own click toggles it; don't let the press also change the selection.
        check.setOnMousePressed(javafx.event.Event::consume);
        tile.overlay(check, Pos.TOP_LEFT);

        Label star = new Label(null, new Icon("favorite-filled", 15));
        star.getStyleClass().addAll("image-tile-badge", "on");
        star.setMouseTransparent(true);
        tile.overlay(star, Pos.TOP_RIGHT);

        Label status = new Label();
        status.getStyleClass().add("staged-card-status");
        status.setMouseTransparent(true);
        tile.overlay(status, Pos.BOTTOM_LEFT);

        CardState state = new CardState(item, tile, check, star, status);
        tile.getProperties().put(STATE_KEY, state);
        patch(state, selection().getSelectedItems().contains(item));

        tile.setOnContextMenuRequested(e -> {
            int index = table.candidates().indexOf(item);
            if (index < 0) return;
            if (!selection().getSelectedItems().contains(item)) {
                selection().clearAndSelect(index);
                anchor = index;
            }
            onMenu.accept(item, e);
            e.consume();
        });
        // PRESSED rather than CLICKED, as in the Gallery: trackpad jitter between press and release
        // can stop a CLICKED from firing at all.
        tile.setOnMousePressed(e -> {
            grid.requestFocus();
            if (e.getButton() != MouseButton.PRIMARY) return;
            int index = table.candidates().indexOf(item);
            if (index < 0) return;
            if (e.getClickCount() == 2) {
                onOpen.accept(item);
                e.consume();
                return;
            }
            if (e.isShiftDown() && anchor >= 0 && anchor < table.candidates().size()) {
                selection().selectRange(Math.min(anchor, index), Math.max(anchor, index) + 1);
            } else if (e.isShortcutDown()) {
                if (selection().isSelected(index)) selection().clearSelection(index);
                else selection().select(index);
                anchor = index;
            } else {
                selection().clearAndSelect(index);
                anchor = index;
            }
            com.gesturtle.ui.common.Sounds.playUnlessSuperseded(com.gesturtle.ui.common.Sounds.Sound.KEY);
            e.consume();
        });
        return tile;
    }

    private static void patch(CardState state, boolean selected) {
        ImportCandidate item = state.item();
        state.tile().setSelected(selected);
        state.check().setDisable(!item.isIncludable());
        state.star().setVisible(item.isFavorite());
        String text = statusText(item);
        state.status().setText(text);
        state.status().setVisible(text != null);
        String reason = item.reasonProperty().get();
        state.status().setTooltip(reason == null || reason.isBlank() ? null : new javafx.scene.control.Tooltip(reason));
        // Files left out of the import fade back, so what will be imported stands out.
        state.tile().setOpacity(item.isIncluded() ? 1 : 0.55);
    }

    /** A short badge for anything but a plain new file; null for those. */
    private static String statusText(ImportCandidate item) {
        String reason = item.reasonProperty().get();
        if (reason != null && reason.startsWith("Import failed")) return "Failed";
        return switch (item.getStatus()) {
            case NEW -> null;
            case ALREADY_IN_LIBRARY -> "In library";
            case DUPLICATE_IN_BATCH -> "Duplicate";
            case UNREADABLE -> "Unreadable";
        };
    }
}
