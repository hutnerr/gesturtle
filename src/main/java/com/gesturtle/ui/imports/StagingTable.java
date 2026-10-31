package com.gesturtle.ui.imports;

import com.gesturtle.model.ImportCandidate;
import com.gesturtle.model.ImportStatus;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.Observable;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import com.gesturtle.ui.components.AppButton;
import javafx.scene.control.CheckBox;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import com.gesturtle.ui.common.ImageLoader;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The import review table: thumbnail, filename, dimensions, size, the file's
 * own tags, status, remove. The Import column's header checkbox checks or
 * unchecks every row that can be imported, Shift-clicking a checkbox sets
 * the whole range since the last one clicked, and Space toggles the
 * checkboxes of all selected rows. Together with the Import page's selection bar, that
 * makes it practical to stage a large folder and import it a slice at a time.
 */
public class StagingTable extends TableView<ImportCandidate> {
    public static final int DEFAULT_THUMB_SIZE = 56;
    public static final int MIN_THUMB_SIZE = 40;
    public static final int MAX_THUMB_SIZE = 200;
    /** Thumbnails decode in steps of this, so dragging the size slider doesn't re-decode every file at every pixel. */
    private static final int DECODE_STEP = 64;
    /** The extractor makes a checkbox or status change fire a list update, so the header and listeners stay in sync. */
    private final ObservableList<ImportCandidate> candidates = FXCollections.observableArrayList(
            c -> new Observable[]{c.includedProperty(), c.statusProperty(), c.reasonProperty()});
    private final CheckBox headerCheckBox = new CheckBox();
    private boolean syncingHeader;
    /** Row of the last checkbox clicked; a Shift-click extends from here, like a file manager's range select. */
    private int checkAnchor = -1;
    /**
     * Each file's thumbnail, decoded once. Any checkbox or status change
     * redraws every row, and re-decoding each full-size file then blacked
     * out all the thumbnails (JavaFX paints a JPEG black while it's still
     * loading in the background) until they caught up.
     */
    private final Map<ImportCandidate, Thumb> thumbnails = new IdentityHashMap<>();
    /** Files with a thumbnail decode in flight, so the rows redrawn meanwhile don't each start another. */
    private final Set<ImportCandidate> loading = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Others (the card view) waiting on a file's decode, with the size each one wants. */
    private final Map<ImportCandidate, List<Waiter>> waiters = new IdentityHashMap<>();
    /**
     * The image view showing each on-screen file. A decode finishing after its
     * cell was recycled to another row updates whichever cell shows the file now.
     */
    private final Map<ImportCandidate, ImageView> shownIn = new IdentityHashMap<>();
    private int thumbSize = DEFAULT_THUMB_SIZE;
    private final TableColumn<ImportCandidate, ImportCandidate> thumbCol = new TableColumn<>("");

    /** A fully decoded thumbnail (null if the file couldn't be decoded) and the size it was decoded at. */
    private record Thumb(Image image, int decodedSize) {
    }

    private record Waiter(int size, Consumer<Image> onReady) {
    }

    /** tagName gives a tag's display path, for the Tags column. */
    public StagingTable(Function<Integer, String> tagName) {
        setItems(candidates);
        setFixedCellSize(thumbSize + 12);
        setColumnResizePolicy(CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<ImportCandidate, ImportCandidate> includeCol = new TableColumn<>("");
        headerCheckBox.setOnAction(e -> {
            if (syncingHeader) return;
            setIncluded(candidates, !allIncludableChecked());
        });
        headerCheckBox.setTooltip(new javafx.scene.control.Tooltip("Toggle all"));
        includeCol.setGraphic(headerCheckBox);
        includeCol.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        includeCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();

            {
                // MOUSE_CLICKED arrives after the checkbox has toggled, so isSelected() is the state to spread.
                checkBox.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
                    int index = getIndex();
                    if (e.isShiftDown() && checkAnchor >= 0 && checkAnchor < getItems().size() && checkAnchor != index) {
                        int from = Math.min(checkAnchor, index), to = Math.max(checkAnchor, index);
                        setIncluded(List.copyOf(getItems().subList(from, to + 1)), checkBox.isSelected());
                    }
                    checkAnchor = index;
                });
            }
            /** Cells are recycled across rows; unbind from the previous row, or one checkbox ends up driving many. */
            private ImportCandidate bound;

