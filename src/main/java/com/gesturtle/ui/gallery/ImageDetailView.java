package com.gesturtle.ui.gallery;

import com.gesturtle.library.LibraryService;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.Tag;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.common.ImageLoader;
import com.gesturtle.ui.common.Shortcuts;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.SegmentedControl;
import com.gesturtle.ui.components.Toolbar;
import com.gesturtle.ui.components.ViewerScreen;
import com.gesturtle.ui.playlist.PlaylistChecklist;
import com.gesturtle.ui.tags.TagActions;
import com.gesturtle.ui.tags.TagChip;
import com.gesturtle.ui.tags.TagPicker;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Full-size image view with metadata, favorite/tag editing, and arrow-key
 * navigation, over the Gallery page (see ViewerScreen for the viewer, Esc,
 * and the full screen and hidden-controls study mode it shares with the
 * Import preview). Flip and grayscale carry over when moving between
 * images; zoom resets. Credit and notes save when their field loses focus
 * or the view moves to another image.
 *
 * Edit mode (the View/Edit switch, or M) swaps the info sidebar for an
 * EditPanel, the tag tree and playlist checklist, for going through images one after another:
 * tick, press →, tick. Every tick is written straight away rather than on
 * leaving the image, the same as favoriting. The mode sticks across images
 * and across openings, since someone tidying up tags tends to keep at it.
 */
public class ImageDetailView extends ViewerScreen {
    private final LibraryService library;
    private final Runnable onLibraryChanged;

    private List<ImageRecord> context;
    private int index;
    private String shownHash;

    private final Label nameLabel = new Label();
    private final GridPane metaGrid = new GridPane();
    private final ToggleButton favoriteButton = AppButton.small(AppButton.favoriteToggle(AppButton.Variant.GHOST));
    private final FlowPane tagsPane = new FlowPane(8, 8);
    private final Label positionLabel = new Label();
    private final TextField creditField = new TextField();
    private final TextArea notesArea = new TextArea();
    private final BooleanProperty editMode = new SimpleBooleanProperty(false);
    private final SegmentedControl<Boolean> modeSwitch = new SegmentedControl<Boolean>()
            .option(false, "View", "eye")
            .option(true, "Edit", "pencil");
    private final TagPicker tagPicker;
    private final PlaylistChecklist playlists;

