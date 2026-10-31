package com.gesturtle.ui.practice;

import com.gesturtle.config.AppConfig;
import com.gesturtle.config.ConfigStore;
import com.gesturtle.library.LibraryService;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.Segment;
import com.gesturtle.model.SessionSpec;
import com.gesturtle.practice.ResolvedSegment;
import com.gesturtle.practice.SessionBuilder;
import com.gesturtle.practice.SessionEngine;
import com.gesturtle.practice.SessionSnapshot;
import com.gesturtle.practice.SessionSpecCodec;
import com.gesturtle.ui.common.AsyncTask;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.common.ImageLoader;
import com.gesturtle.ui.common.Motion;
import com.gesturtle.ui.common.Shortcuts;
import com.gesturtle.ui.common.Sounds;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.Icon;
import com.gesturtle.ui.components.ImageViewer;
import com.gesturtle.ui.components.Toolbar;
import com.gesturtle.ui.tags.TagChip;
import com.gesturtle.ui.tags.TagPicker;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Consumer;

/**
 * Runs a practice session: image display with zoom/pan/flip/filters/grid, a
 * HUD, timer/tick-behavior UI, inline tag/favorite editing, the memory-
 * drawing show/hide/reveal cycle, and audio cues. The toolbar and HUD can be hidden, leaving the
 * image and an on-image timer.
 *
 * After every image the session's position is saved to the library, so a
 * session cut short by closing the app can be resumed from Home; finishing
 * or exiting clears it.
 */
public class PracticeView extends BorderPane {
    private static final String LAST_SESSION_SPEC_KEY = "last_session_spec";
    private static final int EXTEND_SECONDS = 30;

    private final LibraryService library;
    private final AppConfig config;
    private final ConfigStore configStore;
    private final SessionSpec spec;
    private final SessionEngine engine;
    /** Called with the images shown (first-shown order) when the session ends, however it ends. */
    private final Consumer<List<Integer>> onExit;
    private final Timeline timeline;

    private final ImageViewer viewer = new ImageViewer();

    private final Label hudTime = new Label();
    private final Label hudPosition = new Label();
    private final FlowPane hudTags = new FlowPane(8, 8);
    /** Credit and notes, one line under the tags. */
    private final Label hudDetails = new Label();
    private final CheckBox showTagsBox = new CheckBox("Show info");
    /** A large countdown in the image's corner, readable from a drawing desk; shown only while controls are hidden. */
    private final Label imageTimer = new Label();
    /** Brief messages over the image: how to get the controls back, favorite changes. */
    private final Label controlsHint = new Label();
    private javafx.animation.Animation hintAnimation;
    private final javafx.beans.property.BooleanProperty controlsHidden = new javafx.beans.property.SimpleBooleanProperty(false);
    private Toolbar topBar;
    private HBox hud;
    private final ToggleButton pauseButton = AppButton.withIcon(AppButton.small(AppButton.toggle("Pause", AppButton.Variant.SECONDARY)), "play-pause");
    private final ToggleButton fullScreenButton = AppButton.withIcon(AppButton.toggle(null, AppButton.Variant.GHOST), "fullscreen");
    private final ToggleButton pinButton = AppButton.withIcon(AppButton.toggle(null, AppButton.Variant.GHOST), "pin");
    private final ToggleButton favoriteButton = AppButton.small(AppButton.favoriteToggle(AppButton.Variant.GHOST));
    private final AppButton extendButton = AppButton.ghost("+" + EXTEND_SECONDS + "s", this::extend).small();
    private final Label overlayLabel = new Label();

    private ImageRecord currentRecord;
    /** The last phase update, so a tick sound plays only for a real one-second step, not a pause/resume repaint. */
    private SessionEngine.Phase lastPhase;
    private int lastRemaining;
    /** True while a previous/next the user asked for runs; see imageCue(). */
    private boolean steppingByHand;
    private final javafx.event.EventHandler<KeyEvent> keyFilter = this::handleKey;
    /** Keeps the toolbar toggle in sync however full screen changes (F11, Esc, the window manager). */
    private final ChangeListener<Boolean> fullScreenListener = (o, was, on) -> syncFullScreenButton(on);

