package com.gesturtle.ui.gallery;

import com.gesturtle.filter.Filter;
import com.gesturtle.library.LibraryService;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.SortOrder;
import com.gesturtle.ui.common.AsyncTask;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.common.Refreshable;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.Card;
import com.gesturtle.ui.components.EmptyState;
import com.gesturtle.ui.components.ImageTile;
import com.gesturtle.ui.components.Page;
import com.gesturtle.ui.components.SegmentedControl;
import com.gesturtle.ui.components.TileGrid;
import com.gesturtle.ui.components.TileStyle;
import com.gesturtle.ui.components.Toolbar;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The primary library-management surface: grid/list views over
 * a live filter, favoriting, multi-select bulk actions, and a detail
 * overlay. The grid is a {@link TileGrid}: square tiles or a masonry of
 * picture-height tiles, either fitted (padded) or filled (cropped), and
 * virtualized so only tiles near the viewport are ever built.
 */
public class GalleryView extends StackPane implements Refreshable {
    private static final int DEFAULT_TILE_SIZE = 210;
    private static final int MIN_TILE_SIZE = 120;
    /** Thumbnails are generated at 320px, so tiles larger than that would only blur. */
    private static final int MAX_TILE_SIZE = 320;
    private static final int DEFAULT_LIST_THUMB = 48;
    private static final int MIN_LIST_THUMB = 32;
    private static final int MAX_LIST_THUMB = 160;
    private static final int TILE_GAP = 14;
    /** Library settings, so the Gallery reopens the way it was left. */
    private static final String SORT_KEY = "gallery_sort";
    private static final String TILE_SIZE_KEY = "gallery_tile_size";
    private static final String LIST_THUMB_KEY = "gallery_list_thumb_size";

    private final LibraryService library;
    private final FilterBar filterBar;
    private final SavedViewBar savedViewBar;
    private final TileGrid<ImageRecord> grid;
    private final TableView<ImageRecord> table = new TableView<>();
    private final StackPane contentStack = new StackPane();
    private final Label selectionLabel = new Label();
    private final Page page = new Page("Gallery");
    private final ImageDetailView detailView;

    private final ObservableList<ImageRecord> results = FXCollections.observableArrayList();
    private final Set<Integer> gridSelection = new LinkedHashSet<>();
    private Integer lastClickedIndex;
    private boolean gridMode = true;
    private Filter currentFilter = new Filter.All();
    private SortOrder sortOrder = SortOrder.NEWEST;
    private final Slider sizeSlider = new Slider();
    /** The grid-only display toggles (layout, fit), hidden in list mode. */
    private final HBox gridOptions = new HBox(8);
    private int tileSize = DEFAULT_TILE_SIZE;
    private int listThumbSize = DEFAULT_LIST_THUMB;
    /** Card layout and fit, the Gallery's own (see TileStyle). */
    private TileStyle style;
    private final SegmentedControl<TileGrid.Layout> layoutChoice = new SegmentedControl<TileGrid.Layout>()
            .iconOption(TileGrid.Layout.SQUARE, "grid", "Square cards")
            .iconOption(TileGrid.Layout.MASONRY, "masonry", "Cards as tall as their picture (masonry)");
    private final SegmentedControl<Boolean> fillChoice = new SegmentedControl<Boolean>()
            .iconOption(false, "fit", "Show the whole picture")
            .iconOption(true, "fill", "Crop to fill the card");
    /** True while setMode swaps the slider's range, so the clamping it does along the way isn't taken as a resize. */
    private boolean syncingSlider;
    private TableColumn<ImageRecord, ImageRecord> thumbCol;