    public ImageDetailView(LibraryService library, Runnable onClose, Runnable onLibraryChanged) {
        super(onClose);
        this.library = library;
        this.onLibraryChanged = onLibraryChanged;

        favoriteButton.setOnAction(e -> toggleFavorite());
        positionLabel.getStyleClass().add("muted-label");
        List<javafx.scene.Node> viewerTools = viewer.toolButtons();
        AppButton backButton = AppButton.icon("arrow-left", "Back to the grid (Esc)", this::close);
        AppButton tagsButton = AppButton.icon("tag", Shortcuts.hint(Shortcuts.Action.TAG), this::editTags);
        javafx.scene.control.Tooltip.install(modeSwitch, new javafx.scene.control.Tooltip(
                "View the image's details, or edit its tags and playlists (" + Shortcuts.describe(Shortcuts.Action.EDIT_MODE) + ")"));
        modeSwitch.onChange(editMode::set);
        AppButton deleteButton = AppButton.icon("trash", "Move to the " + com.gesturtle.library.SystemTrash.NAME, this::delete);
        deleteButton.getStyleClass().add("btn-ghost-danger");
        Toolbar topBar = new Toolbar()
                .left(backButton, modeSwitch)
                .center(AppButton.icon("chevron-left", Shortcuts.hint(Shortcuts.Action.PREVIOUS), () -> move(-1)), positionLabel,
                        AppButton.icon("chevron-right", Shortcuts.hint(Shortcuts.Action.NEXT), () -> move(1)))
                .right(viewerTools.toArray(javafx.scene.Node[]::new))
                .right(Toolbar.divider(), favoriteButton, tagsButton,
                        Toolbar.divider(), deleteButton,
                        Toolbar.divider())
                .right(studyModeButtons())
                .collapsible(backButton, tagsButton, deleteButton)
                .overflowable(viewerTools);
        setTopBar(topBar);

        nameLabel.getStyleClass().add("detail-name");
        nameLabel.setWrapText(true);
        metaGrid.setHgap(16);
        javafx.scene.layout.ColumnConstraints keyColumn = new javafx.scene.layout.ColumnConstraints();
        keyColumn.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.ColumnConstraints valueColumn = new javafx.scene.layout.ColumnConstraints();
        valueColumn.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        valueColumn.setMinWidth(0);
        metaGrid.getColumnConstraints().addAll(keyColumn, valueColumn);
        metaGrid.setVgap(8);
        Label tagsHeading = new Label("Tags");
        tagsHeading.getStyleClass().add("section-title");

        creditField.setPromptText("Photographer, site, or link");
        creditField.setOnAction(e -> saveDetails());
        notesArea.setPromptText("Model names, anything else");
        notesArea.setWrapText(true);
        notesArea.setPrefRowCount(4);
        creditField.focusedProperty().addListener((o, was, now) -> {
            if (!now) saveDetails();
        });
        notesArea.focusedProperty().addListener((o, was, now) -> {
            if (!now) saveDetails();
        });
        VBox details = new VBox(8, fieldLabel("Credit"), creditField, fieldLabel("Notes"), notesArea);
        VBox.setMargin(details.getChildren().get(2), new Insets(8, 0, 0, 0));

        VBox sidebar = new VBox(20, nameLabel, metaGrid, tagsHeading, tagsPane, details);
        VBox.setMargin(tagsHeading, new Insets(8, 0, -8, 0));
        VBox.setMargin(details, new Insets(8, 0, 0, 0));
        sidebar.getStyleClass().add("detail-sidebar");
        sidebar.setPadding(new Insets(28));
        sidebar.setPrefWidth(EditPanel.WIDTH);
        sidebar.setMinWidth(EditPanel.WIDTH);
        sidebar.setMaxWidth(EditPanel.WIDTH);
        tagsPane.setPrefWrapLength(280);
        // Scrolls so Notes stays reachable in a short window or under a long tag list.
        javafx.scene.control.ScrollPane sidebarScroll = new javafx.scene.control.ScrollPane(sidebar);
        sidebarScroll.setFitToWidth(true);
        sidebarScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        sidebarScroll.getStyleClass().add("page-scroll");
        sidebar.minHeightProperty().bind(sidebarScroll.heightProperty());
        tagPicker = new TagPicker(library, true);
        tagPicker.setOnChange(this::applyTags);
        // A rename, merge or delete can change any image's tags, not just this one's.
        tagPicker.enableTagMenu(new TagActions(library, () -> {
            syncEditPanel(true);
            onLibraryChanged.run();
        }));
        playlists = new PlaylistChecklist(library, "In playlists");
        playlists.setOnChange(this::applyPlaylists);
        EditPanel editPanel = new EditPanel(tagPicker, playlists);

        setSidePanel(sidebarScroll);
        editMode.addListener((o, was, on) -> {
            // Leaving the info sidebar commits Credit/Notes, as moving to another image would.
            if (on) {
                saveDetails();
                syncEditPanel(true);
            } else if (context != null) {
                render();
            }
            modeSwitch.setValue(on);
            setSidePanel(on ? editPanel : sidebarScroll);
        });
    }

    @Override
    protected boolean handleAction(Shortcuts.Action action) {
        switch (action) {
            case FAVORITE -> toggleFavorite();
            case TAG -> {
                // The tree is already docked in edit mode: jump into it rather than opening a dialog over it.
                if (editMode.get()) tagPicker.focusTree();
                else editTags();
            }
            case EDIT_MODE -> editMode.set(!editMode.get());
            default -> {
                return false;
            }
        }
        return true;
    }