    /** A new session: the spec's segments are resolved to images now. */
    public PracticeView(LibraryService library, AppConfig config, ConfigStore configStore, SessionSpec spec,
                        Consumer<List<Integer>> onExit) {
        this(library, config, configStore, spec, new SessionBuilder().resolve(spec, library), 0, onExit);
    }

    /** Resumes an interrupted session at its saved position, with the same images it drew. */
    public PracticeView(LibraryService library, AppConfig config, ConfigStore configStore, SessionSnapshot snapshot,
                        Consumer<List<Integer>> onExit) {
        this(library, config, configStore, snapshot.spec(), snapshot.resolved(), snapshot.cursor(), onExit);
    }

    private PracticeView(LibraryService library, AppConfig config, ConfigStore configStore, SessionSpec spec,
                         List<ResolvedSegment> resolved, int startCursor, Consumer<List<Integer>> onExit) {
        this.library = library;
        this.config = config;
        this.configStore = configStore;
        this.spec = spec;
        this.onExit = onExit;
        getStyleClass().add("practice-view");

        this.engine = new SessionEngine(library, resolved, spec.presetId(), startCursor, AsyncTask::runDb);

        viewer.setOnMouseClicked(e -> {
            if (awaitingManualAdvance()) engine.advanceClick();
        });

        // Status messages (Paused, Break, Memorize…) float over the image as a
        // pill rather than living in the HUD, so they're seen at a glance.
        overlayLabel.getStyleClass().add("stage-pill");
        overlayLabel.setMouseTransparent(true);
        overlayLabel.visibleProperty().bind(overlayLabel.textProperty().isNotEmpty());
        // Pops in when a status appears; updates to a showing pill (countdowns) don't re-animate.
        overlayLabel.textProperty().addListener((o, was, now) -> {
            if ((was == null || was.isEmpty()) && now != null && !now.isEmpty()) Motion.zoomIn(overlayLabel, 0.85, 220);
        });
        imageTimer.getStyleClass().add("image-timer");
        imageTimer.setMouseTransparent(true);
        imageTimer.visibleProperty().bind(imageTimer.textProperty().isNotEmpty().and(controlsHidden));
        controlsHint.getStyleClass().add("stage-pill");
        controlsHint.setMouseTransparent(true);
        controlsHint.setOpacity(0);
        StackPane stage = new StackPane(viewer, overlayLabel, imageTimer, controlsHint);
        stage.setMinSize(0, 0);
        StackPane.setAlignment(overlayLabel, Pos.TOP_CENTER);
        StackPane.setMargin(overlayLabel, new Insets(24));
        StackPane.setAlignment(imageTimer, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(imageTimer, new Insets(24));
        StackPane.setAlignment(controlsHint, Pos.BOTTOM_CENTER);
        StackPane.setMargin(controlsHint, new Insets(24));
        setCenter(stage);
        topBar = buildTopBar();
        hud = buildHud();
        setTop(topBar);
        setBottom(hud);
        controlsHidden.addListener((o, was, hidden) -> {
            setTop(hidden ? null : topBar);
            setBottom(hidden ? null : hud);
            if (hidden) flashControlsHint();
        });

        showTagsBox.setSelected(true);
        showTagsBox.setOnAction(e -> {
            if (currentRecord != null) renderTags(currentRecord);
        });
        pauseButton.setOnAction(e -> togglePause());
        favoriteButton.setOnAction(e -> toggleFavorite());
        fullScreenButton.getStyleClass().add("btn-icon");
        fullScreenButton.setOnAction(e -> setFullScreen(fullScreenButton.isSelected()));
        syncFullScreenButton(false);
        pinButton.getStyleClass().add("btn-icon");
        pinButton.setTooltip(new Tooltip(Shortcuts.hint(Shortcuts.Action.ALWAYS_ON_TOP)));
        pinButton.setSelected(config.practiceAlwaysOnTop);
        pinButton.setOnAction(e -> setAlwaysOnTop(pinButton.isSelected()));

        engine.setListener(new SessionEngine.Listener() {
            @Override
            public void onImageChanged(int imageId, Segment segment, int position, int total) {
                showImage(imageId, segment, position, total);
                saveSnapshot(position);
                Sounds.play(imageCue());
            }

            @Override
            public void onPhaseChanged(SessionEngine.Phase phase, int secondsRemaining) {
                playCue(phase, secondsRemaining);
                updatePhaseUi(phase, secondsRemaining);
            }

            @Override
            public void onSessionFinished() {
                finishSession();
            }
        });

        timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> engine.tick()));
        timeline.setCycleCount(Animation.INDEFINITE);

