package com.gesturtle.ui.imports;

import com.gesturtle.ui.common.Motion;
import com.gesturtle.config.AppConfig;
import com.gesturtle.config.ConfigStore;
import com.gesturtle.library.FileScan;
import com.gesturtle.library.ImportService;
import com.gesturtle.library.IncomingImages;
import com.gesturtle.library.LibraryService;
import com.gesturtle.model.DuplicateAction;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.ImportCandidate;
import com.gesturtle.model.ImportStatus;
import com.gesturtle.ui.common.AsyncTask;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.common.Refreshable;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.Card;
import com.gesturtle.ui.components.Page;
import com.gesturtle.ui.components.ResponsiveRow;
import com.gesturtle.ui.components.SegmentedControl;
import com.gesturtle.ui.components.TileGrid;
import com.gesturtle.ui.components.TileStyle;
import com.gesturtle.ui.components.Toolbar;
import com.gesturtle.ui.tags.TagPicker;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pick files/folders, review them in the staging table, pick batch tags and
 * whether to delete the originals, then commit.
 */
public class ImportView extends Page implements Refreshable {
    /** Library setting: the staging table's thumbnail size, so the Import page reopens the way it was left. */
    private static final String THUMB_SIZE_KEY = "import_thumb_size";
    /** Library setting: the card view's card size, kept apart from the list's thumbnail size like the Gallery's. */
    private static final String TILE_SIZE_KEY = "import_tile_size";
    private final LibraryService library;
    private final ConfigStore configStore;
    private final AppConfig config;
    private final StagingTable stagingTable;
    private final Label batchTagsLabel = new Label();
    private Set<Integer> batchTagIds = new HashSet<>();
    private final Label selectionLabel = new Label();
    private final AppButton checkSelected = AppButton.icon("check", "Include the selected files", () -> checkSelectedRows(true));
    private final AppButton uncheckSelected = AppButton.icon("close", "Leave out the selected files", () -> checkSelectedRows(false));
    private final AppButton tagSelected = AppButton.icon("tag",
            com.gesturtle.ui.common.Shortcuts.hint(com.gesturtle.ui.common.Shortcuts.Action.TAG), () -> editRowTags(selectedRows()));
    private final AppButton importSelected = AppButton.secondary("Import selected", this::importSelected).small();
    private final Toolbar selectionBar;
    private final StagingGrid stagingGrid;
    /** Holds whichever of the list and the cards is showing. */
    private final javafx.scene.layout.StackPane contentStack = new javafx.scene.layout.StackPane();
    private boolean cardMode;
    private final javafx.scene.control.Slider sizeSlider = new javafx.scene.control.Slider();
    /** True while setMode swaps the slider's range, so the clamping it does isn't taken as a resize. */
    private boolean syncingSlider;
    /** Card layout and fit, this page's own (see TileStyle); the toggles show in card mode only. */
    private TileStyle style;
    private final HBox cardOptions = new HBox(8);
    private final SegmentedControl<TileGrid.Layout> layoutChoice = new SegmentedControl<TileGrid.Layout>()
            .iconOption(TileGrid.Layout.SQUARE, "grid", "Square cards")
            .iconOption(TileGrid.Layout.MASONRY, "masonry", "Cards as tall as their picture (masonry)");
    private final SegmentedControl<Boolean> fillChoice = new SegmentedControl<Boolean>()
            .iconOption(false, "fit", "Show the whole picture")
            .iconOption(true, "fill", "Crop to fill the card");
    private final CheckBox skipDuplicates = new CheckBox("Skip duplicates");
    private final CheckBox includeSubfolders = new CheckBox("Include subfolders");
    private final CheckBox deleteOriginals = new CheckBox("Delete originals after import");
    private int skippedDuplicates = 0;

