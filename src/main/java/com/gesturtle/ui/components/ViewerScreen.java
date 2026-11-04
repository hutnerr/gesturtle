package com.gesturtle.ui.components;

import com.gesturtle.ui.common.Motion;
import com.gesturtle.ui.common.Shortcuts;
import com.gesturtle.ui.common.Sounds;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.SequentialTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * A full-page image viewer that sits over the page it was opened from (a
 * hidden node in the same StackPane, not a dialog), so the nav sidebar
 * stays put and Esc goes back. The Gallery's detail view and the Import
 * page's preview both build on it, which keeps them behaving the same: the
 * same ImageViewer, the same Esc order, and the same study mode, where full
 * screen (F11) and hiding the toolbar and side panel (Z) leave just the
 * image. Subclasses supply the toolbar, the side panel, and what stepping
 * and their own shortcuts do to the items they show.
 */
public abstract class ViewerScreen extends BorderPane {
    protected final ImageViewer viewer = new ImageViewer();
    private final Runnable onClose;
    /** Brief messages over the image: how to get the controls back, favorite changes. */
    private final Label hint = new Label();
    private Animation hintAnimation;
    private final BooleanProperty controlsHidden = new SimpleBooleanProperty(false);
    private final ToggleButton fullScreenButton = AppButton.withIcon(AppButton.toggle(null, AppButton.Variant.GHOST), "fullscreen");
    private Node topBar;
    private Node sidePanel;
    /** Keeps the toolbar toggle in sync however full screen changes (F11, Esc, the window manager). */
    private final ChangeListener<Boolean> fullScreenListener = (o, was, on) -> syncFullScreenButton(on);
    private final EventHandler<KeyEvent> keyFilter = this::handleKey;

    protected ViewerScreen(Runnable onClose) {
        this.onClose = onClose;
        getStyleClass().add("image-detail-view");

        hint.getStyleClass().add("stage-pill");
        hint.setMouseTransparent(true);
        hint.setOpacity(0);
        StackPane stage = new StackPane(viewer, hint);
        stage.setMinSize(0, 0);
        StackPane.setAlignment(hint, Pos.BOTTOM_CENTER);
        StackPane.setMargin(hint, new Insets(24));
        setCenter(stage);

        fullScreenButton.getStyleClass().add("btn-icon");
        fullScreenButton.setOnAction(e -> setFullScreen(fullScreenButton.isSelected()));
        syncFullScreenButton(false);
        controlsHidden.addListener((o, was, hidden) -> {
            layoutChrome();
            if (hidden) flashHint("Press " + Shortcuts.describe(Shortcuts.Action.HIDE_CONTROLS) + " to show controls");
        });

        // Full screen belongs to the viewer: leaving the scene (navigating to another view)
        // also leaves it, since the hidden side panel would otherwise strand the user.
        // Shortcuts are a filter on the whole scene, not this node: hiding the controls removes
        // the focused toolbar button, and focus can land outside the viewer (the nav sidebar),
        // where a node filter would never see Z again. handleKey ignores keys while hidden.
        sceneProperty().addListener((o, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.removeEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
                if (oldScene.getWindow() instanceof Stage window) {
                    window.fullScreenProperty().removeListener(fullScreenListener);
                    window.setFullScreen(false);
                }
            }
            if (newScene != null) {
                newScene.addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
                if (newScene.getWindow() instanceof Stage window) {
                    window.fullScreenProperty().addListener(fullScreenListener);
                    syncFullScreenButton(window.isFullScreen());
                }
            }
        });

        setFocusTraversable(true);
    }

    /** Steps delta items through whatever is being shown, if there's one there. */
    protected abstract void move(int delta);

    /**
     * A VIEWER-scope shortcut other than stepping and hiding the controls.
     * Returns whether it was handled; unhandled ones go to the ImageViewer
     * (zoom, flip, grid, and so on).
     */
    protected boolean handleAction(Shortcuts.Action action) {
        return false;
    }

    /** Runs before closing, for anything to save on the way out. */
    protected void beforeClose() {
    }

    protected void setTopBar(Node topBar) {
        this.topBar = topBar;
        layoutChrome();
    }

    /** The panel on the right; swapping it (the detail view's View/Edit modes) keeps the image where it is. */
    protected void setSidePanel(Node sidePanel) {
        this.sidePanel = sidePanel;
        layoutChrome();
    }

    /** The hide-controls and full-screen buttons, for the end of a toolbar. */
    protected Node[] studyModeButtons() {
        return new Node[] {
                AppButton.icon("eye-off", Shortcuts.hint(Shortcuts.Action.HIDE_CONTROLS), () -> controlsHidden.set(true)),
                fullScreenButton};
    }

    private void layoutChrome() {
        boolean hidden = controlsHidden.get();
        setTop(hidden ? null : topBar);
        setRight(hidden ? null : sidePanel);
        viewer.setInset(hidden ? 0 : 24);
    }

    /** A filter so the shortcuts beat a focused toolbar button (which would take arrows for focus traversal). */
    private void handleKey(KeyEvent e) {
        if (!isVisible()) return;
        // Typing in a text field (Credit, Notes, a new tag's name) must not trigger shortcuts; Esc just leaves it.
        if (e.getTarget() instanceof TextInputControl) {
            if (e.getCode() == KeyCode.ESCAPE) {
                requestFocus();
                e.consume();
            }
            return;
        }
        if (e.getCode() == KeyCode.ESCAPE) {
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
        if (e.getCode() == KeyCode.F11) {
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
            default -> {
                if (!handleAction(action) && !viewer.handle(action)) return;
            }
        }
        // FAVORITE and TAG make their own sounds (TAG's picker dialog only returns once it's closed: a tap
        // played here would land on top of the click of whatever button closed it), and so does ADJUST.
        if (action != Shortcuts.Action.FAVORITE && action != Shortcuts.Action.ADJUST && action != Shortcuts.Action.TAG) {
            Sounds.playUnlessSuperseded(Sounds.Sound.KEY);
        }
        e.consume();
    }

    /** For the subclass's open(): call once the first item is rendered and this node is visible. */
    protected void enter() {
        Motion.zoomIn(this, 0.97, 200);
        Sounds.play(Sounds.Sound.OPEN);
        requestFocus();
    }

    /** Shrinks and fades out, then lets the owner hide it. Full screen and hidden controls end with the viewer. */
    protected void close() {
        beforeClose();
        leaveStudyMode();
        Sounds.play(Sounds.Sound.CLOSE);
        Motion.zoomOut(this, 0.97, 150, onClose);
    }

    /** Hides straight away, without the closing animation (after deleting the image shown, say). */
    protected void dismiss() {
        leaveStudyMode();
        onClose.run();
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
        fullScreenButton.setTooltip(new Tooltip(on ? "Leave full screen (F11)" : "Full screen (F11)"));
    }

    /** Shows a short message over the image, then fades it; a newer message replaces one still showing. */
    protected void flashHint(String text) {
        if (hintAnimation != null) hintAnimation.stop();
        hint.setText(text);
        FadeTransition in = new FadeTransition(Duration.millis(200), hint);
        in.setToValue(1);
        FadeTransition out = new FadeTransition(Duration.millis(600), hint);
        out.setDelay(Duration.seconds(2));
        out.setToValue(0);
        hintAnimation = new SequentialTransition(in, out);
        hintAnimation.play();
    }
}
