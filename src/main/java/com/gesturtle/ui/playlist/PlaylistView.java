package com.gesturtle.ui.playlist;

import com.gesturtle.library.LibraryService;
import com.gesturtle.library.UndoCapture;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.Playlist;
import com.gesturtle.ui.common.AsyncTask;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.common.Refreshable;
import com.gesturtle.ui.common.UndoBar;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.Card;
import com.gesturtle.ui.components.EmptyState;
import com.gesturtle.ui.components.Icon;
import com.gesturtle.ui.components.ImageTile;
import com.gesturtle.ui.components.Page;
import com.gesturtle.ui.components.Spacer;
import com.gesturtle.ui.components.TileGrid;
import com.gesturtle.ui.components.SegmentedControl;
import com.gesturtle.ui.components.TileStyle;
import com.gesturtle.ui.gallery.GalleryActions;
import com.gesturtle.ui.gallery.ImageDetailView;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Create/rename/delete playlists and manage the images in each one. Images
 * are added to a playlist from the Gallery's "Add to playlist" action;
 * double-clicking one here opens the same detail view the Gallery uses
 * (favorite, edit tags, delete, browse with arrow keys), and "Start
 * practice" sends you to the Practice page with this playlist already
 * selected as the source, so timing/order/count can still be chosen there.
 * Images select like the Gallery's (click, Ctrl/Shift+click, Ctrl+A), and
 * the bar above them acts on the selection: tag, favorite, copy to another
 * playlist, remove from this one, or delete.
 */
public class PlaylistView extends StackPane implements Refreshable {
    private static final int TILE_SIZE = 200;
    private static final int TILE_GAP = 14;
    private static final int COVER_SIZE = 44;

    private final LibraryService library;
    private final Consumer<Integer> onStartPractice;
    private final ListView<Playlist> playlistList = new ListView<>();
    private final Label itemsHeading = new Label();
    private final Label itemsCount = new Label();
    private final TileGrid<ImageRecord> itemsGrid = new TileGrid<>(ImageRecord::id, this::buildTile);
    private final EmptyState itemsEmpty = new EmptyState("", "");
    private final Label selectionLabel = new Label();
    private final HBox bulkActions = new HBox(4);
    private final Set<Integer> selection = new LinkedHashSet<>();
    private Integer lastClickedIndex;
    private final AppButton startButton = AppButton.primary("Start practice", this::startPractice).trailingIcon("arrow-right");
    private final AppButton renameButton = AppButton.icon("pencil", "Rename playlist", this::renamePlaylist);
    private final AppButton toTagButton = AppButton.icon("tag", "Turn into a tag", this::convertToTag);
    private final AppButton deleteButton = AppButton.icon("trash", "Delete playlist. Its images stay in the library.", this::deletePlaylist);
    private final ImageDetailView detailView;

    /** Card layout and fit, this page's own (see TileStyle). */
    private TileStyle style;
    private final SegmentedControl<TileGrid.Layout> layoutChoice = new SegmentedControl<TileGrid.Layout>()
            .iconOption(TileGrid.Layout.SQUARE, "grid", "Square cards")
            .iconOption(TileGrid.Layout.MASONRY, "masonry", "Cards as tall as their picture (masonry)");
    private final SegmentedControl<Boolean> fillChoice = new SegmentedControl<Boolean>()
            .iconOption(false, "fit", "Show the whole picture")
            .iconOption(true, "fill", "Crop to fill the card");
    private Playlist currentPlaylist;
    private List<ImageRecord> currentRecords = List.of();
    /** Each listed playlist's cover image, looked up with the list. */
    private java.util.Map<Integer, ImageRecord> covers = java.util.Map.of();