    public ImportView(LibraryService library, ConfigStore configStore, AppConfig config) {
        super("Import");
        this.library = library;
        this.configStore = configStore;
        this.config = config;
        stagingTable = new StagingTable(library.tags()::fullPath);
        style = TileStyle.load(library.settings(), TileStyle.IMPORT);
        stagingGrid = new StagingGrid(stagingTable, style, this::preview,
                (item, e) -> showRowMenu(item, (javafx.scene.Node) e.getSource(), e.getScreenX(), e.getScreenY()));
        int savedTileSize = library.settings().get(TILE_SIZE_KEY).map(Integer::parseInt)
                .map(v -> Math.max(StagingGrid.MIN_TILE_SIZE, Math.min(StagingGrid.MAX_TILE_SIZE, v)))
                .orElse(StagingGrid.DEFAULT_TILE_SIZE);
        stagingGrid.setTileSize(savedTileSize);
        getStyleClass().add("import-view");
        setSubtitle("No files staged");

        includeSubfolders.setMinWidth(USE_PREF_SIZE);
        skipDuplicates.setMinWidth(USE_PREF_SIZE);
        skipDuplicates.setTooltip(new javafx.scene.control.Tooltip(
                "Leave out files already in the library or repeated in this batch. "
                        + "When off, you choose update, overwrite, or skip per file."));
        skipDuplicates.setSelected(config.skipDuplicatesOnImport);
        skipDuplicates.selectedProperty().addListener((obs, was, on) -> {
            if (config.skipDuplicatesOnImport != on) {
                config.skipDuplicatesOnImport = on;
                configStore.save(config);
            }
            if (on) dropDuplicates();
        });
        deleteOriginals.setSelected(config.deleteOriginalsOnImport);
        deleteOriginals.selectedProperty().addListener((obs, was, on) -> {
            if (config.deleteOriginalsOnImport != on) {
                config.deleteOriginalsOnImport = on;
                configStore.save(config);
            }
        });
        AppButton confirmButton = AppButton.primary("Import checked", () -> confirmImport(
                stagingTable.candidates().stream().filter(ImportCandidate::isIncluded).toList()));
        // List by default: it shows each file's name, size, and status at a glance.
        SegmentedControl<Boolean> modeChoice = new SegmentedControl<Boolean>()
                .iconOption(false, "list", "List")
                .iconOption(true, "grid", "Cards")
                .bare()
                .onChange(this::setMode);
        setActions(modeChoice, confirmButton);

        javafx.scene.layout.FlowPane pickRow = new javafx.scene.layout.FlowPane(10, 10,
                AppButton.secondary("Add files…", this::addFiles).icon("file"),
                AppButton.secondary("Add folder…", this::addFolder).icon("folder"),
                pasteButton(),
                AppButton.secondary("From link…", this::addFromLink).icon("link"));
        pickRow.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.FlowPane pickOptions = new javafx.scene.layout.FlowPane(20, 10, includeSubfolders, skipDuplicates);
        pickOptions.setAlignment(Pos.CENTER_LEFT);
        Card sourcesCard = new Card("Add images", "Or drop files, folders, or browser images anywhere, or paste with Ctrl+V.")
                .add(pickRow, pickOptions);

        batchTagsLabel.getStyleClass().add("muted-label");
        batchTagsLabel.setWrapText(true);
        AppButton chooseTags = AppButton.secondary("Choose batch tags…", this::chooseBatchTags).small().icon("tag");
        chooseTags.setTooltip(new javafx.scene.control.Tooltip(
                "To tag files one at a time, select them and use the tag button (or press T)"));
        javafx.scene.layout.FlowPane tagsLine = new javafx.scene.layout.FlowPane(12, 8, chooseTags, batchTagsLabel);
        tagsLine.setAlignment(Pos.CENTER_LEFT);
        Card optionsCard = new Card("Options").add(
                optionWithHint(tagsLine, "Added to every file, on top of its own tags."),
                optionWithHint(deleteOriginals,
                        "Images are always copied into the library. Pasted and linked images have no original."));
        ResponsiveRow topRow = new ResponsiveRow(900, sourcesCard, optionsCard);

        VBox.setVgrow(contentStack, Priority.ALWAYS);
        stagingTable.setPlaceholder(new com.gesturtle.ui.components.EmptyState("Nothing staged",
                "Add or drop files and folders to review them before import."));
        stagingGrid.setPlaceholder(new com.gesturtle.ui.components.EmptyState("Nothing staged",
                "Add or drop files and folders to review them before import."));
        contentStack.getChildren().add(stagingTable);

        layoutChoice.setValue(style.layout());
        layoutChoice.onChange(v -> setStyle(style.withLayout(v)));
        fillChoice.setValue(style.fill());
        fillChoice.onChange(v -> setStyle(style.withFill(v)));
        cardOptions.getChildren().setAll(layoutChoice, fillChoice);
        cardOptions.setAlignment(Pos.CENTER_LEFT);
        Motion.setShown(cardOptions, false);
        selectionLabel.getStyleClass().add("muted-label");
        selectionBar = new Toolbar().plain()
                .left(selectionLabel)
                .right(cardOptions, thumbSizeControl(), Toolbar.divider(),
                        checkSelected, uncheckSelected, tagSelected, Toolbar.divider(), importSelected,
                        Toolbar.divider(), clearStaged());
        VBox body = new VBox(12, topRow, selectionBar, contentStack);
        VBox.setMargin(selectionBar, new javafx.geometry.Insets(8, 0, 0, 0));
        setBody(body);

        stagingTable.candidates().addListener((javafx.collections.ListChangeListener<ImportCandidate>) c -> {
            if (stagingTable.candidates().isEmpty()) skippedDuplicates = 0;
            updateSubtitle();
            updateSelectionBar();
        });
        stagingTable.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<ImportCandidate>) c -> updateSelectionBar());
        stagingTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<ImportCandidate> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                // Not for quick clicks on the row's checkbox or remove button.
                boolean onButton = false;
                for (javafx.scene.Node n = (javafx.scene.Node) e.getTarget(); n != null && n != row; n = n.getParent()) {
                    if (n instanceof javafx.scene.control.ButtonBase) onButton = true;
                }
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty() && !onButton) {
                    preview(row.getItem());
                }
            });
            row.setOnContextMenuRequested(e -> {
                if (row.isEmpty()) return;
                // Like a file manager: right-clicking outside the selection selects just that row.
                if (!stagingTable.getSelectionModel().getSelectedItems().contains(row.getItem())) {
                    stagingTable.getSelectionModel().clearAndSelect(row.getIndex());
                }
                showRowMenu(row.getItem(), row, e.getScreenX(), e.getScreenY());
                e.consume();
            });
            return row;
        });
        stagingTable.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, this::handleRowKeys);
        // Esc clears the selection in either view. A filter, not a handler: the TableView swallows Esc itself.
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != javafx.scene.input.KeyCode.ESCAPE || stagingTable.getSelectionModel().isEmpty()
                    || e.getTarget() instanceof javafx.scene.control.TextInputControl) return;
            stagingTable.getSelectionModel().clearSelection();
            e.consume();
        });
        stagingGrid.focusTarget().addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, this::handleRowKeys);
        updateSelectionBar();
    }

    /** The Tag and Favorite shortcuts (T and F unless rebound) act on what's selected, in either view. */
    private void handleRowKeys(javafx.scene.input.KeyEvent e) {
        var action = com.gesturtle.ui.common.Shortcuts.match(e, com.gesturtle.ui.common.Shortcuts.Scope.VIEWER);
        if (action == com.gesturtle.ui.common.Shortcuts.Action.TAG) {
            editRowTags(selectedRows());
            e.consume();
        } else if (action == com.gesturtle.ui.common.Shortcuts.Action.FAVORITE && !selectedRows().isEmpty()) {
            List<ImportCandidate> rows = selectedRows();
            setFavorite(rows, !rows.stream().allMatch(ImportCandidate::isFavorite));
            e.consume();
        }
    }

    /** Swaps the list for the cards or back; the selection is the table's either way, so it carries over. */
    private void setMode(boolean cards) {
        cardMode = cards;
        contentStack.getChildren().setAll(cards ? stagingGrid : stagingTable);
        Motion.setShown(cardOptions, cards);
        if (cards) stagingGrid.refresh();
        else stagingTable.refresh();
        // One slider, two sizes: cards in card mode, thumbnails in the list.
        syncingSlider = true;
        sizeSlider.setMin(Math.min(StagingGrid.MIN_TILE_SIZE, StagingTable.MIN_THUMB_SIZE));
        sizeSlider.setMax(Math.max(StagingGrid.MAX_TILE_SIZE, StagingTable.MAX_THUMB_SIZE));
        sizeSlider.setValue(cards ? stagingGrid.tileSize() : stagingTable.thumbnailSize());
        sizeSlider.setMin(cards ? StagingGrid.MIN_TILE_SIZE : StagingTable.MIN_THUMB_SIZE);
        sizeSlider.setMax(cards ? StagingGrid.MAX_TILE_SIZE : StagingTable.MAX_THUMB_SIZE);
        syncingSlider = false;
        (cards ? stagingGrid.focusTarget() : stagingTable).requestFocus();
    }

    private void setStyle(TileStyle newStyle) {
        if (newStyle.equals(style)) return;
        style = newStyle;
        style.save(library.settings(), TileStyle.IMPORT);
        stagingGrid.setStyle(style);
    }

    /** Redraws both views after a change their own listeners can't see (tags, favorites). */
    private void refreshRows() {
        stagingTable.refresh();
        stagingGrid.refresh();
    }

    /**
     * Tags just these files, on top of the batch tags. Like the Gallery's
     * bulk edit: the picker starts with the tags all of them share, newly
     * checked tags are added to each, and unchecked shared ones are removed.
     */
    private void editRowTags(List<ImportCandidate> rows) {
        if (rows.isEmpty()) return;
        Set<Integer> common = new HashSet<>(rows.get(0).tagOverrides());
        rows.forEach(r -> common.retainAll(r.tagOverrides()));
        TagPicker.pickMany(library, common).ifPresent(picked -> {
            for (ImportCandidate row : rows) {
                row.tagOverrides().removeIf(id -> common.contains(id) && !picked.contains(id));
                // Appended, so on import a newly picked tag wins over an older sibling under a pick-one parent.
                for (int id : picked) {
                    if (!row.tagOverrides().contains(id)) row.tagOverrides().add(id);
                }
            }
            refreshRows();
        });
    }

    private ContextMenu rowMenu;

    /**
     * Right-click on the import list: everything the preview can do to a file,
     * for every selected row at once, plus the list's own actions. Built fresh
     * each time, so it reflects the rows' current favorites and playlists.
     */
    private void showRowMenu(ImportCandidate clicked, javafx.scene.Node anchor, double x, double y) {
        List<ImportCandidate> rows = selectedRows();
        if (rows.isEmpty()) return;
        String count = rows.size() == 1 ? "" : " " + rows.size() + " files";

        MenuItem preview = new MenuItem("Preview");
        preview.setOnAction(e -> preview(clicked));

        MenuItem tags = new MenuItem("Edit tags…");
        tags.setOnAction(e -> editRowTags(rows));

        boolean allFavorite = rows.stream().allMatch(ImportCandidate::isFavorite);
        MenuItem favorite = new MenuItem(allFavorite ? "Remove from favorites" : "Favorite" + count);
        // Quiet: setFavorite plays the favorite sound itself, like the Gallery's star.
        favorite.getStyleClass().add(com.gesturtle.ui.common.UiSounds.QUIET);
        favorite.setOnAction(e -> setFavorite(rows, !allFavorite));

        Menu playlistMenu = new Menu("Add to playlist");
        for (com.gesturtle.model.Playlist playlist : library.playlists().listAll()) {
            CheckMenuItem item = new CheckMenuItem(playlist.name());
            item.setSelected(rows.stream().allMatch(r -> r.playlistIds().contains(playlist.id())));
            item.setOnAction(e -> rows.forEach(r -> {
                if (item.isSelected()) r.playlistIds().add(playlist.id());
                else r.playlistIds().remove(playlist.id());
            }));
            playlistMenu.getItems().add(item);
        }
        if (!playlistMenu.getItems().isEmpty()) playlistMenu.getItems().add(new SeparatorMenuItem());
        MenuItem newPlaylist = new MenuItem("New playlist…");
        newPlaylist.setOnAction(e -> Dialogs.prompt("New playlist", "Name", "").ifPresent(name -> {
            if (name.isBlank()) return;
            try {
                int id = library.playlists().create(name.trim());
                rows.forEach(r -> r.playlistIds().add(id));
            } catch (RuntimeException ex) {
                Dialogs.error("Could not create playlist", ex.getMessage());
            }
        }));
        playlistMenu.getItems().add(newPlaylist);

        List<ImportCandidate> includable = rows.stream().filter(ImportCandidate::isIncludable).toList();
        boolean allIncluded = !includable.isEmpty() && includable.stream().allMatch(ImportCandidate::isIncluded);
        MenuItem include = new MenuItem(allIncluded ? "Leave out of import" : "Include in import");
        include.setDisable(includable.isEmpty());
        include.setOnAction(e -> stagingTable.setIncluded(includable, !allIncluded));

        MenuItem showInFolder = new MenuItem("Show in folder");
        showInFolder.setOnAction(e -> showInFolder(clicked.sourcePath()));

        MenuItem remove = new MenuItem(rows.size() == 1 ? "Remove from list" : "Remove" + count + " from list");
        remove.setOnAction(e -> stagingTable.candidates().removeAll(rows));

        if (rowMenu != null) rowMenu.hide();
        rowMenu = new ContextMenu(preview, new SeparatorMenuItem(), tags, favorite, playlistMenu,
                new SeparatorMenuItem(), include, showInFolder, new SeparatorMenuItem(), remove);
        rowMenu.show(anchor, x, y);
    }

    private void setFavorite(List<ImportCandidate> rows, boolean favorite) {
        rows.forEach(r -> r.setFavorite(favorite));
        com.gesturtle.ui.common.Sounds.play(favorite ? com.gesturtle.ui.common.Sounds.Sound.FAVORITE
                : com.gesturtle.ui.common.Sounds.Sound.UNFAVORITE);
        refreshRows();
    }

    /** Opens the file's folder in the system file manager, with the file selected where the OS can do that. */
    private static void showInFolder(Path file) {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> command = os.contains("win") ? List.of("explorer.exe", "/select," + file.toAbsolutePath())
                : os.contains("mac") ? List.of("open", "-R", file.toAbsolutePath().toString())
                : List.of("xdg-open", file.toAbsolutePath().getParent().toString());
        try {
            new ProcessBuilder(command).start();
        } catch (IOException ex) {
            Dialogs.error("Could not open the folder", ex.getMessage());
        }
    }

    /** Opens a staged file at full size; stepping through files there moves the table's selection along. */
    private void preview(ImportCandidate file) {
        List<ImportCandidate> files = List.copyOf(stagingTable.getItems());
        PreviewDialog.show(library, files, Math.max(0, files.indexOf(file)), shown -> {
            stagingTable.getSelectionModel().clearAndSelect(stagingTable.getItems().indexOf(shown));
            stagingTable.scrollTo(shown);
        }, this::refreshRows);
    }

    private AppButton clearStaged() {
        AppButton clear = AppButton.icon("trash", "Clear the list. Nothing is deleted from disk.", () -> {
            int count = stagingTable.candidates().size();
            if (count == 0) return;
            // Asks first: the tags and playlists picked per file go with them.
            String files = count + (count == 1 ? " file" : " files");
            if (Dialogs.confirmDanger("Clear staged files",
                    "Remove all " + files + " from the import list, along with any tags and playlists picked for them? "
                            + "Nothing is deleted from disk.", "Clear")) {
                stagingTable.candidates().clear();
            }
        });
        clear.getStyleClass().add("btn-ghost-danger");
        return clear;
    }

    /** A slider for the thumbnail size (list) or card size (cards), matching the Gallery's tile-size slider. */
    private HBox thumbSizeControl() {
        library.settings().get(THUMB_SIZE_KEY).map(Integer::parseInt).ifPresent(stagingTable::setThumbnailSize);
        javafx.scene.control.Slider slider = sizeSlider;
        slider.setMin(StagingTable.MIN_THUMB_SIZE);
        slider.setMax(StagingTable.MAX_THUMB_SIZE);
        slider.setValue(stagingTable.thumbnailSize());
        slider.setPrefWidth(120);
        slider.setTooltip(new javafx.scene.control.Tooltip("Thumbnail size"));
        slider.valueProperty().addListener((o, a, v) -> {
            if (syncingSlider) return;
            if (cardMode) stagingGrid.setTileSize(v.intValue());
            else stagingTable.setThumbnailSize(v.intValue());
        });
        Runnable save = () -> {
            if (cardMode) library.settings().set(TILE_SIZE_KEY, String.valueOf((int) stagingGrid.tileSize()));
            else library.settings().set(THUMB_SIZE_KEY, String.valueOf(stagingTable.thumbnailSize()));
        };
        slider.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing) save.run();
        });
        slider.setOnMouseReleased(e -> save.run());
        HBox box = new HBox(8, new com.gesturtle.ui.components.Icon("gallery", 14), slider,
                new com.gesturtle.ui.components.Icon("gallery", 20));
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("tile-size-control");
        return box;
    }

    private void checkSelectedRows(boolean checked) {
        stagingTable.setIncluded(selectedRows(), checked);
    }

    private List<ImportCandidate> selectedRows() {
        return List.copyOf(stagingTable.getSelectionModel().getSelectedItems());
    }

    /**
     * The bar is how a big staged folder gets imported a slice at a time:
     * select a run of rows (Shift/Ctrl-click), then check them, or import
     * just those. It stays visible while anything is staged so the hint is
     * there before you've discovered multi-select.
     */
    private void updateSelectionBar() {
        boolean staged = !stagingTable.candidates().isEmpty();
        Motion.setShown(selectionBar, staged);
        int selected = stagingTable.getSelectionModel().getSelectedItems().size();
        long checked = stagingTable.candidates().stream().filter(ImportCandidate::isIncluded).count();
        selectionLabel.setText(selected == 0
                ? checked + " checked"
                : selected + " selected · " + checked + " checked");
        checkSelected.setDisable(selected == 0);
        uncheckSelected.setDisable(selected == 0);
        tagSelected.setDisable(selected == 0);
        importSelected.setDisable(selected == 0);
    }

    private void updateSubtitle() {
        int n = stagingTable.candidates().size();
        String text = n == 0 ? "No files staged" : n + (n == 1 ? " file staged" : " files staged");
        if (skippedDuplicates > 0) text += " · " + skippedDuplicates + " duplicate" + (skippedDuplicates == 1 ? "" : "s") + " skipped";
        setSubtitle(text);
    }

    private static boolean isDuplicate(ImportCandidate c) {
        return c.getStatus() == ImportStatus.ALREADY_IN_LIBRARY || c.getStatus() == ImportStatus.DUPLICATE_IN_BATCH;
    }

    /** Duplicates are only known once a probe has hashed the file, so they're dropped as probes complete. */
    private void dropDuplicates() {
        int before = stagingTable.candidates().size();
        stagingTable.candidates().removeIf(ImportView::isDuplicate);
        skippedDuplicates += before - stagingTable.candidates().size();
        updateSubtitle();
    }

    @Override
    public void onShown() {
        // The same preference can be changed from Settings while this page is cached.
        skipDuplicates.setSelected(config.skipDuplicatesOnImport);
    }

    private void addFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose images to import");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"));
        List<java.io.File> files = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (files == null) return;
        stageFiles(files.stream().map(java.io.File::toPath));
    }

    private void addFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose a folder to import");
        java.io.File dir = chooser.showDialog(getScene().getWindow());
        if (dir == null) return;
        stagePaths(List.of(dir.toPath()));
    }

    /**
     * Stages a mix of files and folders, as dropped onto the window: folders
     * are expanded the same way "Add folder…" does, honoring Include
     * subfolders; unsupported files are quietly left out.
     */
    public void stagePaths(List<Path> paths) {
        boolean recursive = includeSubfolders.isSelected();
        // Off the FX thread: a big folder tree takes a while to list. Unreadable subfolders are skipped.
        AsyncTask.runIo(() -> {
            List<Path> files = new java.util.ArrayList<>();
            List<String> unreadable = new java.util.ArrayList<>();
            for (Path path : paths) {
                if (!Files.isDirectory(path)) {
                    files.add(path);
                    continue;
                }
                try {
                    files.addAll(FileScan.files(path, recursive));
                } catch (IOException e) {
                    unreadable.add(path + ": " + e.getMessage());
                }
            }
            return new ScannedFolders(files, unreadable);
        }, scanned -> {
            if (!scanned.unreadable().isEmpty()) {
                Dialogs.error("Could not read folder", String.join("\n", scanned.unreadable()));
            }
            stageFiles(scanned.files().stream());
        }, err -> Dialogs.error("Could not read folder", err.getMessage()));
    }

    private record ScannedFolders(List<Path> files, List<String> unreadable) {
    }

    private AppButton pasteButton() {
        AppButton paste = AppButton.secondary("Paste", () -> {
            if (!stageClipboard()) {
                Dialogs.info("Nothing to paste", "Copy an image, image files, or a link to an image first.");
            }
        }).icon("clipboard");
        paste.setTooltip(new javafx.scene.control.Tooltip("Paste from the clipboard (Ctrl+V)"));
        return paste;
    }

    private void addFromLink() {
        String suggested = "";
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasString() && IncomingImages.isImageLink(clipboard.getString())) suggested = clipboard.getString().strip();
        Dialogs.prompt("Import from link", "Image link", suggested).ifPresent(this::stageLink);
    }

    /**
     * Stages whatever image the clipboard holds: copied files first, then a
     * copied picture (credited with the page link the browser put beside it,
     * if any), then a link to an image. Returns false when there's nothing
     * usable on it.
     */
    public boolean stageClipboard() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasFiles()) {
            stagePaths(clipboard.getFiles().stream().map(java.io.File::toPath).toList());
            return true;
        }
        String link = clipboard.hasUrl() ? clipboard.getUrl() : clipboard.hasString() ? clipboard.getString() : null;
        if (link != null && IncomingImages.isImageLink(link) && !clipboard.hasImage()) {
            stageLink(link);
            return true;
        }
        if (clipboard.hasImage()) {
            stageImage(clipboard.getImage(), link != null && link.strip().startsWith("http") ? link.strip() : null);
            return true;
        }
        return false;
    }

    /** Downloads the image a link points at in the background, then stages it with the link as its credit. */
    public void stageLink(String link) {
        setSubtitle("Downloading…");
        AsyncTask.runIo(() -> {
            try {
                return IncomingImages.download(link);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, path -> {
            updateSubtitle();
            String trimmed = link.strip();
            stageFiles(Stream.of(path), trimmed.startsWith("http") ? trimmed : null);
        }, err -> {
            updateSubtitle();
            Throwable cause = err instanceof java.io.UncheckedIOException u ? u.getCause() : err;
            Dialogs.error("Could not import from link", cause.getMessage());
        });
    }

    /** Stages a bitmap (pasted, or dragged from a browser) as a PNG. credit may be null. */
    public void stageImage(Image image, String credit) {
        if (image == null || image.getPixelReader() == null) return;
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        if (w <= 0 || h <= 0) return;
        int[] pixels = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
        AsyncTask.runIo(() -> {
            java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            buffered.setRGB(0, 0, w, h, pixels, 0, w);
            try {
                return IncomingImages.savePng(buffered, "pasted-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, path -> stageFiles(Stream.of(path), credit),
                err -> Dialogs.error("Could not paste image", err.getMessage()));
    }

    private void stageFiles(Stream<Path> files) {
        stageFiles(files, null);
    }

    private void stageFiles(Stream<Path> files, String credit) {
        List<ImportCandidate> newCandidates = files
                .filter(ImportService::isSupported)
                .map(ImportCandidate::new)
                .toList();
        newCandidates.forEach(c -> {
            c.setCredit(credit);
            if (credit != null && config.linkInNotes) c.setNotes(credit);
        });
        if (!newCandidates.isEmpty()) com.gesturtle.ui.common.Sounds.play(com.gesturtle.ui.common.Sounds.Sound.DROP);
        stagingTable.candidates().addAll(newCandidates);
        for (ImportCandidate candidate : newCandidates) {
            AsyncTask.runDb(
                    () -> library.importService().probe(candidate.sourcePath(), library.images()),
                    outcome -> {
                        candidate.applyProbe(outcome);
                        library.importService().markBatchDuplicates(stagingTable.candidates());
                        if (skipDuplicates.isSelected()) dropDuplicates();
                        refreshRows();
                    },
                    err -> candidate.setStatus(com.gesturtle.model.ImportStatus.UNREADABLE, err.getMessage()));
        }
    }

    private void chooseBatchTags() {
        TagPicker.pickMany(library, batchTagIds).ifPresent(this::setBatchTags);
    }

    /** An Options control with its muted explanation underneath; the control's own text names it, so no label column. */
    private static VBox optionWithHint(javafx.scene.Node control, String hint) {
        Label hintLabel = new Label(hint);
        hintLabel.getStyleClass().add("hint-label");
        hintLabel.setWrapText(true);
        return new VBox(6, control, hintLabel);
    }

    private void setBatchTags(Set<Integer> ids) {
        batchTagIds = ids;
        batchTagsLabel.setText(ids.isEmpty() ? ""
                : ids.stream().map(library.tags()::fullPath).collect(Collectors.joining(", ")));
    }

    /** Imports the selected rows whether or not they were checked, checking them first so the table agrees. */
    private void importSelected() {
        List<ImportCandidate> rows = selectedRows().stream().filter(ImportCandidate::isIncludable).toList();
        stagingTable.setIncluded(rows, true);
        confirmImport(rows);
    }

    /**
     * Imports exactly these rows. Afterwards they leave the staging table
     * unless they failed; failures stay checked, with the reason in their
     * status, so the next import retries them. Everything else staged is
     * untouched for a later pass.
     */
    private void confirmImport(List<ImportCandidate> rows) {
        List<ImportCandidate> snapshot = List.copyOf(rows);
        if (snapshot.isEmpty()) {
            Dialogs.error("Nothing to import", stagingTable.candidates().isEmpty()
                    ? "Add files first."
                    : "Check files, or select rows and use Import selected.");
            return;
        }
        List<ImportCandidate> duplicates = snapshot.stream()
                .filter(c -> c.getStatus() == ImportStatus.ALREADY_IN_LIBRARY).toList();
        if (duplicates.isEmpty()) {
            runImport(snapshot);
            return;
        }
        AsyncTask.runDb(
                () -> duplicates.stream().map(c -> library.images().findByHash(c.getHash()).orElse(null)).toList(),
                existing -> {
                    if (chooseDuplicateActions(duplicates, existing)) runImport(snapshot);
                },
                err -> Dialogs.error("Import failed", err.getMessage()));
    }

    /**
     * Asks Update / Overwrite / Skip for each already-present row, until the
     * user ticks "do this for the rest". Returns false if they cancelled,
     * in which case nothing is imported.
     */
    private boolean chooseDuplicateActions(List<ImportCandidate> duplicates, List<ImageRecord> existing) {
        DuplicateAction forRest = null;
        for (int i = 0; i < duplicates.size(); i++) {
            ImportCandidate candidate = duplicates.get(i);
            if (forRest == null && existing.get(i) != null) {
                var choice = DuplicateDialog.ask(library, candidate, existing.get(i), duplicates.size() - i - 1);
                if (choice.isEmpty()) return false;
                candidate.setDuplicateAction(choice.get().action());
                if (choice.get().applyToRest()) forRest = choice.get().action();
            } else if (forRest != null) {
                candidate.setDuplicateAction(forRest);
            }
        }
        return true;
    }

    private void runImport(List<ImportCandidate> snapshot) {
        boolean move = deleteOriginals.isSelected();

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(380);
        Label progressLabel = new Label("Starting…");
        Alert progressAlert = new Alert(Alert.AlertType.NONE);
        progressAlert.setTitle("Importing");
        progressAlert.getDialogPane().setContent(new VBox(12, progressLabel, progressBar));
        progressAlert.getButtonTypes().add(ButtonType.CANCEL);
        Dialogs.prepare(progressAlert);
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        progressAlert.setOnCloseRequest(e -> cancelFlag.set(true));
        progressAlert.getDialogPane().lookupButton(ButtonType.CANCEL).addEventFilter(
                javafx.event.ActionEvent.ACTION, e -> cancelFlag.set(true));
        progressAlert.show();

        AsyncTask.runDb(
                () -> library.importService().importBatch(snapshot, batchTagIds, move, library.images(),
                        cancelFlag, i -> Platform.runLater(() -> {
                            progressBar.setProgress((i + 1.0) / snapshot.size());
                            progressLabel.setText("Processing " + (i + 1) + " / " + snapshot.size());
                        })),
                result -> {
                    progressAlert.setResult(ButtonType.CLOSE);
                    progressAlert.close();
                    if (result.cancelled()) {
                        Dialogs.info("Import cancelled", "No changes were made.");
                        return;
                    }
                    result.failures().forEach(ImportCandidate::markFailed);
                    // Failed rows are retried later; carry this pass's batch tags on them, since the batch tags reset below.
                    result.failures().keySet().forEach(c -> c.tagOverrides().addAll(batchTagIds));
                    Set<ImportCandidate> done = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                    for (ImportCandidate c : snapshot) {
                        if (!result.failures().containsKey(c)) done.add(c);
                    }
                    stagingTable.candidates().removeIf(done::contains);
                    int remaining = stagingTable.candidates().size();
                    String message = result.imported() + " imported, "
                            + result.updated() + " updated, "
                            + result.overwritten() + " overwritten, "
                            + result.skipped() + " skipped, " + result.failed() + " failed.";
                    if (result.failed() > 0) message += "\n\nFailed files stay staged. Import again to retry.";
                    if (remaining > 0) {
                        message += "\n\n" + remaining + (remaining == 1 ? " file is" : " files are") + " still staged.";
                        // The next slice of a presorted folder usually wants different tags; don't let these leak onto it.
                        if (!batchTagIds.isEmpty()) {
                            setBatchTags(new HashSet<>());
                            message += " Batch tags cleared.";
                        }
                    }
                    Dialogs.info("Import complete", message);
                },
                err -> {
                    progressAlert.close();
                    Dialogs.error("Import failed", err.getMessage());
                });
    }
}
