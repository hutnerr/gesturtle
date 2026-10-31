package com.gesturtle.ui.practice;

import com.gesturtle.ui.common.Motion;
import com.gesturtle.filter.Filter;
import com.gesturtle.filter.FilterSummary;
import com.gesturtle.library.LibraryService;
import com.gesturtle.model.OrderMode;
import com.gesturtle.model.Playlist;
import com.gesturtle.model.Preset;
import com.gesturtle.model.SavedView;
import com.gesturtle.model.Segment;
import com.gesturtle.model.SessionSpec;
import com.gesturtle.model.SourceKind;
import com.gesturtle.model.TickBehavior;
import com.gesturtle.practice.SessionBuilder;
import com.gesturtle.ui.common.AsyncTask;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.Card;
import com.gesturtle.ui.components.FormRow;
import com.gesturtle.ui.components.Icon;
import com.gesturtle.ui.components.NumberField;
import com.gesturtle.ui.components.Page;
import com.gesturtle.ui.components.ResponsiveRow;
import com.gesturtle.ui.components.SegmentedControl;
import com.gesturtle.ui.gallery.FilterBar;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Builds a SessionSpec and hands it off to start a session.
 * A single configured segment is the fast path; "Add this segment
 * to queue" turns it into a multi-segment queue (e.g.
 * "5 female, 5 male, 5 dog, ramping timer") by appending the current
 * form to a list and letting the form be reconfigured for the next segment.
 */
public class SessionSetupView extends Page {
    private final LibraryService library;
    private final FilterBar filterBar;
    private final List<Segment> queuedSegments = new ArrayList<>();
    private final AppButton clearQueueButton = AppButton.ghost("Clear", this::clearQueue).small();
    private final VBox queueListBox = new VBox(8);
    private final Label queueHint = new Label(
            "Start and Save use this queue, not the form. Add the current segment to include it.");
    /**
     * Top-of-page note while anything is queued: the form then only builds
     * segments for the queue, where with an empty queue it is the session.
     * Without it, the form looks like it will run when it won't.
     */
    private final Label queueNoticeText = new Label();
    private final Card queueNotice = new Card().accent();
    private List<Playlist> playlistsCache = List.of();
    private final Label matchCountLabel = new Label();
    /** Bumped per count request, so a slow earlier count can't overwrite a newer one. */
    private int matchCountRequest = 0;

    private final SegmentedControl<SourceKind> sourceChoice = new SegmentedControl<SourceKind>()
            .option(SourceKind.FILTER, "Filter")
            .option(SourceKind.PLAYLIST, "Playlist")
            .option(SourceKind.VIEW, "Saved view");
    private final FormRow filterRow;
    private final FormRow playlistRow;
    private final ChoiceBox<String> playlistChoice = new ChoiceBox<>();
    private final FormRow viewRow;
    private final ChoiceBox<String> viewChoice = new ChoiceBox<>();
    private final Label viewMatchCountLabel = new Label();
    private List<SavedView> viewsCache = List.of();
    private Integer pendingPreselectViewId;

    private final ChoiceBox<String> countChoice = new ChoiceBox<>();
    private final ChoiceBox<String> intervalChoice = new ChoiceBox<>();
    private final FormRow intervalRow;
    private final SegmentedControl<OrderMode> orderChoice = new SegmentedControl<OrderMode>()
            .option(OrderMode.RANDOM, "Random", "shuffle")
            .option(OrderMode.SEQUENTIAL, "Sequential", "list");
    private final SegmentedControl<TickBehavior> tickChoice = new SegmentedControl<TickBehavior>()
            .option(TickBehavior.ADVANCE_IMMEDIATELY, "Advance")
            .option(TickBehavior.BREAK, "Take a break")
            .option(TickBehavior.WAIT_FOR_CLICK, "Wait for click");
    private final TextField breakSecondsField = new NumberField(10);
    private final HBox breakFields;
    private final FormRow tickRow;

    private final CheckBox rampBox = new CheckBox("Ramp to");
    private final TextField rampToField = new NumberField(10);
    private final HBox rampFields;
    private final FormRow rampRow;

    private final SegmentedControl<Boolean> modeChoice = new SegmentedControl<Boolean>()
            .option(false, "Standard", "eye")
            .option(true, "Memory drawing", "eye-off");
    private final TextField memoryShowField = new NumberField(30);
    private final TextField memoryHideField = new NumberField(10);
    private final FormRow memoryRow;