    public PlaylistView(LibraryService library, Consumer<Integer> onStartPractice) {
        this.library = library;
        this.onStartPractice = onStartPractice;
        getStyleClass().add("playlist-view");

        Page page = new Page("Playlists");
        page.setSubtitle("Hand-picked image sets. Add images from the Gallery.");
        page.setActions(AppButton.secondary("New playlist…", this::createPlaylist).icon("plus"), startButton);

        playlistList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Playlist item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item.name());
                setGraphic(coverThumb(item));
                setGraphicTextGap(12);
            }
        });
        playlistList.setFixedCellSize(COVER_SIZE + 16);
        playlistList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> loadItems(b));
        playlistList.setPlaceholder(new EmptyState("No playlists", null));
        VBox.setVgrow(playlistList, Priority.ALWAYS);
        Card listCard = new Card().flush().add(playlistList);
        listCard.setPrefWidth(260);
        listCard.setMinWidth(260);

        itemsHeading.getStyleClass().add("card-title");
        itemsHeading.setMinWidth(0);
        itemsCount.getStyleClass().add("card-description");
        startButton.setDisable(true);
        startButton.setTooltip(new javafx.scene.control.Tooltip("Practice from this playlist"));
        deleteButton.getStyleClass().add("btn-ghost-danger");
        HBox itemsTop = new HBox(8, new VBox(2, itemsHeading, itemsCount), new Spacer(), toTagButton, renameButton, deleteButton);
        itemsTop.setAlignment(Pos.CENTER_LEFT);
        itemsTop.setPadding(new Insets(20, 24, 4, 24));

        selectionLabel.getStyleClass().add("muted-label");
        selectionLabel.setWrapText(true);
        AppButton tags = AppButton.icon("tag", "Edit tags of the selected images", this::bulkEditTags);
        AppButton favorite = AppButton.icon("favorite", "Favorite / unfavorite the selected images", this::bulkFavorite);
        AppButton copy = AppButton.icon("queue", "Add the selected images to another playlist", this::bulkCopy);
        AppButton remove = AppButton.icon("close", "Remove the selected images from this playlist", this::bulkRemove);
        AppButton delete = AppButton.icon("trash", "Move the selected images to the " + com.gesturtle.library.SystemTrash.NAME, this::bulkDelete);
        delete.getStyleClass().add("btn-ghost-danger");
        bulkActions.getChildren().setAll(tags, favorite, copy, remove, com.gesturtle.ui.components.Toolbar.divider(), delete);
        bulkActions.setAlignment(Pos.CENTER_RIGHT);
        style = TileStyle.load(library.settings(), TileStyle.PLAYLISTS);
        layoutChoice.setValue(style.layout());
        layoutChoice.onChange(v -> setStyle(style.withLayout(v)));
        fillChoice.setValue(style.fill());
        fillChoice.onChange(v -> setStyle(style.withFill(v)));
        // Display controls first, as in the Gallery's bar; actions on the selection on the right.
        HBox bulkBar = new HBox(12, layoutChoice, fillChoice, selectionLabel, new Spacer(), bulkActions);
        bulkBar.setAlignment(Pos.CENTER_LEFT);
        bulkBar.setPadding(new Insets(4, 24, 0, 24));

        itemsGrid.setGap(TILE_GAP);
        itemsGrid.setLayout(style.layout(), TILE_SIZE, style::heightFor);
        itemsGrid.setPadding(new Insets(8, 18, 18, 18));
        itemsGrid.setPlaceholder(itemsEmpty);
        itemsGrid.setFocusTraversable(true);
        itemsGrid.setOnKeyPressed(e -> {
            if (e.isShortcutDown() && e.getCode() == javafx.scene.input.KeyCode.A) {
                Set<Integer> before = new LinkedHashSet<>(selection);
                currentRecords.forEach(r -> selection.add(r.id()));
                syncSelection(before);
                e.consume();
            }
        });
        // Esc clears the picked images from anywhere on the page, except while typing. A filter, so the
        // playlist list on the left can't swallow it first.
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != javafx.scene.input.KeyCode.ESCAPE || selection.isEmpty()
                    || e.getTarget() instanceof javafx.scene.control.TextInputControl) return;
            Set<Integer> before = new LinkedHashSet<>(selection);
            selection.clear();
            syncSelection(before);
            e.consume();
        });
        VBox.setVgrow(itemsGrid, Priority.ALWAYS);
        Card itemsCard = new Card().flush().add(itemsTop, bulkBar, itemsGrid);
        itemsCard.setSpacing(0);
        HBox.setHgrow(itemsCard, Priority.ALWAYS);
        updateSelectionLabel();

        HBox body = new HBox(20, listCard, itemsCard);
        page.setBody(body);

        detailView = new ImageDetailView(library, this::closeDetail, () -> loadItems(currentPlaylist));

        getChildren().addAll(page, detailView);
        detailView.setVisible(false);
        detailView.setManaged(false);

        refresh();
    }

    @Override
    public void onShown() {
        refresh();
    }

    private void setStyle(TileStyle newStyle) {
        if (newStyle.equals(style)) return;
        style = newStyle;
        style.save(library.settings(), TileStyle.PLAYLISTS);
        itemsGrid.setLayout(style.layout(), TILE_SIZE, style::heightFor);
        // Fill vs fit is baked into each tile, so they're rebuilt.
        itemsGrid.setItems(currentRecords);
    }

    private void refresh() {
        Playlist previouslySelected = playlistList.getSelectionModel().getSelectedItem();
        record Listing(List<Playlist> playlists, java.util.Map<Integer, ImageRecord> covers) {
        }
        AsyncTask.runDb(
                () -> {
                    List<Playlist> playlists = library.playlists().listAll();
                    java.util.Map<Integer, ImageRecord> found = new java.util.HashMap<>();
                    for (Playlist p : playlists) {
                        if (p.coverImageId() != null) library.images().findById(p.coverImageId()).ifPresent(r -> found.put(r.id(), r));
                    }
                    return new Listing(playlists, found);
                },
                listing -> {
                    List<Playlist> list = listing.playlists();
                    covers = listing.covers();
                    playlistList.getItems().setAll(list);
                    Playlist toSelect = previouslySelected == null ? null : list.stream()
                            .filter(p -> p.id() == previouslySelected.id()).findFirst().orElse(null);
                    if (toSelect != null) {
                        playlistList.getSelectionModel().select(toSelect);
                    } else if (!list.isEmpty()) {
                        playlistList.getSelectionModel().select(0);
                    } else {
                        loadItems(null);
                    }
                },
                err -> Dialogs.error("Could not load playlists", err.getMessage()));
    }

    private void loadItems(Playlist playlist) {
        currentPlaylist = playlist;
        closeDetail();
        boolean hasPlaylist = playlist != null;
        renameButton.setDisable(!hasPlaylist);
        deleteButton.setDisable(!hasPlaylist);
        toTagButton.setDisable(!hasPlaylist);
        if (!hasPlaylist) {
            itemsHeading.setText("No playlist selected");
            itemsCount.setText("");
            startButton.setDisable(true);
            showItems(List.of(), "No playlists yet", "Create one to collect images for focused practice.");
            return;
        }
        itemsHeading.setText(playlist.name());
        itemsCount.setText("");
        AsyncTask.runDb(
                () -> {
                    List<Integer> ids = library.playlists().listImageIds(playlist.id(), false);
                    return ids.stream().map(id -> library.images().findById(id).orElse(null))
                            .filter(r -> r != null && !r.isDeleted()).toList();
                },
                records -> {
                    itemsCount.setText(records.size() + (records.size() == 1 ? " image" : " images"));
                    startButton.setDisable(records.isEmpty());
                    showItems(records, "This playlist is empty", "Add images from the Gallery.");
                },
                err -> Dialogs.error("Could not load playlist images", err.getMessage()));
    }

    private void showItems(List<ImageRecord> records, String emptyTitle, String emptyMessage) {
        currentRecords = records;
        selection.retainAll(records.stream().map(ImageRecord::id).toList());
        itemsEmpty.setText(emptyTitle, emptyMessage);
        itemsGrid.setItems(records);
        updateSelectionLabel();
    }

    private void updateSelectionLabel() {
        int n = selectedRecords().size();
        selectionLabel.setText(n == 0 ? "Double-click to open" : n + " selected");
        bulkActions.setDisable(n == 0);
    }

    private List<ImageRecord> selectedRecords() {
        return currentRecords.stream().filter(r -> selection.contains(r.id())).toList();
    }

    /** Restyles the tiles whose selection changed, without rebuilding them (see GalleryView.buildTile). */
    private void syncSelection(Set<Integer> before) {
        Set<Integer> changed = new LinkedHashSet<>(before);
        changed.addAll(selection);
        for (int id : changed) {
            if (itemsGrid.nodeFor(id) instanceof ImageTile tile) tile.setSelected(selection.contains(id));
        }
        updateSelectionLabel();
    }

    private void bulkEditTags() {
        if (GalleryActions.editTags(library, selectedRecords())) loadItems(currentPlaylist);
    }

    private void bulkFavorite() {
        List<ImageRecord> selected = selectedRecords();
        if (selected.isEmpty()) return;
        GalleryActions.toggleFavorite(library, selected);
        loadItems(currentPlaylist);
    }

    private void bulkCopy() {
        if (currentPlaylist != null) GalleryActions.addToPlaylist(library, selectedRecords(), currentPlaylist.id());
    }

    private void bulkRemove() {
        List<ImageRecord> selected = selectedRecords();
        if (selected.isEmpty() || currentPlaylist == null) return;
        removeFromPlaylist(currentPlaylist, selected);
        selection.clear();
        refresh();
    }

    /** Takes the images out of the playlist, with an undo (their places in the order come back too). */
    private void removeFromPlaylist(Playlist playlist, List<ImageRecord> records) {
        UndoCapture undo = library.captureForUndo(
                UndoCapture.Table.where("playlist", List.of("id"), "id = ?", playlist.id()),
                UndoCapture.Table.where("playlist_item", List.of(), "playlist_id = ?", playlist.id()));
        for (ImageRecord r : records) library.playlists().removeItem(playlist.id(), r.id());
        UndoBar.offer("Removed " + (records.size() == 1 ? "an image" : records.size() + " images")
                + " from “" + playlist.name() + "”", undo.after());
    }

    private void bulkDelete() {
        if (GalleryActions.deleteToTrash(library, selectedRecords())) {
            selection.clear();
            refresh();
        }
    }

    private Node buildTile(ImageRecord record, double width, double height) {
        Playlist playlist = currentPlaylist;
        Button removeButton = new Button(null, new Icon("close", 12));
        removeButton.getStyleClass().add("tile-remove-button");
        removeButton.setOnAction(e -> {
            removeFromPlaylist(playlist, List.of(record));
            loadItems(playlist);
        });

        removeButton.setTooltip(new javafx.scene.control.Tooltip("Remove from playlist"));

        ImageTile tile = new ImageTile(library.thumbnails().load(library.ref(), record), width, height, style.fill())
                .favorite(record.favorite())
                .overlay(removeButton, Pos.TOP_LEFT);
        tile.setSelected(selection.contains(record.id()));
        boolean isCover = playlist.coverImageId() != null && playlist.coverImageId() == record.id();
        if (isCover) {
            Label badge = new Label("Cover");
            badge.getStyleClass().addAll("image-tile-badge", "tile-cover-badge");
            badge.setMouseTransparent(true);
            tile.overlay(badge, Pos.BOTTOM_LEFT);
        }
        // PRESSED, not CLICKED, for the same reasons as the Gallery's tiles.
        tile.setOnMousePressed(e -> {
            itemsGrid.requestFocus();
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            int index = currentRecords.indexOf(record);
            if (e.getClickCount() == 2) {
                openDetail(index);
                e.consume();
                return;
            }
            Set<Integer> before = new LinkedHashSet<>(selection);
            if (e.isShiftDown() && lastClickedIndex != null) {
                int from = Math.min(lastClickedIndex, index), to = Math.max(lastClickedIndex, index);
                for (int i = from; i <= to; i++) selection.add(currentRecords.get(i).id());
            } else if (e.isShortcutDown()) {
                if (!selection.add(record.id())) selection.remove(record.id());
            } else {
                selection.clear();
                selection.add(record.id());
            }
            lastClickedIndex = index;
            syncSelection(before);
            e.consume();
        });
        javafx.scene.control.MenuItem setCover = new javafx.scene.control.MenuItem(
                isCover && playlist.explicitCover() ? "Use the first image as cover" : "Set as cover");
        setCover.setOnAction(e -> {
            library.playlists().setCover(playlist.id(), isCover && playlist.explicitCover() ? null : record.id());
            refresh();
        });
        javafx.scene.control.MenuItem open = new javafx.scene.control.MenuItem("Open");
        open.setOnAction(e -> openDetail(currentRecords.indexOf(record)));
        javafx.scene.control.MenuItem remove = new javafx.scene.control.MenuItem("Remove from playlist");
        remove.setOnAction(e -> {
            if (selection.contains(record.id())) {
                bulkRemove();
            } else {
                removeFromPlaylist(playlist, List.of(record));
                refresh();
            }
        });
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(open, setCover, remove);
        tile.setOnContextMenuRequested(e -> {
            menu.show(tile, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return tile;
    }

    /** The cover's thumbnail, or a placeholder square for an empty playlist. */
    private Node coverThumb(Playlist playlist) {
        ImageRecord cover = playlist.coverImageId() == null ? null : covers.get(playlist.coverImageId());
        StackPane box = new StackPane();
        box.getStyleClass().add("playlist-cover");
        box.setPrefSize(COVER_SIZE, COVER_SIZE);
        box.setMinSize(COVER_SIZE, COVER_SIZE);
        box.setMaxSize(COVER_SIZE, COVER_SIZE);
        javafx.scene.image.Image thumb = cover == null ? null : library.thumbnails().load(library.ref(), cover);
        if (thumb == null) {
            box.getChildren().add(new Icon("queue", 18));
            return box;
        }
        javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(thumb);
        view.setFitWidth(COVER_SIZE);
        view.setFitHeight(COVER_SIZE);
        view.setPreserveRatio(false);
        // Center-cropped square: a cover is a swatch, not a reference to study. The thumbnail
        // decodes in the background, so crop again once its size is known.
        thumb.widthProperty().addListener((o, a, w) -> cropSquare(view));
        cropSquare(view);
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(COVER_SIZE, COVER_SIZE);
        clip.setArcWidth(12);
        clip.setArcHeight(12);
        view.setClip(clip);
        box.getChildren().add(view);
        return box;
    }

    private static void cropSquare(javafx.scene.image.ImageView view) {
        javafx.scene.image.Image img = view.getImage();
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return;
        double side = Math.min(img.getWidth(), img.getHeight());
        view.setViewport(new javafx.geometry.Rectangle2D((img.getWidth() - side) / 2, (img.getHeight() - side) / 2, side, side));
    }

    private void openDetail(int index) {
        // Visible/managed first; see GalleryView.openDetail() for why order matters here.
        detailView.setVisible(true);
        detailView.setManaged(true);
        detailView.open(List.copyOf(currentRecords), index);
    }

    private void closeDetail() {
        detailView.setVisible(false);
        detailView.setManaged(false);
    }

    private void startPractice() {
        if (currentPlaylist == null || currentRecords.isEmpty()) return;
        onStartPractice.accept(currentPlaylist.id());
    }

    private void createPlaylist() {
        Dialogs.prompt("New playlist", "Name", "").ifPresent(name -> {
            library.playlists().create(name);
            refresh();
        });
    }

    private void renamePlaylist() {
        Playlist selected = playlistList.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Dialogs.prompt("Rename playlist", "New name", selected.name()).ifPresent(name -> {
            library.playlists().rename(selected.id(), name);
            refresh();
        });
    }

    /** Tags the playlist's images with a new top-level tag; keeping the playlist makes it a copy. */
    private void convertToTag() {
        Playlist selected = playlistList.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        int count = currentRecords.size();
        String message = "Every image in this playlist (" + count + ") gets a new top-level tag.";
        Dialogs.promptWithOption("Convert to tag", message, "Tag name", selected.name(),
                "Keep the playlist (copy instead of convert)", true, "Convert").ifPresent(choice -> {
            try {
                UndoCapture undo = library.captureForUndo(
                        UndoCapture.Table.all("tag", "id"),
                        UndoCapture.Table.all("image_tag"),
                        UndoCapture.Table.all("playlist", "id"),
                        UndoCapture.Table.all("playlist_item"));
                com.gesturtle.library.PlaylistTagConversion.playlistToTag(library, selected.id(), choice.name(), choice.option());
                com.gesturtle.ui.common.UndoBar.offer((choice.option() ? "Copied" : "Converted") + " “" + selected.name()
                        + "” to tag “" + choice.name() + "”", undo.after());
            } catch (RuntimeException ex) {
                Dialogs.error("Could not convert playlist", ex.getMessage());
            }
            refresh();
        });
    }

    private void deletePlaylist() {
        Playlist selected = playlistList.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        if (!Dialogs.confirmDanger("Delete playlist", "Delete \"" + selected.name() + "\"? Its images are kept.", "Delete")) return;
        library.playlists().delete(selected.id());
        refresh();
    }
}
