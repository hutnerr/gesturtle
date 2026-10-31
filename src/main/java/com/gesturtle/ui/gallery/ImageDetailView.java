package com.gesturtle.ui.gallery;

import com.gesturtle.library.LibraryService;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.Tag;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.common.ImageLoader;
import com.gesturtle.ui.common.Motion;
import com.gesturtle.ui.common.Shortcuts;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.ImageViewer;
import com.gesturtle.ui.components.Toolbar;
import com.gesturtle.ui.tags.TagChip;
import com.gesturtle.ui.tags.TagPicker;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Full-size image view with metadata, favorite/tag editing, and arrow-key
 * navigation. The image itself is the same ImageViewer practice
 * uses: zoom, pan, flip, grayscale, fit. Flip and grayscale carry over when
 * moving between images; zoom resets. Credit and notes save when their field
 * loses focus or the view moves to another image. Like practice, it doubles
 * as a free-study mode: full screen (F11) and hiding the toolbar and sidebar
 * (Z) leave just the image.
 */
public class ImageDetailView extends BorderPane {
    private final LibraryService library;
    private final Runnable onClose;
    private final Runnable onLibraryChanged;

    private List<ImageRecord> context;
    private int index;
    private String shownHash;

    private final ImageViewer viewer = new ImageViewer();
    private final Label nameLabel = new Label();
    private final GridPane metaGrid = new GridPane();
    private final ToggleButton favoriteButton = AppButton.small(AppButton.favoriteToggle(AppButton.Variant.GHOST));
    private final FlowPane tagsPane = new FlowPane(8, 8);
    private final Label positionLabel = new Label();
    private final TextField creditField = new TextField();
    private final TextArea notesArea = new TextArea();
    private final ToggleButton fullScreenButton = AppButton.withIcon(AppButton.toggle(null, AppButton.Variant.GHOST), "fullscreen");
    /** Brief messages over the image: how to get the controls back, favorite changes. */
    private final Label controlsHint = new Label();
    private javafx.animation.Animation hintAnimation;
    private final BooleanProperty controlsHidden = new SimpleBooleanProperty(false);
    /** Keeps the toolbar toggle in sync however full screen changes (F11, Esc, the window manager). */
    private final ChangeListener<Boolean> fullScreenListener = (o, was, on) -> syncFullScreenButton(on);
    private final javafx.event.EventHandler<javafx.scene.input.KeyEvent> keyFilter = this::handleKey;