    private Integer pendingPreselectPlaylistId;
    private final Integer editingPresetId;
    private Runnable onReset;

    /** Wider than FormRow.compact() so "When time is up" and "Time per image" stay on one line. */
    private static final double TIMING_LABEL_WIDTH = 165;
    private static final List<String> COUNT_OPTIONS = List.of("Unlimited", "5", "10", "20", "50", "Custom…");
    private static final List<String> INTERVAL_OPTIONS = List.of(
            "Untimed", "10s", "30s", "60s", "2m", "3m", "5m", "10m", "Custom…");

    public SessionSetupView(LibraryService library, Consumer<SessionSpec> onStart) {
        this(library, onStart, null, null, null);
    }

    /** Pre-selects Playlist as the source and that playlist in the dropdown (e.g. "Start practice" from the Playlists page). */
    public SessionSetupView(LibraryService library, Consumer<SessionSpec> onStart, Integer preselectPlaylistId) {
        this(library, onStart, preselectPlaylistId, null, null);
    }

    /** Pre-fills the whole form from an existing preset and swaps "Save as preset…" for "Update preset" (Home page's "Edit" action). */
    public SessionSetupView(LibraryService library, Consumer<SessionSpec> onStart, SessionSpec editingSpec, int editingPresetId) {
        this(library, onStart, null, editingSpec, editingPresetId);
    }

    private SessionSetupView(LibraryService library, Consumer<SessionSpec> onStart, Integer preselectPlaylistId,
                              SessionSpec editingSpec, Integer editingPresetId) {
        super(editingPresetId != null ? "Edit preset" : "Practice");
        this.library = library;
        this.pendingPreselectPlaylistId = preselectPlaylistId;
        this.editingPresetId = editingPresetId;
        getStyleClass().add("session-setup-view");
        setSubtitle("What to draw, and for how long.");

        filterBar = new FilterBar(library, false);
        filterBar.setOpenByDefault(true);
        filterBar.setOnChange(f -> updateMatchCount());
        matchCountLabel.getStyleClass().add("hint-label");

        sourceChoice.setValue(preselectPlaylistId == null ? SourceKind.FILTER : SourceKind.PLAYLIST);
        sourceChoice.onChange(v -> updateSourceVisibility());
        filterRow = new FormRow("Images matching").below(filterBar).below(matchCountLabel);
        playlistChoice.setMinWidth(240);
        playlistRow = new FormRow("Playlist", playlistChoice,
                AppButton.icon("refresh", "Reload playlists", this::loadPlaylists));
        viewChoice.setMinWidth(240);
        viewChoice.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateViewMatchCount());
        viewMatchCountLabel.getStyleClass().add("hint-label");
        viewRow = new FormRow("View", viewChoice, AppButton.icon("refresh", "Reload views", this::loadViews))
                .below(viewMatchCountLabel)
                .hint("Edits to the view in the Gallery apply here.");