    public void open(List<ImageRecord> context, int startIndex) {
        // A mutable copy: edits swap in the refreshed record (refreshCurrent), and callers pass List.copyOf.
        this.context = new java.util.ArrayList<>(context);
        this.index = startIndex;
        shownHash = null; // reload fields even if this is the image shown last time
        // Tags and playlists may have been added or renamed elsewhere since the last opening.
        if (editMode.get()) syncEditPanel(true);
        render();
        enter();
    }

    @Override
    protected void beforeClose() {
        saveDetails();
    }

    @Override
    protected void move(int delta) {
        int newIndex = index + delta;
        if (newIndex < 0 || newIndex >= context.size()) return;
        saveDetails();
        index = newIndex;
        render();
        if (editMode.get()) syncEditPanel(false);
    }

    private void render() {
        ImageRecord record = context.get(index);
        // Only reload the picture (and the text fields) when moving to another image, so a tag
        // or favorite edit doesn't flicker it or wipe text still being typed.
        if (!record.hash().equals(shownHash)) {
            shownHash = record.hash();
            creditField.setText(record.credit() == null ? "" : record.credit());
            notesArea.setText(record.notes() == null ? "" : record.notes());
            viewer.setImage(null);
            viewer.fit();
            ImageLoader.load(library.ref().imagePath(record.hash(), record.ext()), 0, 0, image -> {
                if (record.hash().equals(shownHash)) viewer.setImage(image);
            });
        }

        nameLabel.setText(record.originalName());
        metaGrid.getChildren().clear();
        addMeta(0, "Size", record.width() + " × " + record.height() + "  ·  " + humanSize(record.byteSize()));
        addMeta(1, "Imported", formatDate(record.importedAt()));
        addMeta(2, "Last seen", record.lastSeenAt() != null ? formatDate(record.lastSeenAt()) : "Never practiced");
        addMeta(3, "Practiced", record.seenCount() + (record.seenCount() == 1 ? " time" : " times"));
        favoriteButton.setSelected(record.favorite());
        positionLabel.setText((index + 1) + " / " + context.size());
        tagsPane.getChildren().clear();
        for (int tagId : record.tagIds()) {
            library.tags().get(tagId).ifPresent(tag -> tagsPane.getChildren().add(
                    new TagChip(library.tags().fullPath(tag.id()), () -> removeTag(record, tag)).styled(tag)));
        }
        if (tagsPane.getChildren().isEmpty()) {
            Label none = new Label("No tags yet");
            none.getStyleClass().add("hint-label");
            tagsPane.getChildren().add(none);
        }
    }

    private static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    /** Writes Credit/Notes for the current image if they changed. Blank is stored as nothing. */
    private void saveDetails() {
        if (context == null) return;
        ImageRecord record = context.get(index);
        String credit = creditField.getText().strip();
        String notes = notesArea.getText().strip();
        if (credit.equals(record.credit() == null ? "" : record.credit())
                && notes.equals(record.notes() == null ? "" : record.notes())) return;
        library.images().setDetails(record.id(), credit, notes);
        library.images().findById(record.id()).ifPresent(updated -> context.set(index, updated));
        onLibraryChanged.run();
    }

    private void addMeta(int row, String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("meta-key");
        keyLabel.setMinWidth(USE_PREF_SIZE);
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("meta-value");
        valueLabel.setWrapText(true);
        metaGrid.addRow(row, keyLabel, valueLabel);
    }

    private void toggleFavorite() {
        ImageRecord record = context.get(index);
        boolean newValue = !record.favorite();
        library.images().setFavorite(record.id(), newValue);
        context.set(index, record.withFavorite(newValue));
        favoriteButton.setSelected(newValue);
        com.gesturtle.ui.common.Sounds.play(newValue ? com.gesturtle.ui.common.Sounds.Sound.FAVORITE
                : com.gesturtle.ui.common.Sounds.Sound.UNFAVORITE);
        flashHint(newValue ? "Favorited" : "Removed from favorites");
        onLibraryChanged.run();
    }