        setFocusTraversable(true);
        // Shortcuts are a filter on the whole scene while this view is in it, so they work
        // wherever focus sits (toolbar, HUD checkboxes, the nav sidebar). A filter runs before
        // the focused control, which would otherwise take arrows for focus traversal and
        // Space as its own click. Leaving the scene (navigating away, session end) removes it.
        // Full screen and always-on-top belong to the session: leaving the scene also ends
        // them, since the hidden sidebar would otherwise strand the user in the next view.
        sceneProperty().addListener((o, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.removeEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
                if (oldScene.getWindow() instanceof Stage window) {
                    window.fullScreenProperty().removeListener(fullScreenListener);
                    window.setFullScreen(false);
                    window.setAlwaysOnTop(false);
                }
            }
            if (newScene != null) {
                newScene.addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
                if (newScene.getWindow() instanceof Stage window) {
                    window.fullScreenProperty().addListener(fullScreenListener);
                    syncFullScreenButton(window.isFullScreen());
                    window.setAlwaysOnTop(config.practiceAlwaysOnTop);
                }
            }
        });

        if (engine.isEmpty()) {
            Dialogs.error("Nothing to practice", "No images match.");
            clearSnapshot();
            javafx.application.Platform.runLater(() -> onExit.accept(List.of()));
            return;
        }
        recordSessionStart();
        engine.start();
        timeline.play();
    }

    /** Remembers this spec for Home's "Quick start" and, if it came from a preset, bumps its use stats. */
    private void recordSessionStart() {
        String encoded = SessionSpecCodec.encode(spec);
        Integer presetId = spec.presetId();
        AsyncTask.runDb(() -> {
            library.settings().set(LAST_SESSION_SPEC_KEY, encoded);
            if (presetId != null) library.presets().recordUsed(presetId);
        });
    }

    private void saveSnapshot(int position) {
        String json = new SessionSnapshot(spec, engine.resolved(), position, System.currentTimeMillis()).encode();
        AsyncTask.runDb(() -> library.settings().set(SessionSnapshot.SETTING_KEY, json), () -> {
        }, err -> {
        });
    }

    /** Queued behind any snapshot save still on the DB thread, so the last word is "nothing to resume". */
    private void clearSnapshot() {
        AsyncTask.runDb(() -> library.settings().set(SessionSnapshot.SETTING_KEY, ""), () -> {
        }, err -> {
        });
    }

    private Toolbar buildTopBar() {
        AppButton exitButton = AppButton.icon("arrow-left", "Exit the session (Esc)", this::confirmExit);
        AppButton tagButton = AppButton.icon("tag", Shortcuts.hint(Shortcuts.Action.TAG), this::tagCurrent);
        pauseButton.setTooltip(new Tooltip(Shortcuts.hint(Shortcuts.Action.PAUSE)));
        extendButton.setTooltip(new Tooltip(Shortcuts.hint(Shortcuts.Action.EXTEND)));
        AppButton restartButton = AppButton.icon("restart", Shortcuts.hint(Shortcuts.Action.RESTART), engine::restartCurrent);
        List<javafx.scene.Node> viewerTools = viewer.toolButtons();
        return new Toolbar()
                .left(exitButton)
                .center(AppButton.icon("chevron-left", Shortcuts.hint(Shortcuts.Action.PREVIOUS), this::previous),
                        pauseButton,
                        AppButton.icon("chevron-right", Shortcuts.hint(Shortcuts.Action.NEXT), this::advance),
                        Toolbar.divider(),
                        restartButton, extendButton)
                .right(viewerTools.toArray(javafx.scene.Node[]::new))
                .right(Toolbar.divider(),
                        favoriteButton,
                        tagButton,
                        Toolbar.divider(),
                        AppButton.icon("eye-off", Shortcuts.hint(Shortcuts.Action.HIDE_CONTROLS), () -> controlsHidden.set(true)),
                        pinButton,
                        fullScreenButton)
                .collapsible(exitButton, pauseButton, tagButton)
                .overflowable(viewerTools);
    }

    /** Same "next" semantics as the Right-arrow shortcut: dismiss a waiting phase before actually advancing. */
    private void advance() {
        byHand(() -> {
            if (awaitingManualAdvance()) engine.advanceClick();
            else engine.next();
        });
    }

    private void previous() {
        byHand(engine::previous);
    }

    /** Runs a skip the user asked for, so the image it brings up gets the interface-level cue. */
    private void byHand(Runnable step) {
        steppingByHand = true;
        try {
            step.run();
        } finally {
            steppingByHand = false;
        }
    }

    /** The timer moving on is a practice cue, heard across the room; a skip is a button press and sounds like one. */
    private Sounds.Sound imageCue() {
        return steppingByHand ? Sounds.Sound.SKIP : Sounds.Sound.IMAGE;
    }

    private HBox buildHud() {
        hudPosition.getStyleClass().add("hud-meta");
        hudTime.getStyleClass().add("hud-timer");
        Icon timerIcon = new Icon("timer", 20);
        timerIcon.visibleProperty().bind(hudTime.textProperty().isNotEmpty());
        hudTime.setGraphic(timerIcon);
        hudTime.setGraphicTextGap(10);
        HBox.setHgrow(hudTags, Priority.ALWAYS);
        hudTags.setAlignment(Pos.CENTER_LEFT);

        showTagsBox.setMinWidth(HBox.USE_PREF_SIZE);
        hudDetails.getStyleClass().add("hud-meta");
        hudDetails.setMinWidth(0);
        hudDetails.managedProperty().bind(hudDetails.textProperty().isNotEmpty());
        hudDetails.visibleProperty().bind(hudDetails.managedProperty());
        VBox left = new VBox(8, hudPosition, hudTags, hudDetails);
        left.setMinWidth(0);
        HBox.setHgrow(left, Priority.ALWAYS);

        HBox hud = new HBox(24, left, showTagsBox, hudTime);
        hud.setAlignment(Pos.CENTER_LEFT);
        hud.setPadding(new Insets(14, 24, 14, 24));
        hud.getStyleClass().addAll("app-toolbar", "bottom", "hud");
        return hud;
    }

    private void showImage(int imageId, Segment segment, int position, int total) {
        AsyncTask.runDb(
                () -> library.images().findById(imageId).orElse(null),
                record -> {
                    currentRecord = record;
                    if (record == null) return;
                    viewer.setImage(null);
                    ImageLoader.load(library.ref().imagePath(record.hash(), record.ext()), 0, 0, image -> {
                        if (currentRecord == record) viewer.setImage(image);
                    });
                    // Flips, rotation and filters carry over to the next image on purpose: they're a way of practicing.
                    viewer.fit();
                    favoriteButton.setSelected(record.favorite());
                    hudPosition.setText("Image " + (position + 1) + " of " + total);
                    renderTags(record);
                },
                err -> Dialogs.error("Could not load image", err.getMessage()));
    }

    private void renderTags(ImageRecord record) {
        hudTags.getChildren().clear();
        hudDetails.setText("");
        if (!showTagsBox.isSelected()) return;
        String details = java.util.stream.Stream.of(record.credit(), record.notes())
                .filter(java.util.Objects::nonNull)
                .map(text -> text.replaceAll("\\s+", " "))
                .collect(java.util.stream.Collectors.joining("  ·  "));
        hudDetails.setText(details);
        for (int tagId : record.tagIds()) {
            library.tags().get(tagId).ifPresent(tag ->
                    hudTags.getChildren().add(new TagChip(library.tags().fullPath(tag.id())).styled(tag)));
        }
    }

    /**
     * Audio for what just happened: a tick for each of a pose's last three
     * seconds, then a cue when the image changes or a break or the hidden
     * phase begins.
     */
    private void playCue(SessionEngine.Phase phase, int remaining) {
        boolean step = phase == lastPhase && remaining == lastRemaining - 1;
        boolean entered = phase != lastPhase;
        lastPhase = phase;
        lastRemaining = remaining;
        switch (phase) {
            case SHOWING -> {
                if (step && remaining <= 3) Sounds.play(Sounds.Sound.TICK);
            }
            case MEMORY_HIDDEN -> {
                if (entered) Sounds.play(Sounds.Sound.GO);
                else if (step && remaining <= 3) Sounds.play(Sounds.Sound.TICK);
            }
            case MEMORY_REVEALED, WAITING_FOR_CLICK -> {
                if (entered) Sounds.play(imageCue());
            }
            case BREAK -> {
                if (entered) Sounds.play(Sounds.Sound.BREAK);
            }
            default -> {
            }
        }
    }

    private void updatePhaseUi(SessionEngine.Phase phase, int secondsRemaining) {
        switch (phase) {
            case SHOWING -> {
                overlayLabel.setGraphic(null);
                viewer.setImageVisible(true);
                setTimeRemaining(secondsRemaining > 0 ? secondsRemaining : -1);
                // The last few seconds pulse and turn warm, so the end of a pose is felt, not read.
                boolean urgent = secondsRemaining > 0 && secondsRemaining <= 3;
                hudTime.getStyleClass().remove("urgent");
                imageTimer.getStyleClass().remove("urgent");
                if (urgent) {
                    hudTime.getStyleClass().add("urgent");
                    imageTimer.getStyleClass().add("urgent");
                    Motion.pulse(hudTime);
                }
                overlayLabel.setText(engine.isPaused() ? "Paused" : "");
            }
            case MEMORY_HIDDEN -> {
                viewer.setImageVisible(false);
                setTimeRemaining(secondsRemaining);
                overlayLabel.setText("Draw from memory · " + secondsRemaining + "s");
                overlayLabel.setGraphic(new Icon("eye-off", 18));
            }
            case MEMORY_REVEALED -> {
                viewer.setImageVisible(true);
                setTimeRemaining(-1);
                overlayLabel.setText("Compare, then click or press Space");
                overlayLabel.setGraphic(new Icon("eye", 18));
            }
            case BREAK -> {
                overlayLabel.setText("Break · " + secondsRemaining + "s");
                overlayLabel.setGraphic(new Icon("timer", 18));
            }
            case WAITING_FOR_CLICK -> {
                overlayLabel.setText("Click or Space to continue");
                overlayLabel.setGraphic(null);
            }
            case FINISHED -> {
                overlayLabel.setText("Session finished");
                overlayLabel.setGraphic(null);
            }
        }
        if (engine.isPaused() && phase != SessionEngine.Phase.SHOWING) {
            overlayLabel.setText("Paused · " + overlayLabel.getText());
        }
        pauseButton.setSelected(engine.isPaused());
        pauseButton.setText(engine.isPaused() ? "Resume" : "Pause");
        // +30s only means something while a clock is running for this image.
        Segment seg = engine.currentSegment();
        extendButton.setDisable(seg == null || !(phase == SessionEngine.Phase.BREAK || phase == SessionEngine.Phase.MEMORY_HIDDEN
                || (phase == SessionEngine.Phase.SHOWING && (seg.isTimed() || seg.isMemoryMode()))));
    }

    /** Updates the HUD timer and the on-image timer together; negative clears both. */
    private void setTimeRemaining(int seconds) {
        hudTime.setText(seconds < 0 ? "" : seconds + "s");
        imageTimer.setText(seconds < 0 ? "" : seconds >= 60
                ? String.format("%d:%02d", seconds / 60, seconds % 60) : String.valueOf(seconds));
    }

    /** Tells the user once, briefly, how to get the controls back. */
    private void flashControlsHint() {
        String keys = Shortcuts.describe(Shortcuts.Action.HIDE_CONTROLS);
        flashHint("—".equals(keys) ? "Press Esc to show controls" : "Press " + keys.split(" / ")[0] + " to show controls");
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

    private boolean awaitingManualAdvance() {
        return engine.phase() == SessionEngine.Phase.WAITING_FOR_CLICK
                || engine.phase() == SessionEngine.Phase.MEMORY_REVEALED;
    }

    private void handleKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ESCAPE) {
            if (viewer.closePanel()) {
                // closed the adjustments panel
            } else if (controlsHidden.get()) {
                controlsHidden.set(false);
            } else if (isFullScreen()) {
                setFullScreen(false);
            } else {
                confirmExit();
            }
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.F11) {
            setFullScreen(!isFullScreen());
            e.consume();
            return;
        }
        // Typing in the adjustments panel (none today) or a dialog field must not trigger shortcuts.
        if (e.getTarget() instanceof javafx.scene.control.TextInputControl) return;
        Shortcuts.Action action = Shortcuts.match(e, Shortcuts.Scope.PRACTICE);
        if (action == null) return;
        switch (action) {
            case PAUSE -> {
                if (awaitingManualAdvance()) engine.advanceClick();
                else togglePause();
            }
            case PREVIOUS -> previous();
            case NEXT -> advance();
            case EXTEND -> extend();
            case RESTART -> engine.restartCurrent();
            case ALWAYS_ON_TOP -> {
                pinButton.setSelected(!pinButton.isSelected());
                setAlwaysOnTop(pinButton.isSelected());
            }
            case HIDE_CONTROLS -> controlsHidden.set(!controlsHidden.get());
            case FAVORITE -> toggleFavorite();
            case TAG -> tagCurrent();
            default -> {
                if (!viewer.handle(action)) return;
            }
        }
        // Favorite and the adjust panel have sounds of their own; next/previous plays the image chime instead.
        // Tag opens a picker, which has its own sound and only returns once closed, so a tap here would
        // double the click of the button that closed it.
        if (action != Shortcuts.Action.FAVORITE && action != Shortcuts.Action.ADJUST && action != Shortcuts.Action.TAG) {
            Sounds.playUnlessSuperseded(Sounds.Sound.KEY);
        }
        e.consume();
    }

    private void extend() {
        if (engine.extend(EXTEND_SECONDS)) flashHint("+" + EXTEND_SECONDS + " seconds");
    }

    private void setAlwaysOnTop(boolean on) {
        config.practiceAlwaysOnTop = on;
        configStore.save(config);
        if (getScene() != null && getScene().getWindow() instanceof Stage stage) stage.setAlwaysOnTop(on);
        flashHint(on ? "Window stays on top" : "Window no longer on top");
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
        fullScreenButton.setTooltip(new Tooltip(on ? "Leave full screen (F11)" : "Full screen (F11)"));
    }

    private void togglePause() {
        engine.togglePause();
    }

    private void toggleFavorite() {
        if (currentRecord == null) return;
        boolean newValue = !currentRecord.favorite();
        int imageId = currentRecord.id();
        AsyncTask.runDb(() -> library.images().setFavorite(imageId, newValue));
        currentRecord = currentRecord.withFavorite(newValue);
        favoriteButton.setSelected(newValue);
        Sounds.play(newValue ? Sounds.Sound.FAVORITE : Sounds.Sound.UNFAVORITE);
        flashHint(newValue ? "Favorited" : "Removed from favorites");
    }

    private void tagCurrent() {
        if (currentRecord == null) return;
        ImageRecord record = currentRecord;
        java.util.Set<Integer> before = new java.util.HashSet<>(record.tagIds());
        TagPicker.pickMany(library, before).ifPresent(after -> {
            // The picker returns the whole new set, so unticked tags come off as well as ticked ones going on.
            java.util.Set<Integer> toAdd = new java.util.HashSet<>(after);
            toAdd.removeAll(before);
            java.util.Set<Integer> toRemove = new java.util.HashSet<>(before);
            toRemove.removeAll(after);
            AsyncTask.runDb(() -> {
                if (!toAdd.isEmpty()) library.images().addTags(record.id(), toAdd);
                for (int tagId : toRemove) library.images().removeTag(record.id(), tagId);
                return library.images().findById(record.id()).orElse(null);
            }, updated -> {
                if (updated == null || currentRecord == null || currentRecord.id() != updated.id()) return;
                currentRecord = updated;
                renderTags(updated);
            }, err -> Dialogs.error("Could not change tags", err.getMessage()));
        });
    }

    private void confirmExit() {
        if (Dialogs.confirm("Exit session", "End this practice session?", "Exit")) {
            timeline.stop();
            engine.end();
            clearSnapshot();
            onExit.accept(engine.shownImageIds());
        }
    }

    private void finishSession() {
        timeline.stop();
        clearSnapshot();
        Sounds.play(Sounds.Sound.FINISH);
        onExit.accept(engine.shownImageIds());
    }
}