    public ImageDetailView(LibraryService library, Runnable onClose, Runnable onLibraryChanged) {
        this.library = library;
        this.onClose = onClose;
        this.onLibraryChanged = onLibraryChanged;
        getStyleClass().add("image-detail-view");

        viewer.setInset(24);
        controlsHint.getStyleClass().add("stage-pill");
        controlsHint.setMouseTransparent(true);
        controlsHint.setOpacity(0);
        StackPane stage = new StackPane(viewer, controlsHint);
        stage.setMinSize(0, 0);
        StackPane.setAlignment(controlsHint, Pos.BOTTOM_CENTER);
        StackPane.setMargin(controlsHint, new Insets(24));
        setCenter(stage);

        favoriteButton.setOnAction(e -> toggleFavorite());
        positionLabel.getStyleClass().add("muted-label");
        List<javafx.scene.Node> viewerTools = viewer.toolButtons();
        AppButton backButton = AppButton.icon("arrow-left", "Back to the grid (Esc)", this::close);
        AppButton tagsButton = AppButton.icon("tag", Shortcuts.hint(Shortcuts.Action.TAG), this::editTags);
        AppButton deleteButton = AppButton.icon("trash", "Move to the " + com.gesturtle.library.SystemTrash.NAME, this::delete);
        deleteButton.getStyleClass().add("btn-ghost-danger");
        Toolbar topBar = new Toolbar()
                .left(backButton)
                .center(AppButton.icon("chevron-left", Shortcuts.hint(Shortcuts.Action.PREVIOUS), () -> move(-1)), positionLabel,
                        AppButton.icon("chevron-right", Shortcuts.hint(Shortcuts.Action.NEXT), () -> move(1)))
                .right(viewerTools.toArray(javafx.scene.Node[]::new))
                .right(Toolbar.divider(), favoriteButton, tagsButton,
                        Toolbar.divider(), deleteButton,
                        Toolbar.divider(),
                        AppButton.icon("eye-off", Shortcuts.hint(Shortcuts.Action.HIDE_CONTROLS), () -> controlsHidden.set(true)),
                        fullScreenButton)
                .collapsible(backButton, tagsButton, deleteButton)
                .overflowable(viewerTools);
        setTop(topBar);
        fullScreenButton.getStyleClass().add("btn-icon");
        fullScreenButton.setOnAction(e -> setFullScreen(fullScreenButton.isSelected()));
        syncFullScreenButton(false);

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
        sidebar.setPrefWidth(340);
        sidebar.setMinWidth(340);
        sidebar.setMaxWidth(340);
        tagsPane.setPrefWrapLength(280);
        // Scrolls so Notes stays reachable in a short window or under a long tag list.
        javafx.scene.control.ScrollPane sidebarScroll = new javafx.scene.control.ScrollPane(sidebar);
        sidebarScroll.setFitToWidth(true);
        sidebarScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        sidebarScroll.getStyleClass().add("page-scroll");
        sidebar.minHeightProperty().bind(sidebarScroll.heightProperty());
        setRight(sidebarScroll);
        controlsHidden.addListener((o, was, hidden) -> {
            setTop(hidden ? null : topBar);
            setRight(hidden ? null : sidebarScroll);
            viewer.setInset(hidden ? 0 : 24);
            if (hidden) flashControlsHint();
        });

        // Full screen belongs to the viewer: leaving the scene (navigating to another view)
        // also leaves it, since the hidden sidebar would otherwise strand the user.
        // Shortcuts are a filter on the whole scene, not this node: hiding the controls removes
        // the focused toolbar button, and focus can land outside the viewer (the nav sidebar),
        // where a node filter would never see Z again. handleKey ignores keys while hidden.
        sceneProperty().addListener((o, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, keyFilter);
                if (oldScene.getWindow() instanceof Stage window) {
                    window.fullScreenProperty().removeListener(fullScreenListener);
                    window.setFullScreen(false);
                }
            }
            if (newScene != null) {
                newScene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, keyFilter);
                if (newScene.getWindow() instanceof Stage window) {
                    window.fullScreenProperty().addListener(fullScreenListener);
                    syncFullScreenButton(window.isFullScreen());
                }
            }
        });

        setFocusTraversable(true);
    }

    /** A filter so the shortcuts beat a focused toolbar button (which would take arrows for focus traversal). */
    private void handleKey(javafx.scene.input.KeyEvent e) {
        if (!isVisible()) return;
        // Typing in Credit/Notes must not trigger shortcuts; Esc just leaves the field.
        if (e.getTarget() instanceof TextInputControl) {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                requestFocus();
                e.consume();
            }
            return;
        }
        if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
            if (viewer.closePanel()) {
                // closed the adjustments panel
            } else if (controlsHidden.get()) {
                controlsHidden.set(false);
            } else if (isFullScreen()) {
                setFullScreen(false);
            } else {
                close();
            }
            e.consume();
            return;
        }
        if (e.getCode() == javafx.scene.input.KeyCode.F11) {
            setFullScreen(!isFullScreen());
            e.consume();
            return;
        }
        Shortcuts.Action action = Shortcuts.match(e, Shortcuts.Scope.VIEWER);
        if (action == null) return;
        switch (action) {
            case PREVIOUS -> move(-1);
            case NEXT -> move(1);
            case HIDE_CONTROLS -> controlsHidden.set(!controlsHidden.get());
            case FAVORITE -> toggleFavorite();
            case TAG -> editTags();
            default -> {
                if (!viewer.handle(action)) return;
            }
        }
        // TAG's picker opens with its own sound, and editTags only returns once it's closed: a tap
        // played here would land on top of the click of whatever button closed it.
        if (action != Shortcuts.Action.FAVORITE && action != Shortcuts.Action.ADJUST && action != Shortcuts.Action.TAG) {
            com.gesturtle.ui.common.Sounds.playUnlessSuperseded(com.gesturtle.ui.common.Sounds.Sound.KEY);
        }
        e.consume();
    }

    public void open(List<ImageRecord> context, int startIndex) {
        // A mutable copy: edits swap in the refreshed record (refreshCurrent), and callers pass List.copyOf.
        this.context = new java.util.ArrayList<>(context);
        this.index = startIndex;
        shownHash = null; // reload fields even if this is the image shown last time
        render();
        Motion.zoomIn(this, 0.97, 200);
        com.gesturtle.ui.common.Sounds.play(com.gesturtle.ui.common.Sounds.Sound.OPEN);
        requestFocus();
    }

    /** Shrinks and fades out, then lets the owner hide it. Full screen and hidden controls end with the viewer. */
    private void close() {
        saveDetails();
        leaveStudyMode();
        com.gesturtle.ui.common.Sounds.play(com.gesturtle.ui.common.Sounds.Sound.CLOSE);
        Motion.zoomOut(this, 0.97, 150, onClose);
    }

    private void leaveStudyMode() {
        setFullScreen(false);
        controlsHidden.set(false);
    }

    private boolean isFullScreen() {
        return getScene() != null && getScene().getWindow() instanceof Stage stage && stage.isFullScreen();
    }

    private void setFullScreen(boolean on) {
        if (getScene() != null && getScene().getWindow() instanceof Stage stage) stage.setFullScreen(on);
    }

    private void syncFullScreenButton(boolean on) {
        fullScreenButton.setSelected(on);
        AppButton.withIcon(fullScreenButton, on ? "fullscreen-exit" : "fullscreen");
        fullScreenButton.setTooltip(new javafx.scene.control.Tooltip(on ? "Leave full screen (F11)" : "Full screen (F11)"));
    }

    /** Tells the user once, briefly, how to get the controls back. */
    private void flashControlsHint() {
        flashHint("Press Z to show controls");
    }

    /** Shows a short message over the image, then fades it; a newer message replaces one still showing. */
    private void flashHint(String text) {
        if (hintAnimation != null) hintAnimation.stop();
        controlsHint.setText(text);
        javafx.animation.FadeTransition in = new javafx.animation.FadeTransition(Duration.millis(200), controlsHint);
        in.setToValue(1);
        javafx.animation.FadeTransition out = new javafx.animation.FadeTransition(Duration.millis(600), controlsHint);
        out.setDelay(Duration.seconds(2));
        out.setToValue(0);
        hintAnimation = new javafx.animation.SequentialTransition(in, out);
        hintAnimation.play();
    }

    private void move(int delta) {
        int newIndex = index + delta;
        if (newIndex < 0 || newIndex >= context.size()) return;
        saveDetails();
        index = newIndex;
        render();
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
        leaveStudyMode();
        onClose.run();
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