            @Override
            protected void updateItem(ImportCandidate item, boolean empty) {
                super.updateItem(item, empty);
                if (bound != null) {
                    checkBox.selectedProperty().unbindBidirectional(bound.includedProperty());
                    bound = null;
                }
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                checkBox.setDisable(!item.isIncludable());
                checkBox.selectedProperty().bindBidirectional(item.includedProperty());
                bound = item;
                setGraphic(checkBox);
            }
        });
        includeCol.setPrefWidth(76);
        includeCol.setSortable(false);

        thumbCol.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        thumbCol.setCellFactory(col -> new TableCell<>() {
            private final ImageView imageView = new ImageView();
            /** The file this cell last showed; cells are recycled, so its entry in shownIn must be let go. */
            private ImportCandidate shown;

            @Override
            protected void updateItem(ImportCandidate item, boolean empty) {
                super.updateItem(item, empty);
                if (shown != null && shownIn.get(shown) == imageView) shownIn.remove(shown);
                shown = null;
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                shown = item;
                shownIn.put(item, imageView);
                imageView.setFitWidth(thumbSize);
                imageView.setFitHeight(thumbSize);
                imageView.setPreserveRatio(true);
                // A too-small thumbnail stays up, scaled, while the sharper one decodes.
                Thumb cached = thumbnails.get(item);
                imageView.setImage(cached == null ? null : cached.image());
                if (cached == null || cached.decodedSize() < thumbSize) loadThumbnail(item, thumbSize);
                setGraphic(imageView);
            }
        });
        thumbCol.setPrefWidth(thumbSize + 20);
        candidates.addListener((ListChangeListener<ImportCandidate>) c -> {
            while (c.next()) c.getRemoved().forEach(removed -> {
                thumbnails.remove(removed);
                loading.remove(removed);
                waiters.remove(removed);
            });
        });
        thumbCol.setSortable(false);

        TableColumn<ImportCandidate, String> nameCol = new TableColumn<>("File");
        nameCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().fileName()));
        nameCol.setPrefWidth(260);

        TableColumn<ImportCandidate, String> dimCol = new TableColumn<>("Dimensions");
        dimCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().dimensionsText()));

        TableColumn<ImportCandidate, String> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(humanSize(cd.getValue().getByteSize())));

        // Not observable (tagOverrides is a plain list): whoever edits a row's tags refreshes the table.
        TableColumn<ImportCandidate, String> tagsCol = new TableColumn<>("Tags");
        tagsCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().tagOverrides().stream()
                .map(tagName).collect(Collectors.joining(", "))));
        tagsCol.setPrefWidth(220);
        tagsCol.setSortable(false);

        // A star on files to be imported as favorites (set in the preview); refreshed along with the tags.
        TableColumn<ImportCandidate, Boolean> favoriteCol = new TableColumn<>("");
        favoriteCol.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().isFavorite()));
        favoriteCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Boolean favorite, boolean empty) {
                super.updateItem(favorite, empty);
                if (empty || favorite == null || !favorite) {
                    setGraphic(null);
                    return;
                }
                com.gesturtle.ui.components.Icon star = new com.gesturtle.ui.components.Icon("favorite-filled", 14);
                star.getStyleClass().add("staged-favorite");
                setGraphic(star);
            }
        });
        favoriteCol.setPrefWidth(36);
        favoriteCol.setResizable(false);
        favoriteCol.setSortable(false);

        TableColumn<ImportCandidate, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(cd -> {
            ImportCandidate c = cd.getValue();
            String text = statusLabel(c.getStatus());
            String reason = c.reasonProperty().get();
            return new ReadOnlyStringWrapper(reason == null || reason.isBlank() ? text : text + " (" + reason + ")");
        });
        statusCol.setPrefWidth(260);

        TableColumn<ImportCandidate, ImportCandidate> removeCol = new TableColumn<>("");
        removeCol.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        removeCol.setCellFactory(col -> new TableCell<>() {
            private final AppButton removeButton = AppButton.icon("close", "Remove", null);

            @Override
            protected void updateItem(ImportCandidate item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                removeButton.setOnAction(e -> candidates.remove(item));
                setGraphic(removeButton);
            }
        });
        removeCol.setSortable(false);

        getColumns().addAll(java.util.List.of(includeCol, thumbCol, nameCol, favoriteCol, dimCol, sizeCol, tagsCol, statusCol, removeCol));

        candidates.addListener((ListChangeListener<ImportCandidate>) c -> syncHeader());
        syncHeader();

        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.SPACE || getSelectionModel().isEmpty()) return;
            toggleSelectedIncluded();
            e.consume();
        });
    }

    /** Space: checks the selected rows, or unchecks them if they're all checked already. */
    public void toggleSelectedIncluded() {
        List<ImportCandidate> selected = List.copyOf(getSelectionModel().getSelectedItems());
        boolean anyUnchecked = selected.stream().anyMatch(c -> c.isIncludable() && !c.isIncluded());
        setIncluded(selected, anyUnchecked);
    }

    /**
     * A file's thumbnail at least size pixels across, from the same cache the
     * rows use. onReady runs right away if one is cached (a smaller one is
     * passed too, as a stand-in), and again once a big enough one decodes;
     * it gets null if the file can't be decoded.
     */
    public void thumbnail(ImportCandidate item, int size, Consumer<Image> onReady) {
        Thumb cached = thumbnails.get(item);
        if (cached != null) onReady.accept(cached.image());
        if (cached != null && cached.decodedSize() >= size) return;
        waiters.computeIfAbsent(item, k -> new java.util.ArrayList<>()).add(new Waiter(size, onReady));
        loadThumbnail(item, size);
    }

    /** Resizes the thumbnail column and rows; thumbnails decoded smaller than this are re-decoded. */
    public void setThumbnailSize(int size) {
        size = Math.max(MIN_THUMB_SIZE, Math.min(MAX_THUMB_SIZE, size));
        if (size == thumbSize) return;
        thumbSize = size;
        setFixedCellSize(thumbSize + 12);
        thumbCol.setPrefWidth(thumbSize + 20);
        refresh();
    }

    public int thumbnailSize() {
        return thumbSize;
    }

    /** Decodes this file's thumbnail at this size, rounded up to a step, and shows it once fully loaded. */
    private void loadThumbnail(ImportCandidate item, int size) {
        if (!loading.add(item)) return;
        int decodeSize = (size + DECODE_STEP - 1) / DECODE_STEP * DECODE_STEP;
        ImageLoader.load(item.sourcePath(), decodeSize, decodeSize, image -> {
            if (image == null) {
                finishThumbnail(item, null, decodeSize);
            } else if (image.getProgress() >= 1) {
                finishThumbnail(item, image, decodeSize);
            } else {
                image.progressProperty().addListener((o, a, p) -> {
                    if (p.doubleValue() >= 1) finishThumbnail(item, image, decodeSize);
                });
            }
        });
    }

    private void finishThumbnail(ImportCandidate item, Image image, int decodeSize) {
        // Dropped from the table meanwhile.
        if (!loading.remove(item)) return;
        Image usable = image == null || image.isError() ? null : image;
        // A failed decode is recorded too (at any size), so it isn't retried on every redraw.
        thumbnails.put(item, new Thumb(usable, usable == null ? Integer.MAX_VALUE : decodeSize));
        ImageView view = shownIn.get(item);
        if (view != null && usable != null) view.setImage(usable);
        List<Waiter> waiting = waiters.remove(item);
        if (waiting == null) return;
        int largest = 0;
        for (Waiter w : waiting) {
            w.onReady().accept(usable);
            // Joined a smaller decode already in flight: wait on for a sharper one.
            if (usable != null && w.size() > decodeSize) {
                waiters.computeIfAbsent(item, k -> new java.util.ArrayList<>()).add(w);
                largest = Math.max(largest, w.size());
            }
        }
        if (largest > 0) loadThumbnail(item, largest);
    }

    /** Checks or unchecks these rows; rows that can't be imported (unreadable, duplicate) are left alone. */
    public void setIncluded(List<ImportCandidate> rows, boolean included) {
        for (ImportCandidate c : rows) {
            if (c.isIncludable()) c.includedProperty().set(included);
        }
    }

    private boolean allIncludableChecked() {
        return candidates.stream().filter(ImportCandidate::isIncludable).allMatch(ImportCandidate::isIncluded);
    }

    /** Header shows checked when every importable row is, unchecked when none is, and a dash when it's mixed. */
    private void syncHeader() {
        long includable = candidates.stream().filter(ImportCandidate::isIncludable).count();
        long checked = candidates.stream().filter(c -> c.isIncludable() && c.isIncluded()).count();
        syncingHeader = true;
        headerCheckBox.setDisable(includable == 0);
        headerCheckBox.setIndeterminate(checked > 0 && checked < includable);
        headerCheckBox.setSelected(includable > 0 && checked == includable);
        syncingHeader = false;
    }

    public ObservableList<ImportCandidate> candidates() {
        return candidates;
    }

    private static String statusLabel(ImportStatus status) {
        return switch (status) {
            case NEW -> "New";
            case ALREADY_IN_LIBRARY -> "Already in library";
            case DUPLICATE_IN_BATCH -> "Duplicate in batch";
            case UNREADABLE -> "Unreadable";
        };
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