    /** Opens the tag picker pre-checked with this image's current tags and applies the diff: adds, then removes. */
    private void editTags() {
        ImageRecord record = context.get(index);
        Set<Integer> before = new HashSet<>(record.tagIds());
        TagPicker.pickMany(library, before).ifPresent(after -> {
            Set<Integer> toAdd = new HashSet<>(after);
            toAdd.removeAll(before);
            Set<Integer> toRemove = new HashSet<>(before);
            toRemove.removeAll(after);
            if (!toAdd.isEmpty()) library.images().addTags(record.id(), toAdd);
            for (int tagId : toRemove) library.images().removeTag(record.id(), tagId);
            refreshCurrent();
            onLibraryChanged.run();
        });
    }

    private void removeTag(ImageRecord record, Tag tag) {
        library.images().removeTag(record.id(), tag.id());
        refreshCurrent();
        onLibraryChanged.run();
    }

    /**
     * Ticks the edit panel to match the current image. reloadLists rebuilds
     * the tag tree and playlist list first, to pick up ones made elsewhere;
     * stepping between images skips that, so the tree keeps its scroll and
     * collapsed state. The record is read afresh, since the copy in context
     * dates from when the viewer opened and a tag merge or delete since then
     * may have changed its tags.
     */
    private void syncEditPanel(boolean reloadLists) {
        if (context == null) return;
        library.images().findById(context.get(index).id()).ifPresent(updated -> context.set(index, updated));
        ImageRecord record = context.get(index);
        if (reloadLists) {
            tagPicker.refresh();
            playlists.reload();
        }
        tagPicker.selectTagIds(new HashSet<>(record.tagIds()));
        playlists.setChecked(library.playlists().playlistIdsOf(record.id()));
    }

    /**
     * Writes the difference between the tree and the image's tags. Not
     * re-ticking the tree afterwards: this runs inside a checkbox's listener,
     * and the tree already unchecks a "pick one" sibling itself.
     */
    private void applyTags() {
        ImageRecord record = context.get(index);
        Set<Integer> before = new HashSet<>(record.tagIds());
        Set<Integer> after = tagPicker.getSelectedTagIds();
        Set<Integer> toAdd = new HashSet<>(after);
        toAdd.removeAll(before);
        Set<Integer> toRemove = new HashSet<>(before);
        toRemove.removeAll(after);
        if (toAdd.isEmpty() && toRemove.isEmpty()) return;
        if (!toAdd.isEmpty()) library.images().addTags(record.id(), toAdd);
        for (int tagId : toRemove) library.images().removeTag(record.id(), tagId);
        library.images().findById(record.id()).ifPresent(updated -> context.set(index, updated));
        onLibraryChanged.run();
    }

    private void applyPlaylists() {
        ImageRecord record = context.get(index);
        Set<Integer> before = library.playlists().playlistIdsOf(record.id());
        Set<Integer> after = playlists.checked();
        for (int id : after) {
            if (!before.contains(id)) library.playlists().addItem(id, record.id());
        }
        for (int id : before) {
            if (!after.contains(id)) library.playlists().removeItem(id, record.id());
        }
        onLibraryChanged.run();
    }

    /** record/favorite/tagIds are immutable, so after editing the current image, re-fetch it to re-render accurately. */
    private void refreshCurrent() {
        library.images().findById(context.get(index).id()).ifPresent(updated -> context.set(index, updated));
        render();
    }

    private void delete() {
        if (!Dialogs.confirmDanger("Delete image", "Move this image to the " + com.gesturtle.library.SystemTrash.NAME + "?", "Delete")) return;
        ImageRecord record = context.get(index);
        if (!Dialogs.tryRun(() -> com.gesturtle.library.ImageTrash.delete(library, record), "Could not delete image")) return;
        com.gesturtle.ui.common.Sounds.play(com.gesturtle.ui.common.Sounds.Sound.DELETE);
        onLibraryChanged.run();
        dismiss();
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