    public GalleryView(LibraryService library) {
        this.library = library;
        getStyleClass().add("gallery-view");

        filterBar = new FilterBar(library);
        savedViewBar = new SavedViewBar(library, filterBar);
        filterBar.setOnChange(f -> {
            currentFilter = f;
            savedViewBar.filterChanged(f);
            runQuery();
        });

        SegmentedControl<Boolean> modeChoice = new SegmentedControl<Boolean>()
                .iconOption(true, "grid", "Grid")
                .iconOption(false, "list", "List")
                .bare()
                .onChange(this::setMode);
        sortOrder = SortOrder.parse(library.settings().get(SORT_KEY).orElse(null));
        tileSize = readSize(TILE_SIZE_KEY, MIN_TILE_SIZE, MAX_TILE_SIZE, DEFAULT_TILE_SIZE);
        listThumbSize = readSize(LIST_THUMB_KEY, MIN_LIST_THUMB, MAX_LIST_THUMB, DEFAULT_LIST_THUMB);
        style = TileStyle.load(library.settings(), TileStyle.GALLERY);
        ChoiceBox<SortOrder> sortChoice = new ChoiceBox<>();
        sortChoice.getItems().setAll(SortOrder.values());
        sortChoice.setValue(sortOrder);
        sortChoice.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(SortOrder order) {
                return order == null ? "" : order.label;
            }

            @Override
            public SortOrder fromString(String s) {
                return null;
            }
        });
        sortChoice.setTooltip(new javafx.scene.control.Tooltip("Sort order"));
        sortChoice.valueProperty().addListener((o, was, order) -> {
            if (order == null) return;
            sortOrder = order;
            library.settings().set(SORT_KEY, order.name());
            runQuery();
        });

        layoutChoice.setValue(style.layout());
        layoutChoice.onChange(v -> setStyle(style.withLayout(v)));
        fillChoice.setValue(style.fill());
        fillChoice.onChange(v -> setStyle(style.withFill(v)));
        gridOptions.getChildren().setAll(layoutChoice, fillChoice);
        gridOptions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        sizeSlider.setMin(MIN_TILE_SIZE);
        sizeSlider.setMax(MAX_TILE_SIZE);
        sizeSlider.setValue(tileSize);
        sizeSlider.setPrefWidth(120);
        sizeSlider.setTooltip(new javafx.scene.control.Tooltip("Thumbnail size"));
        sizeSlider.valueProperty().addListener((o, a, v) -> setThumbSize(v.intValue()));
        sizeSlider.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing) saveThumbSize();
        });
        sizeSlider.setOnMouseReleased(e -> saveThumbSize());
        javafx.scene.Node smallIcon = new com.gesturtle.ui.components.Icon("gallery", 12);
        javafx.scene.Node bigIcon = new com.gesturtle.ui.components.Icon("gallery", 18);
        HBox sizeBox = new HBox(8, smallIcon, sizeSlider, bigIcon);
        sizeBox.setAlignment(javafx.geometry.Pos.CENTER);
        sizeBox.getStyleClass().add("tile-size-control");
        page.setActions(AppButton.icon("refresh", "Refresh (F5)", this::runQuery), modeChoice);

        selectionLabel.getStyleClass().add("muted-label");
        selectionLabel.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        AppButton editTags = AppButton.icon("tag", "Edit tags of the selected images", this::bulkEditTags);
        AppButton favorite = AppButton.icon("favorite", "Favorite / unfavorite the selected images", this::bulkToggleFavorite);
        AppButton addToPlaylist = AppButton.icon("queue", "Add the selected images to a playlist", this::bulkAddToPlaylist);
        AppButton delete = AppButton.icon("trash", "Move the selected images to the " + com.gesturtle.library.SystemTrash.NAME, this::bulkDelete);
        delete.getStyleClass().add("btn-ghost-danger");
        // Display controls on the left (they shape the grid below, and wrap in a narrow window), actions on
        // what's selected on the right.
        javafx.scene.layout.FlowPane displayControls = new javafx.scene.layout.FlowPane(10, 8,
                sortChoice, gridOptions, sizeBox, selectionLabel);
        displayControls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        displayControls.setPrefWrapLength(2000);
        HBox actions = new HBox(4, editTags, favorite, addToPlaylist, Toolbar.divider(), delete);
        actions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        actions.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox bulkBar = new HBox(12, displayControls, actions);
        bulkBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(displayControls, Priority.ALWAYS);
        updateSelectionLabel();

        grid = new TileGrid<>(ImageRecord::id, this::buildTile);
        grid.setGap(TILE_GAP);
        grid.getStyleClass().add("gallery-grid");
        grid.setFocusTraversable(true);
        grid.setOnKeyPressed(e -> {
            if ((e.isControlDown() || e.isMetaDown()) && e.getCode() == javafx.scene.input.KeyCode.A) {
                Set<Integer> previouslySelected = new LinkedHashSet<>(gridSelection);
                gridSelection.clear();
                for (ImageRecord r : results) gridSelection.add(r.id());
                updateSelectionStyles(previouslySelected);
                updateSelectionLabel();
                e.consume();
            }
        });
        applyGridLayout();

        buildTableColumns();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setFixedCellSize(listThumbSize + 12);
        table.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<ImageRecord> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    openDetail(results.indexOf(row.getItem()));
                }
            });
            row.setOnContextMenuRequested(e -> {
                if (row.isEmpty()) return;
                if (!table.getSelectionModel().getSelectedItems().contains(row.getItem())) {
                    table.getSelectionModel().clearAndSelect(row.getIndex());
                }
                buildContextMenu(results.indexOf(row.getItem())).show(row, e.getScreenX(), e.getScreenY());
                e.consume();
            });
            return row;
        });

        grid.setPlaceholder(new EmptyState("No images match",
                "Loosen the filter or import images."));
        table.setPlaceholder(new EmptyState("No images match", null));
        table.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<ImageRecord>) c -> updateSelectionLabel());
        contentStack.getChildren().add(grid);

        filterBar.addToTopRowStart(savedViewBar);
        VBox center = new VBox(16, new Card().add(filterBar), bulkBar, contentStack);
        VBox.setVgrow(contentStack, Priority.ALWAYS);
        page.setBody(center);

        detailView = new ImageDetailView(library, this::closeDetail, this::runQuery);

        getChildren().addAll(page, detailView);
        detailView.setVisible(false);
        detailView.setManaged(false);
        addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.F5 && !detailView.isVisible()) {
                runQuery();
                e.consume();
            }
        });
        // A filter, not a handler: the list's TableView swallows Esc itself. Left alone while the detail
        // view is open (Esc closes it) or a text field has focus.
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != javafx.scene.input.KeyCode.ESCAPE || detailView.isVisible()
                    || e.getTarget() instanceof javafx.scene.control.TextInputControl
                    || selectedRecords().isEmpty()) return;
            Set<Integer> previouslySelected = new LinkedHashSet<>(gridSelection);
            gridSelection.clear();
            table.getSelectionModel().clearSelection();
            updateSelectionStyles(previouslySelected);
            updateSelectionLabel();
            e.consume();
        });

        runQuery();
    }

    @Override
    public void onShown() {
        savedViewBar.refresh();
        runQuery();
    }

    private void setMode(boolean grid) {
        this.gridMode = grid;
        contentStack.getChildren().setAll(grid ? this.grid : table);
        com.gesturtle.ui.common.Motion.setShown(gridOptions, grid);
        // One slider, two sizes: tiles in the grid, thumbnails in the list.
        syncingSlider = true;
        sizeSlider.setMin(Math.min(MIN_TILE_SIZE, MIN_LIST_THUMB));
        sizeSlider.setMax(Math.max(MAX_TILE_SIZE, MAX_LIST_THUMB));
        sizeSlider.setValue(grid ? tileSize : listThumbSize);
        sizeSlider.setMin(grid ? MIN_TILE_SIZE : MIN_LIST_THUMB);
        sizeSlider.setMax(grid ? MAX_TILE_SIZE : MAX_LIST_THUMB);
        syncingSlider = false;
        updateSelectionLabel();
    }

    private int readSize(String key, int min, int max, int fallback) {
        return library.settings().get(key).map(Integer::parseInt)
                .map(v -> Math.max(min, Math.min(max, v))).orElse(fallback);
    }

    private void updateSelectionLabel() {
        int n = selectedRecords().size();
        selectionLabel.setText(n == 0 ? "Double-click to open"
                : n + " selected");
    }

    private void setThumbSize(int size) {
        if (syncingSlider) return;
        if (gridMode) {
            if (size == tileSize) return;
            tileSize = size;
            grid.setTileSize(tileSize);
        } else {
            if (size == listThumbSize) return;
            listThumbSize = size;
            table.setFixedCellSize(listThumbSize + 12);
            thumbCol.setPrefWidth(listThumbSize + 24);
            table.refresh();
        }
    }

    private void saveThumbSize() {
        if (gridMode) library.settings().set(TILE_SIZE_KEY, String.valueOf(tileSize));
        else library.settings().set(LIST_THUMB_KEY, String.valueOf(listThumbSize));
    }

    private void applyGridLayout() {
        grid.setLayout(style.layout(), tileSize, style::heightFor);
    }

    private void setStyle(TileStyle newStyle) {
        if (newStyle.equals(style)) return;
        style = newStyle;
        style.save(library.settings(), TileStyle.GALLERY);
        applyGridLayout();
    }

    private void runQuery() {
        AsyncTask.runDb(
                () -> library.images().query(currentFilter, sortOrder, null),
                list -> {
                    // Coming back to the page re-queries; when nothing changed, keep the built tiles (and the
                    // selection) instead of rebuilding them all under the page's entrance.
                    page.setSubtitle(list.size() + (list.size() == 1 ? " image" : " images"));
                    if (list.equals(results)) return;
                    results.setAll(list);
                    gridSelection.clear();
                    table.setItems(results);
                    grid.setItems(results);
                    updateSelectionLabel();
                },
                err -> Dialogs.error("Could not load images", err.getMessage()));
    }

    /**
     * Builds one tile. Selection changes update style classes on the already-
     * rendered tiles directly (see updateSelectionStyles) rather than
     * rebuilding the grid: replacing the clicked tile's
     * Node mid-gesture made JavaFX lose track of double-clicks, since its
     * click-count tracking keys off the Node a click lands on, not just
     * screen position.
     */
    private javafx.scene.Node buildTile(ImageRecord record, double width, double height) {
        ImageTile tile = new ImageTile(library.thumbnails().load(library.ref(), record), width, height, style.fill())
                .favoriteToggle(record.favorite(), false, favorite -> setFavorite(record, favorite));
        tile.setUserData(record.id());
        tile.setSelected(gridSelection.contains(record.id()));

        // MOUSE_PRESSED instead of MOUSE_CLICKED: a CLICKED event only
        // synthesizes if the release lands back on the exact same node as
        // the press, which trackpad jitter can silently break, making clicks/double-
        // clicks feel like they sometimes just don't register. PRESSED
        // fires immediately and unconditionally, and still carries an
        // accurate getClickCount().
        tile.setOnContextMenuRequested(e -> {
            int index = indexOf(record.id());
            if (!gridSelection.contains(record.id())) {
                Set<Integer> previouslySelected = new LinkedHashSet<>(gridSelection);
                gridSelection.clear();
                gridSelection.add(record.id());
                lastClickedIndex = index;
                updateSelectionStyles(previouslySelected);
                updateSelectionLabel();
            }
            buildContextMenu(index).show(tile, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        tile.setOnMousePressed(e -> {
            grid.requestFocus();
            if (e.getButton() != MouseButton.PRIMARY) return;
            // By id: a hover-star toggle swaps this image's record in results for an updated copy.
            int index = indexOf(record.id());
            if (e.getClickCount() == 2) {
                openDetail(index);
                e.consume();
                return;
            }
            Set<Integer> previouslySelected = new LinkedHashSet<>(gridSelection);
            if (e.isShiftDown() && lastClickedIndex != null) {
                int from = Math.min(lastClickedIndex, index);
                int to = Math.max(lastClickedIndex, index);
                for (int i = from; i <= to; i++) gridSelection.add(results.get(i).id());
            } else if (e.isControlDown() || e.isMetaDown()) {
                if (!gridSelection.add(record.id())) gridSelection.remove(record.id());
            } else {
                gridSelection.clear();
                gridSelection.add(record.id());
            }
            lastClickedIndex = index;
            updateSelectionStyles(previouslySelected);
            updateSelectionLabel();
            com.gesturtle.ui.common.Sounds.playUnlessSuperseded(com.gesturtle.ui.common.Sounds.Sound.KEY);
            e.consume();
        });

        return tile;
    }

    /**
     * The tile's hover star. Updates results in place rather than re-running
     * the query, so the grid's selection and scroll position survive. If the
     * filter is on favorites, the image stays until the next refresh.
     */
    private void setFavorite(ImageRecord record, boolean favorite) {
        int index = indexOf(record.id());
        if (index >= 0) {
            results.set(index, results.get(index).withFavorite(favorite));
            // The grid keeps its own copy; patch it too, or a tile rebuilt after scrolling would redraw the old star.
            grid.replaceItem(index, results.get(index));
        }
        AsyncTask.runDb(() -> library.images().setFavorite(record.id(), favorite), () -> {
        }, err -> Dialogs.error("Could not update favorite", err.getMessage()));
    }

    private int indexOf(int imageId) {
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).id() == imageId) return i;
        }
        return -1;
    }

    /** Toggles the "selected" style class on whichever currently-rendered tiles changed, without rebuilding any Node. */
    private void updateSelectionStyles(Set<Integer> previouslySelected) {
        Set<Integer> changed = new LinkedHashSet<>(previouslySelected);
        changed.addAll(gridSelection);
        for (int id : changed) {
            if (grid.nodeFor(id) instanceof ImageTile tile) tile.setSelected(gridSelection.contains(id));
        }
    }

    private void openDetail(int index) {
        // Visible/managed first: ImageDetailView.open() ends with
        // requestFocus(), which JavaFX silently drops on a node that isn't
        // part of the visible scene graph yet. Do it the other order and
        // focus (and so Esc-to-close, arrow-key navigation, etc.) never
        // actually lands on the detail view.
        detailView.setVisible(true);
        detailView.setManaged(true);
        detailView.open(List.copyOf(results), index);
    }

    private void closeDetail() {
        detailView.setVisible(false);
        detailView.setManaged(false);
    }

    private List<ImageRecord> selectedRecords() {
        if (gridMode) {
            return results.stream().filter(r -> gridSelection.contains(r.id())).toList();
        }
        return List.copyOf(table.getSelectionModel().getSelectedItems());
    }

    /** Right-click on a tile or row: the same actions, for whatever is selected (the clicked image, if nothing else). */
    private ContextMenu buildContextMenu(int index) {
        MenuItem open = new MenuItem("Open");
        open.setOnAction(e -> openDetail(index));
        MenuItem tags = new MenuItem("Edit tags…");
        tags.setOnAction(e -> bulkEditTags());
        MenuItem favorite = new MenuItem("Favorite / unfavorite");
        favorite.setOnAction(e -> bulkToggleFavorite());
        MenuItem playlist = new MenuItem("Add to playlist…");
        playlist.setOnAction(e -> bulkAddToPlaylist());
        MenuItem delete = new MenuItem("Delete");
        delete.setOnAction(e -> bulkDelete());
        return new ContextMenu(open, new SeparatorMenuItem(), tags, favorite, playlist, new SeparatorMenuItem(), delete);
    }

    private void bulkEditTags() {
        if (GalleryActions.editTags(library, selectedRecords())) runQuery();
    }

    private void bulkToggleFavorite() {
        List<ImageRecord> selected = selectedRecords();
        if (selected.isEmpty()) return;
        GalleryActions.toggleFavorite(library, selected);
        runQuery();
    }

    private void bulkAddToPlaylist() {
        GalleryActions.addToPlaylist(library, selectedRecords(), null);
    }

    private void bulkDelete() {
        if (GalleryActions.deleteToTrash(library, selectedRecords())) runQuery();
    }

    private void buildTableColumns() {
        thumbCol = new TableColumn<>();
        thumbCol.setSortable(false);
        thumbCol.setResizable(false);
        thumbCol.setCellValueFactory(cd -> new javafx.beans.property.ReadOnlyObjectWrapper<>(cd.getValue()));
        thumbCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            private final javafx.scene.image.ImageView view = new javafx.scene.image.ImageView();

            {
                view.setPreserveRatio(true);
                view.setSmooth(true);
                setAlignment(javafx.geometry.Pos.CENTER);
            }

            @Override
            protected void updateItem(ImageRecord record, boolean empty) {
                super.updateItem(record, empty);
                if (empty || record == null) {
                    setGraphic(null);
                    return;
                }
                view.setImage(library.thumbnails().load(library.ref(), record));
                view.setFitWidth(listThumbSize);
                view.setFitHeight(listThumbSize);
                setGraphic(view);
            }
        });
        // As wide as its thumbnails; setThumbSize keeps it in step with the slider.
        thumbCol.setPrefWidth(listThumbSize + 24);

        TableColumn<ImageRecord, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().originalName()));
        nameCol.setPrefWidth(260);

        TableColumn<ImageRecord, String> dimCol = new TableColumn<>("Dimensions");
        dimCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                cd.getValue().width() + " x " + cd.getValue().height()));

        TableColumn<ImageRecord, String> tagsCol = new TableColumn<>("Tags");
        tagsCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                cd.getValue().tagIds().stream().map(library.tags()::fullPath).collect(Collectors.joining(", "))));
        tagsCol.setPrefWidth(280);

        TableColumn<ImageRecord, String> favCol = new TableColumn<>("★");
        favCol.setPrefWidth(56);
        favCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().favorite() ? "★" : ""));

        TableColumn<ImageRecord, Number> seenCol = new TableColumn<>("Practiced");
        seenCol.setPrefWidth(100);
        seenCol.setCellValueFactory(cd -> new javafx.beans.property.ReadOnlyIntegerWrapper(cd.getValue().seenCount()));

        TableColumn<ImageRecord, Number> sizeCol = new TableColumn<>("Size");
        sizeCol.setPrefWidth(100);
        sizeCol.setCellValueFactory(cd -> new javafx.beans.property.ReadOnlyLongWrapper(cd.getValue().byteSize()));
        sizeCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? "" : humanSize(value.longValue()));
            }
        });

        TableColumn<ImageRecord, String> importedCol = new TableColumn<>("Imported");
        importedCol.setPrefWidth(170);
        importedCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(formatDate(cd.getValue().importedAt())));

        TableColumn<ImageRecord, String> lastSeenCol = new TableColumn<>("Last seen");
        lastSeenCol.setPrefWidth(170);
        lastSeenCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                cd.getValue().lastSeenAt() == null ? "never" : formatDate(cd.getValue().lastSeenAt())));

        table.getColumns().addAll(List.of(thumbCol, favCol, nameCol, dimCol, sizeCol, tagsCol, importedCol, lastSeenCol, seenCol));
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private static String formatDate(long epochMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(epochMillis));
    }
}
