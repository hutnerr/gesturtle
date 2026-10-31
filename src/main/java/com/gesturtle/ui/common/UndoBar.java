package com.gesturtle.ui.common;

import com.gesturtle.ui.components.AppButton;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

/**
 * "Tag deleted · Undo": a bar at the bottom of the window, offered for a
 * few seconds after something that's hard to put back by hand (deleting or
 * merging a tag, taking images out of a playlist, changing many images'
 * tags). Only the latest action can be undone; a newer one replaces it.
 * Ctrl+Z does the same while it shows (see Shell).
 *
 * The undo work comes from UndoCapture and runs on the DB thread; then the
 * view showing is refreshed.
 */
public final class UndoBar extends HBox {
    private static final Duration SHOWN_FOR = Duration.seconds(8);
    private static UndoBar instance;

    private final Label message = new Label();
    private final PauseTransition timer = new PauseTransition(SHOWN_FOR);
    private final Runnable afterUndo;
    private Runnable pendingUndo;

    /** One per window; afterUndo refreshes whatever is showing. */
    public UndoBar(Runnable afterUndo) {
        this.afterUndo = afterUndo;
        getStyleClass().add("undo-bar");
        message.getStyleClass().add("undo-bar-message");
        AppButton undo = AppButton.ghost("Undo", this::undo).icon("restart");
        AppButton dismiss = AppButton.icon("close", "Dismiss", this::hide);
        getChildren().addAll(message, undo, dismiss);
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(12);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        setVisible(false);
        setManaged(false);
        timer.setOnFinished(e -> hide());
        // Hovering keeps it up, so it doesn't vanish under the pointer on the way to Undo.
        hoverProperty().addListener((o, was, now) -> {
            if (now) timer.stop();
            else if (pendingUndo != null) timer.playFromStart();
        });
        instance = this;
    }

    /** Offers undoWork (from UndoCapture.after(); null means nothing changed, so nothing is offered). */
    public static void offer(String text, Runnable undoWork) {
        if (instance == null || undoWork == null) return;
        instance.show(text, undoWork);
    }

    /** Ctrl+Z: undoes the action on offer, if any. Returns whether there was one. */
    public static boolean undoIfOffered() {
        if (instance == null || instance.pendingUndo == null) return false;
        instance.undo();
        return true;
    }

    private void show(String text, Runnable undoWork) {
        pendingUndo = undoWork;
        message.setText(text);
        setManaged(true);
        setVisible(true);
        FadeTransition in = new FadeTransition(Duration.millis(150), this);
        in.setFromValue(getOpacity() < 1 ? getOpacity() : 0);
        in.setToValue(1);
        in.play();
        timer.playFromStart();
    }

    private void hide() {
        pendingUndo = null;
        timer.stop();
        FadeTransition out = new FadeTransition(Duration.millis(200), this);
        out.setToValue(0);
        out.setOnFinished(e -> {
            if (pendingUndo != null) return; // a newer offer arrived while fading
            setVisible(false);
            setManaged(false);
        });
        out.play();
    }

    private void undo() {
        Runnable work = pendingUndo;
        if (work == null) return;
        hide();
        AsyncTask.runDb(work, () -> {
            afterUndo.run();
            Sounds.play(Sounds.Sound.CONFIRM);
        }, err -> {
            Dialogs.error("Could not undo", err.getMessage());
            afterUndo.run();
        });
    }
}