        countChoice.getItems().addAll(COUNT_OPTIONS);
        countChoice.getSelectionModel().select("Unlimited");
        intervalChoice.getItems().addAll(INTERVAL_OPTIONS);
        intervalChoice.getSelectionModel().select("Untimed");
        intervalChoice.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateTimingAvailability());
        countChoice.setMinWidth(160);
        promptOnCustom(countChoice, COUNT_OPTIONS, "Custom count", "Number of images", "20", String::valueOf);
        promptOnCustom(intervalChoice, INTERVAL_OPTIONS, "Custom interval", "Seconds per image", "45",
                this::intervalLabelFor);
        intervalChoice.setMinWidth(160);

        tickChoice.onChange(v -> updateTimingAvailability());
        breakFields = new HBox(10, breakSecondsField, unit("sec"));
        breakFields.setAlignment(Pos.CENTER_LEFT);

        rampBox.setOnAction(e -> updateTimingAvailability());
        rampFields = new HBox(10, rampToField, unit("sec/image"));
        rampFields.setAlignment(Pos.CENTER_LEFT);

        modeChoice.onChange(v -> updateTimingAvailability());
        HBox memoryTimes = new HBox(10, unit("Show for"), memoryShowField, unit("sec, then hide for"),
                memoryHideField, unit("sec"));
        memoryTimes.setAlignment(Pos.CENTER_LEFT);

        intervalRow = new FormRow("Time per image", intervalChoice).labelWidth(TIMING_LABEL_WIDTH);
        tickRow = new FormRow("When time is up", tickChoice).labelWidth(TIMING_LABEL_WIDTH).below(breakFields);
        rampRow = new FormRow("Ramp timer", rampBox, rampFields).labelWidth(TIMING_LABEL_WIDTH)
                .hint("Shifts the time per image toward this value.");
        memoryRow = new FormRow("Memory timing", memoryTimes).labelWidth(TIMING_LABEL_WIDTH)
                .hint("Draw from memory while hidden. Click or Space to reveal.");

        Motion.setShown(queueListBox, false);
        Icon queueIcon = new Icon("queue", 18);
        queueNoticeText.setWrapText(true);
        queueNoticeText.getStyleClass().add("body-text");
        HBox noticeRow = new HBox(12, queueIcon, queueNoticeText);
        noticeRow.setAlignment(Pos.CENTER_LEFT);
        queueNotice.add(noticeRow);
        Motion.setShown(queueNotice, !queuedSegments.isEmpty());
        queueHint.getStyleClass().add("hint-label");
        queueHint.setWrapText(true);
        Motion.setShown(queueHint, false);

        AppButton startButton = AppButton.primary("Start practice", () -> start(onStart)).trailingIcon("arrow-right");
        AppButton secondaryButton = editingPresetId != null
                ? AppButton.secondary("Update preset", () -> updatePreset(editingPresetId)).icon("bookmark")
                : AppButton.secondary("Save as preset…", this::saveAsPreset).icon("bookmark");
        AppButton resetButton = AppButton.ghost("Reset", this::reset).icon("rotate-left");
        resetButton.setTooltip(new javafx.scene.control.Tooltip(editingPresetId != null
                ? "Stop editing this preset and start from a blank form"
                : "Put the whole page, queue included, back to its defaults"));
        setActions(resetButton, secondaryButton, startButton);

        Card queueCard = new Card("Segment queue",
                "Optional. Chain segments into one session. While it's empty, the form above is the whole session.")
                .headerActions(clearQueueButton,
                        AppButton.secondary("Add this segment", this::addSegmentToQueue).small().icon("plus"));
        clearQueueButton.setTooltip(new javafx.scene.control.Tooltip("Remove every queued segment"));
        clearQueueButton.setVisible(!queuedSegments.isEmpty());
        clearQueueButton.managedProperty().bind(clearQueueButton.visibleProperty());
        queueCard.add(queueHint, queueListBox);

        // Source gets the full width for its conditions; Images and Timing share a row when they fit.
        Card imagesCard = new Card("Images").add(new FormRow("Image count", countChoice).compact(),
                new FormRow("Order", orderChoice).compact());
        Card timingCard = new Card("Timing").add(new FormRow("Mode", modeChoice).labelWidth(TIMING_LABEL_WIDTH), intervalRow, tickRow, rampRow, memoryRow);
        setScrollingBody(false,
                queueNotice,
                new Card("Source").add(drawFromRow(), filterRow, playlistRow, viewRow),
                new ResponsiveRow(900, imagesCard, ResponsiveRow.weight(timingCard, 1.6)),
                queueCard);

        if (editingSpec != null && !editingSpec.segments().isEmpty()) {
            applyEditingSpec(editingSpec);
        }

        updateSourceVisibility();
        updateTimingAvailability();
        loadPlaylists();
        loadViews();
        updateMatchCount();
    }

    /**
     * The source tabs, plus (except while editing a preset) "Load preset…",
     * which fills in the whole page from a saved preset to adjust or start.
     */
    private FormRow drawFromRow() {
        if (editingPresetId != null) return new FormRow("Draw from", sourceChoice);
        AppButton loadButton = AppButton.ghost("Load preset…", null).small().icon("bookmark");
        loadButton.setOnAction(e -> showPresetMenu(loadButton));
        return new FormRow("Draw from", sourceChoice, loadButton);
    }

    private void showPresetMenu(AppButton anchor) {
        AsyncTask.runDb(() -> library.presets().listAll(), presets -> {
            ContextMenu menu = new ContextMenu();
            for (Preset preset : presets) {
                MenuItem item = new MenuItem(preset.name());
                item.setOnAction(e -> loadPreset(preset));
                menu.getItems().add(item);
            }
            if (presets.isEmpty()) {
                MenuItem none = new MenuItem("No presets yet. Use \"Save as preset…\" to make one.");
                none.setDisable(true);
                menu.getItems().add(none);
            }
            menu.show(anchor, Side.BOTTOM, 0, 4);
        }, err -> Dialogs.error("Could not load presets", err.getMessage()));
    }

    /** Replaces the whole page, queue included, with the preset's segments. */
    private void loadPreset(Preset preset) {
        if (!queuedSegments.isEmpty() && !Dialogs.confirm("Load preset",
                "Replace the " + queuedSegmentsText() + " with \"" + preset.name() + "\"?", "Load")) {
            return;
        }
        AsyncTask.runDb(() -> library.presets().loadSpec(preset.id()), spec -> {
            if (spec.segments().isEmpty()) return;
            queuedSegments.clear();
            // applyEditingSpec only sets what the preset uses; put the rest back to defaults first.
            modeChoice.setValue(false);
            rampBox.setSelected(false);
            applyEditingSpec(spec);
            updateSourceVisibility();
            updateTimingAvailability();
            // Playlist and view picks are applied when their lists load.
            loadPlaylists();
            loadViews();
            updateMatchCount();
        }, err -> Dialogs.error("Could not load preset", err.getMessage()));
    }

    /** What Reset runs; the container swaps in a fresh setup view, which also leaves preset editing. */
    public void setOnReset(Runnable onReset) {
        this.onReset = onReset;
    }

    private void reset() {
        if (onReset == null) return;
        if (!queuedSegments.isEmpty() && !Dialogs.confirm("Reset",
                "Clear the form and the " + queuedSegmentsText() + "?", "Reset")) {
            return;
        }
        onReset.run();
    }

    /** Re-reads the playlist and view lists, keeping what's picked. */
    public void refreshSources() {
        loadPlaylists();
        loadViews();
    }

    private void loadViews() {
        AsyncTask.runDb(
                () -> {
                    // The built-in views come first, as in the Gallery, unless Settings hides them.
                    List<SavedView> all = new ArrayList<>();
                    if (SavedView.builtInsShown(library.settings().get(SavedView.BUILT_INS_SETTING).orElse(null))) {
                        all.addAll(SavedView.BUILT_INS);
                    }
                    all.addAll(library.savedViews().listAll());
                    return all;
                },
                views -> {
                    // By id, so a view renamed in the Gallery stays picked under its new name.
                    Integer previousId = selectedView().map(SavedView::id).orElse(null);
                    Integer wanted = pendingPreselectViewId != null ? pendingPreselectViewId : previousId;
                    pendingPreselectViewId = null;
                    viewsCache = views;
                    viewChoice.getSelectionModel().clearSelection();
                    viewChoice.getItems().setAll(views.stream().map(SavedView::name).toList());
                    Optional<SavedView> match = views.stream().filter(v -> wanted != null && v.id() == wanted).findFirst();
                    if (match.isPresent()) {
                        viewChoice.getSelectionModel().select(match.get().name());
                    } else if (!viewChoice.getItems().isEmpty()) {
                        viewChoice.getSelectionModel().select(0);
                    }
                    updateViewMatchCount();
                },
                err -> Dialogs.error("Could not load views", err.getMessage()));
    }

    private Optional<SavedView> selectedView() {
        String name = viewChoice.getValue();
        return viewsCache.stream().filter(v -> v.name().equals(name)).findFirst();
    }

    private void updateViewMatchCount() {
        Optional<SavedView> view = selectedView();
        if (view.isEmpty()) {
            viewMatchCountLabel.setText(viewsCache.isEmpty()
                    ? "No saved views. Save one in the Gallery." : "");
            return;
        }
        Filter filter = view.get().filter();
        AsyncTask.runDb(
                () -> library.images().count(filter),
                count -> {
                    if (selectedView().map(v -> v.id() == view.get().id()).orElse(false)) {
                        viewMatchCountLabel.setText(count == 0 ? "No images match this view"
                                : count + (count == 1 ? " image matches" : " images match"));
                    }
                },
                err -> viewMatchCountLabel.setText(""));
    }

    private void updateMatchCount() {
        int request = ++matchCountRequest;
        Filter filter = filterBar.buildFilter();
        AsyncTask.runDb(
                () -> library.images().count(filter),
                count -> {
                    if (request != matchCountRequest) return;
                    matchCountLabel.setText(count == 0 ? "No images match"
                            : count + (count == 1 ? " image matches" : " images match"));
                },
                err -> {
                    if (request == matchCountRequest) matchCountLabel.setText("");
                });
    }

    private static Label unit(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted-label");
        label.setMinWidth(Label.USE_PREF_SIZE);
        return label;
    }

    /**
     * Pre-fills the page from a preset. A one-segment preset goes into the
     * form, which is then the session. A longer one goes into the queue whole,
     * since Start and Save use only the queue once it has anything in it; the
     * form shows the last segment's settings as a starting point for more.
     */
    private void applyEditingSpec(SessionSpec editingSpec) {
        List<Segment> segments = editingSpec.segments();
        if (segments.size() > 1) queuedSegments.addAll(segments);
        renderQueueList();

        Segment last = segments.get(segments.size() - 1);
        sourceChoice.setValue(last.sourceKind());
        if (last.sourceKind() == SourceKind.PLAYLIST) {
            pendingPreselectPlaylistId = last.playlistId();
        } else if (last.sourceKind() == SourceKind.VIEW) {
            pendingPreselectViewId = last.viewId();
        } else {
            filterBar.loadFilter(last.filter());
        }

        selectChoice(countChoice, last.itemCount() == null ? "Unlimited" : String.valueOf(last.itemCount()));
        orderChoice.setValue(last.orderMode());

        if (last.isMemoryMode()) {
            modeChoice.setValue(true);
            memoryShowField.setText(String.valueOf(last.memoryShowSeconds()));
            memoryHideField.setText(String.valueOf(last.memoryHideSeconds()));
        } else {
            selectChoice(intervalChoice, intervalLabelFor(last.secondsPerImage()));
            tickChoice.setValue(last.tickBehavior());
            breakSecondsField.setText(String.valueOf(last.breakSeconds()));
            if (last.isRamped()) {
                rampBox.setSelected(true);
                rampToField.setText(String.valueOf(last.rampToSeconds()));
            }
        }
    }

    /** Selects value, adding it as the custom entry first if it isn't one of the presets (e.g. from a saved session). */
    private void selectChoice(ChoiceBox<String> box, String value) {
        if (!box.getItems().contains(value)) setCustomItem(box, value);
        box.getSelectionModel().select(value);
    }

    /**
     * Asks for the number as soon as "Custom…" is picked, rather than when the
     * session starts, and keeps the answer as its own entry so the box shows
     * what will actually be used. Cancelling (or a non-positive number) puts
     * the previous choice back.
     */
    private void promptOnCustom(ChoiceBox<String> box, List<String> presets, String title, String label,
                                String defaultValue, IntFunction<String> labelFor) {
        box.getSelectionModel().selectedItemProperty().addListener((o, previous, picked) -> {
            if (!"Custom…".equals(picked)) return;
            // Later, so the dialog doesn't open while the ChoiceBox popup is still closing.
            Platform.runLater(() -> {
                Integer value = Dialogs.prompt(title, label, defaultValue).map(this::parseIntOrNull).orElse(null);
                if (value != null && value > 0) {
                    String item = labelFor.apply(value);
                    if (!presets.contains(item)) setCustomItem(box, item);
                    box.getSelectionModel().select(item);
                } else {
                    box.getSelectionModel().select(previous);
                }
            });
        });
    }

    /** Replaces any earlier custom entry with item, just above "Custom…". */
    private void setCustomItem(ChoiceBox<String> box, String item) {
        List<String> presets = box == countChoice ? COUNT_OPTIONS : INTERVAL_OPTIONS;
        box.getItems().removeIf(existing -> !presets.contains(existing));
        box.getItems().add(box.getItems().indexOf("Custom…"), item);
    }

    private String intervalLabelFor(int seconds) {
        return switch (seconds) {
            case 0 -> "Untimed";
            case 10 -> "10s";
            case 30 -> "30s";
            case 60 -> "60s";
            case 120 -> "2m";
            case 180 -> "3m";
            case 300 -> "5m";
            case 600 -> "10m";
            default -> seconds % 60 == 0 ? seconds / 60 + "m" : seconds + "s";
        };
    }

    /**
     * Either the queue, or the live form, never both. Once you've queued at
     * least one segment, the queue IS the session being built (you used this
     * same form to build each one); silently tacking the form's current,
     * maybe-half-reconfigured state on as one more segment was the confusing
     * part. With nothing queued, the form itself is the single-segment fast
     * path, unchanged from before.
     */
    private List<Segment> resolveSegments() {
        if (!queuedSegments.isEmpty()) {
            return new ArrayList<>(queuedSegments);
        }
        Segment current = buildSegment();
        return current == null ? List.of() : List.of(current);
    }

    /** Replaces this preset's segments in place instead of creating a new one (Home page's "Edit" action). */
    private void updatePreset(int presetId) {
        List<Segment> segments = resolveSegments();
        if (segments.isEmpty()) {
            Dialogs.error("Nothing to save", "Fill in the segment or queue one.");
            return;
        }
        AsyncTask.runDb(() -> {
            library.presets().update(presetId, new SessionSpec(segments, presetId));
            return null;
        }, ignored -> Dialogs.info("Preset updated", "Saved."),
                err -> Dialogs.error("Could not update preset", err.getMessage()));
    }

    private void updateSourceVisibility() {
        SourceKind source = sourceChoice.getValue();
        filterRow.setShown(source == SourceKind.FILTER);
        playlistRow.setShown(source == SourceKind.PLAYLIST);
        viewRow.setShown(source == SourceKind.VIEW);
    }

    /**
     * Hides (not just disables) whichever timing rows don't apply right now,
     * instead of leaving dead, disabled controls sitting on screen: "When
     * timer ends" and "Ramp timer" are meaningless while Untimed is picked
     * (the default), and the whole interval/tick/ramp cluster is meaningless
     * once Memory drawing mode takes over timing.
     */
    private void updateTimingAvailability() {
        boolean memoryMode = modeChoice.getValue();
        boolean timed = !"Untimed".equals(intervalChoice.getValue());

        memoryRow.setShown(memoryMode);
        intervalRow.setShown(!memoryMode);

        boolean showTickRow = !memoryMode && timed;
        tickRow.setShown(showTickRow);
        boolean breaking = tickChoice.getValue() == TickBehavior.BREAK;
        Motion.setShown(breakFields, breaking);

        boolean showRampRow = !memoryMode && timed;
        rampRow.setShown(showRampRow);

        boolean ramping = rampBox.isSelected() && showRampRow;
        Motion.setShown(rampFields, ramping);
    }

    private void loadPlaylists() {
        AsyncTask.runDb(
                () -> library.playlists().listAll(),
                playlists -> {
                    playlistsCache = playlists;
                    String previous = playlistChoice.getValue();
                    playlistChoice.getItems().setAll(playlists.stream().map(Playlist::name).toList());
                    if (pendingPreselectPlaylistId != null) {
                        int wanted = pendingPreselectPlaylistId;
                        pendingPreselectPlaylistId = null;
                        playlists.stream().filter(p -> p.id() == wanted).findFirst()
                                .ifPresent(p -> playlistChoice.getSelectionModel().select(p.name()));
                    } else if (previous != null && playlistChoice.getItems().contains(previous)) {
                        playlistChoice.getSelectionModel().select(previous);
                    } else if (!playlistChoice.getItems().isEmpty()) {
                        playlistChoice.getSelectionModel().select(0);
                    }
                },
                err -> Dialogs.error("Could not load playlists", err.getMessage()));
    }

    private Integer selectedPlaylistId() {
        String name = playlistChoice.getValue();
        if (name == null) return null;
        return playlistsCache.stream().filter(p -> p.name().equals(name)).map(Playlist::id).findFirst().orElse(null);
    }

    /** Builds the segment from the current form state, or null if a required field is incomplete. */
    private Segment buildSegment() {
        Integer itemCount = parseCount(countChoice.getValue());
        if (itemCount == null && countChoice.getValue().equals("Custom…")) return null;
        OrderMode order = orderChoice.getValue();

        boolean memoryMode = modeChoice.getValue();
        int seconds;
        TickBehavior tickBehavior;
        int breakSeconds;
        Integer rampTo = null;

        if (memoryMode) {
            seconds = 0;
            tickBehavior = TickBehavior.ADVANCE_IMMEDIATELY;
            breakSeconds = 0;
        } else {
            seconds = parseInterval(intervalChoice.getValue());
            if (seconds < 0) return null;
            tickBehavior = tickChoice.getValue();
            breakSeconds = parseIntOr(breakSecondsField.getText(), 10);
            if (rampBox.isSelected() && seconds > 0) {
                rampTo = parseIntOrNull(rampToField.getText());
                if (rampTo == null || rampTo < 0) return null;
            }
        }

        Segment segment;
        if (sourceChoice.getValue() == SourceKind.PLAYLIST) {
            Integer playlistId = selectedPlaylistId();
            if (playlistId == null) return null;
            segment = Segment.playlistSegment(playlistId, itemCount, order, seconds, tickBehavior, breakSeconds);
        } else if (sourceChoice.getValue() == SourceKind.VIEW) {
            Optional<SavedView> view = selectedView();
            if (view.isEmpty()) return null;
            // A built-in view isn't a row segments can point at, so it goes in as its filter (the same images).
            segment = view.get().isBuiltIn()
                    ? Segment.filterSegment(view.get().filter(), itemCount, order, seconds, tickBehavior, breakSeconds)
                    : Segment.viewSegment(view.get().id(), itemCount, order, seconds, tickBehavior, breakSeconds);
        } else {
            Filter filter = filterBar.buildFilter();
            segment = Segment.filterSegment(filter, itemCount, order, seconds, tickBehavior, breakSeconds);
        }
        if (rampTo != null) segment = segment.withRampToSeconds(rampTo);
        if (memoryMode) {
            Integer showSeconds = parseIntOrNull(memoryShowField.getText());
            Integer hideSeconds = parseIntOrNull(memoryHideField.getText());
            if (showSeconds == null || hideSeconds == null || showSeconds <= 0 || hideSeconds <= 0) return null;
            segment = segment.withMemoryMode(showSeconds, hideSeconds);
        }
        return segment;
    }

    private void addSegmentToQueue() {
        Segment segment = buildSegment();
        if (segment == null) {
            Dialogs.error("Incomplete segment", "Fill in the segment first.");
            return;
        }
        queuedSegments.add(segment);
        renderQueueList();
    }

    /** Empties the queue so the form is the whole session again. Asks first, since the segments can't be got back. */
    private void clearQueue() {
        int n = queuedSegments.size();
        if (n == 0) return;
        if (n > 1 && !Dialogs.confirmDanger("Clear queue", "Remove all " + n + " queued segments?", "Clear")) return;
        queuedSegments.clear();
        renderQueueList();
    }

    /** "1 queued segment" / "3 queued segments". */
    private String queuedSegmentsText() {
        int n = queuedSegments.size();
        return n + (n == 1 ? " queued segment" : " queued segments");
    }

    private void renderQueueList() {
        // Eases to the new height when rows are added, removed, or reordered (once the list is showing).
        Motion.animateHeightChange(queueListBox, () -> {
            queueListBox.getChildren().clear();
            for (int i = 0; i < queuedSegments.size(); i++) {
                queueListBox.getChildren().add(buildQueueRow(i));
            }
        });
        boolean hasItems = !queuedSegments.isEmpty();
        int n = queuedSegments.size();
        queueNoticeText.setText("Start and Save use the " + queuedSegmentsText()
                + ". The form below only builds segments now: use \"Add this segment\" to queue it. "
                + "Remove everything from the queue to practice straight from the form again.");
        Motion.setShown(queueNotice, hasItems);
        Motion.setShown(queueListBox, hasItems);
        Motion.setShown(queueHint, hasItems);
        clearQueueButton.setVisible(hasItems);
    }

    private HBox buildQueueRow(int index) {
        Segment segment = queuedSegments.get(index);
        Label number = new Label(String.valueOf(index + 1));
        number.getStyleClass().add("queue-index");
        Label summary = new Label(summarizeSegment(segment));
        summary.setWrapText(true);
        summary.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(summary, Priority.ALWAYS);

        AppButton upButton = AppButton.icon("arrow-up", "Move up", () -> {
            queuedSegments.add(index - 1, queuedSegments.remove(index));
            renderQueueList();
        });
        upButton.setDisable(index == 0);
        AppButton downButton = AppButton.icon("arrow-down", "Move down", () -> {
            queuedSegments.add(index + 1, queuedSegments.remove(index));
            renderQueueList();
        });
        downButton.setDisable(index == queuedSegments.size() - 1);
        AppButton removeButton = AppButton.icon("close", "Remove", () -> {
            queuedSegments.remove(index);
            renderQueueList();
        });

        HBox row = new HBox(12, number, summary, upButton, downButton, removeButton);
        row.getStyleClass().add("filter-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private String summarizeSegment(Segment segment) {
        String source = switch (segment.sourceKind()) {
            case FILTER -> FilterSummary.describe(segment.filter(), id -> library.tags().get(id).map(t -> t.name()).orElse("?"));
            case PLAYLIST -> playlistName(segment.playlistId());
            case VIEW -> viewName(segment.viewId());
        };
        String count = segment.itemCount() == null ? "unlimited" : segment.itemCount() + " images";
        String timing;
        if (segment.isMemoryMode()) {
            timing = "memory " + segment.memoryShowSeconds() + "s/" + segment.memoryHideSeconds() + "s";
        } else if (segment.isRamped()) {
            timing = segment.secondsPerImage() + "s→" + segment.rampToSeconds() + "s/image";
        } else {
            timing = segment.isTimed() ? segment.secondsPerImage() + "s/image" : "untimed";
        }
        return source + " · " + count + " · " + timing;
    }

    private String playlistName(Integer playlistId) {
        if (playlistId == null) return "playlist";
        return playlistsCache.stream().filter(p -> p.id() == playlistId).map(Playlist::name).findFirst()
                .orElse("playlist #" + playlistId);
    }

    private String viewName(Integer viewId) {
        if (viewId == null) return "deleted view";
        return viewsCache.stream().filter(v -> v.id() == viewId).map(v -> "view: " + v.name()).findFirst()
                .orElse("view #" + viewId);
    }

    private void start(Consumer<SessionSpec> onStart) {
        List<Segment> segments = resolveSegments();
        if (segments.isEmpty()) {
            Dialogs.error("Nothing to start", "Fill in the segment or queue one.");
            return;
        }

        AsyncTask.runDb(
                () -> countMatches(segments),
                total -> {
                    if (total == 0) {
                        Dialogs.error("No matching images", "No images match these segments.");
                        return;
                    }
                    onStart.accept(new SessionSpec(segments, null));
                },
                err -> Dialogs.error("Could not start session", err.getMessage()));
    }

    private int countMatches(List<Segment> segments) {
        int total = 0;
        for (Segment seg : segments) {
            if (seg.sourceKind() == SourceKind.PLAYLIST) {
                total += library.playlists().listImageIds(seg.playlistId(), false).size();
            } else {
                Filter filter = SessionBuilder.filterOf(seg, library);
                if (filter != null) total += library.images().count(filter);
            }
        }
        return total;
    }

    /** "Create from the setup screen": saves without starting a session. */
    private void saveAsPreset() {
        List<Segment> segments = resolveSegments();
        if (segments.isEmpty()) {
            Dialogs.error("Nothing to save", "Fill in the segment or queue one.");
            return;
        }
        Dialogs.prompt("Save preset", "Preset name", "My preset").ifPresent(name ->
                AsyncTask.runDb(
                        () -> library.presets().create(name, new SessionSpec(segments, null)),
                        id -> Dialogs.info("Preset saved", "\"" + name + "\" saved to Home."),
                        err -> Dialogs.error("Could not save preset", err.getMessage())));
    }

    private Integer parseCount(String value) {
        if (value.equals("Unlimited")) return null;
        if (value.equals("Custom…")) return null;
        return Integer.parseInt(value);
    }

    private int parseInterval(String value) {
        return switch (value) {
            case "Untimed" -> 0;
            case "10s" -> 10;
            case "30s" -> 30;
            case "60s" -> 60;
            case "2m" -> 120;
            case "3m" -> 180;
            case "5m" -> 300;
            case "10m" -> 600;
            case "Custom…" -> -1;
            // A custom entry, labelled by intervalLabelFor.
            default -> value.endsWith("m")
                    ? Integer.parseInt(value.substring(0, value.length() - 1)) * 60
                    : Integer.parseInt(value.substring(0, value.length() - 1));
        };
    }

    private Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseIntOr(String s, int fallback) {
        Integer v = parseIntOrNull(s);
        return v == null ? fallback : v;
    }
}
