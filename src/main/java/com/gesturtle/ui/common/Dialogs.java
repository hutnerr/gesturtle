package com.gesturtle.ui.common;

import javafx.scene.Node;
import javafx.animation.FadeTransition;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.Optional;

/**
 * The app's popups. Every dialog goes through prepare(), which gives it an
 * owner window (so tiling window managers float it at its natural size
 * instead of tiling it full-screen, and it stays above the app) and the
 * app's styling. Message dialogs are a heading plus text, with no system
 * graphic, and confirm buttons say what they do.
 *
 * Dialogs have no OS title bar: each is a frameless, transparent window
 * holding a card in the app's own style, centered over the app, which dims
 * behind it while it's open, so a popup reads as part of the app rather
 * than as a separate system window.
 */
public final class Dialogs {
    private static final String ENTER_KEY = "dialogs.acceptOnEnter";
    /** The sound a dialog makes as it appears; OPEN unless build() says otherwise (error, question, info). */
    private static final String SOUND_KEY = "dialogs.sound";
    private static Window mainWindow;

    private Dialogs() {
    }

    /** The app window, used as the owner when no other window of ours has focus. */
    public static void setMainWindow(Window window) {
        mainWindow = window;
    }

    /** Owner + styling + Enter-to-accept for any dialog, including the app's own Dialog subclasses. */
    public static void prepare(Dialog<?> dialog) {
        Window owner = Window.getWindows().stream().filter(Window::isFocused).findFirst().orElse(mainWindow);
        if (owner != null && dialog.getOwner() == null) dialog.initOwner(owner);
        dialog.setGraphic(null);
        dialog.setHeaderText(null);
        Theme.style(dialog.getDialogPane());
        acceptOnEnter(dialog.getDialogPane());
        DialogPane pane = dialog.getDialogPane();
        frameless(dialog);
        if (!pane.getProperties().containsKey(UiSounds.class)) {
            pane.getProperties().put(UiSounds.class, true);
            dialog.addEventHandler(DialogEvent.DIALOG_SHOWING, e -> wrapText(pane.getContent()));
            UiSounds.install(pane);
            dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, e -> Sounds.play(
                    (Sounds.Sound) pane.getProperties().getOrDefault(SOUND_KEY, Sounds.Sound.OPEN)));
        }
    }

    /**
     * Makes every label and checkbox in a dialog's content wrap at its full
     * height. The window is sized from the content's preferred height, which
     * JavaFX works out a line per label, so wrapped text was cut short with
     * "…" unless it can't shrink below its wrapped height.
     */
    private static void wrapText(Node node) {
        if (node instanceof javafx.scene.control.Labeled labeled
                && (!(labeled instanceof Button) && !(labeled instanceof javafx.scene.control.ToggleButton))) {
            labeled.setWrapText(true);
            labeled.setMinHeight(Region.USE_PREF_SIZE);
        }
        if (node instanceof javafx.scene.Parent parent && !(node instanceof javafx.scene.control.Control)) {
            for (Node child : parent.getChildrenUnmodifiable()) wrapText(child);
        }
    }

    /**
     * No OS decorations: a transparent window whose only visible part is the
     * pane, styled as a card (.app-dialog). Centered over the owner once its
     * size is known, with a scrim over the owner while it's open.
     *
     * Where text uses LCD smoothing (Windows), the window is opaque and the
     * card fills it edge to edge, without the rounded corners and shadow
     * (.app-dialog-opaque): JavaFX can't draw LCD text into a transparent
     * window, and its grayscale fallback looks fuzzy on a 100% display.
     */
    private static void frameless(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        if (pane.getStyleClass().contains("app-dialog")) return;
        pane.getStyleClass().add("app-dialog");
        boolean opaque = Theme.usesLcdText();
        if (opaque) pane.getStyleClass().add("app-dialog-opaque");
        try {
            dialog.initStyle(opaque ? StageStyle.UNDECORATED : StageStyle.TRANSPARENT);
        } catch (IllegalStateException alreadyShown) {
            return;
        }
        if (!opaque) {
            if (pane.getScene() != null) pane.getScene().setFill(Color.TRANSPARENT);
            dialog.setOnShowing(e -> {
                if (pane.getScene() != null) pane.getScene().setFill(Color.TRANSPARENT);
            });
        }
        Region[] scrim = new Region[1];
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, e -> {
            Window window = pane.getScene().getWindow();
            Window owner = dialog.getOwner();
            if (owner != null) {
                window.setX(owner.getX() + (owner.getWidth() - window.getWidth()) / 2);
                window.setY(owner.getY() + Math.max(0, (owner.getHeight() - window.getHeight()) / 3));
                scrim[0] = showScrim(owner);
            }
        });
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, e -> {
            if (scrim[0] != null) hideScrim(scrim[0]);
        });
    }

    /** Dims the owner window's content, if it's one of ours with a Pane at its root. */
    private static Region showScrim(Window owner) {
        if (owner.getScene() == null || !(owner.getScene().getRoot() instanceof Pane root)) return null;
        Region scrim = new Region();
        scrim.getStyleClass().add("dialog-scrim");
        scrim.setManaged(false);
        scrim.resizeRelocate(0, 0, root.getWidth(), root.getHeight());
        scrim.setOpacity(0);
        root.getChildren().add(scrim);
        FadeTransition fade = new FadeTransition(Duration.millis(140), scrim);
        fade.setToValue(1);
        fade.play();
        return scrim;
    }

    private static void hideScrim(Region scrim) {
        FadeTransition fade = new FadeTransition(Duration.millis(120), scrim);
        fade.setToValue(0);
        fade.setOnFinished(e -> {
            if (scrim.getParent() instanceof Pane parent) parent.getChildren().remove(scrim);
        });
        fade.play();
    }

    /**
     * Enter presses the dialog's default (OK) button wherever focus is.
     * JavaFX only does that when nothing else uses the key, and a focused
     * tree, list, or checkbox does, so in the tag picker Enter did nothing.
     * A text field with its own Enter action (the picker's "New tag" box)
     * keeps Enter while it has text in it; a text area keeps it for newlines.
     */
    private static void acceptOnEnter(DialogPane pane) {
        if (pane.getProperties().containsKey(ENTER_KEY)) return;
        pane.getProperties().put(ENTER_KEY, true);
        pane.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.ENTER || e.isShiftDown() || e.isControlDown() || e.isAltDown()) return;
            Object target = e.getTarget();
            if (target instanceof TextArea) return;
            if (target instanceof TextField field && field.getOnAction() != null
                    && field.getText() != null && !field.getText().isBlank()) return;
            for (Node n = target instanceof Node node ? node : null; n != null; n = n.getParent()) {
                if (n instanceof ComboBoxBase<?> combo && combo.isShowing()) return;
            }
            for (ButtonType type : pane.getButtonTypes()) {
                if (pane.lookupButton(type) instanceof Button button && button.isDefaultButton() && !button.isDisabled()) {
                    button.fire();
                    e.consume();
                    return;
                }
            }
        });
    }

    public static void error(String title, String message) {
        show(title, message, "dialog-error", Sounds.Sound.ERROR, ButtonType.OK);
    }

    /** Mostly "it worked" news (import complete, preset saved), so it chimes like a confirmation. */
    public static void info(String title, String message) {
        show(title, message, null, Sounds.Sound.CONFIRM, ButtonType.OK);
    }

    /** OK/Cancel, with the OK button named for the action (e.g. "Exit"). */
    public static boolean confirm(String title, String message, String actionLabel) {
        return ask(title, message, actionLabel, "Cancel", false);
    }

    /** confirm() for destructive actions: the action button is red. */
    public static boolean confirmDanger(String title, String message, String actionLabel) {
        return ask(title, message, actionLabel, "Cancel", true);
    }

    /**
     * confirmDanger with a checkbox under the message (e.g. "Also delete its
     * children"), explained by optionTooltip if given. message may be null.
     * Empty if cancelled; otherwise whether the box was ticked.
     */
    public static Optional<Boolean> confirmDangerWithOption(String title, String message, String actionLabel,
                                                            String optionLabel, String optionTooltip) {
        ButtonType yes = new ButtonType(actionLabel, ButtonBar.ButtonData.OK_DONE);
        ButtonType no = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Dialog<ButtonType> dialog = build(title, message, null, Sounds.Sound.QUESTION, no, yes);
        dialog.getDialogPane().lookupButton(yes).getStyleClass().add("dialog-danger");
        javafx.scene.control.CheckBox option = new javafx.scene.control.CheckBox(optionLabel);
        if (optionTooltip != null) option.setTooltip(new javafx.scene.control.Tooltip(optionTooltip));
        ((VBox) dialog.getDialogPane().getContent()).getChildren().add(option);
        return dialog.showAndWait().orElse(no) == yes ? Optional.of(option.isSelected()) : Optional.empty();
    }

    /** A two-way question with both answers named (e.g. "Include" / "Skip"). Returns whether yes was picked. */
    public static boolean ask(String title, String message, String yesLabel, String noLabel) {
        return ask(title, message, yesLabel, noLabel, false);
    }

    private static boolean ask(String title, String message, String yesLabel, String noLabel, boolean danger) {
        ButtonType yes = new ButtonType(yesLabel, ButtonBar.ButtonData.OK_DONE);
        ButtonType no = new ButtonType(noLabel, ButtonBar.ButtonData.CANCEL_CLOSE);
        Dialog<ButtonType> dialog = build(title, message, null, Sounds.Sound.QUESTION, no, yes);
        if (danger) dialog.getDialogPane().lookupButton(yes).getStyleClass().add("dialog-danger");
        return dialog.showAndWait().orElse(no) == yes;
    }

    private static void show(String title, String message, String styleClass, Sounds.Sound sound, ButtonType button) {
        Dialog<ButtonType> dialog = build(title, message, styleClass, sound, button);
        dialog.showAndWait();
    }

    private static Dialog<ButtonType> build(String title, String message, String styleClass, Sounds.Sound sound,
                                            ButtonType... buttons) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        Label heading = new Label(title);
        heading.getStyleClass().add("dialog-title");
        VBox content = new VBox(10, heading);
        // A title can say it all (e.g. Delete "Monks"); then there's no message line.
        if (message != null && !message.isBlank()) {
            Label body = new Label(message);
            body.getStyleClass().add("dialog-message");
            body.setWrapText(true);
            content.getChildren().add(body);
        }
        content.getStyleClass().add("dialog-body");
        content.setPrefWidth(440);
        if (styleClass != null) content.getStyleClass().add(styleClass);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(buttons);
        dialog.getDialogPane().getProperties().put(SOUND_KEY, sound);
        prepare(dialog);
        return dialog;
    }

    /** A name, plus whether the prompt's checkbox was ticked. */
    public record NameAndOption(String name, boolean option) {
    }

    /**
     * A name prompt with a checkbox under it (e.g. Convert to tag's "Keep
     * the playlist"). Empty if cancelled or the name was left blank.
     */
    public static Optional<NameAndOption> promptWithOption(String title, String message, String label,
                                                           String defaultValue, String optionLabel,
                                                           boolean optionDefault, String actionLabel) {
        ButtonType ok = new ButtonType(actionLabel, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Dialog<ButtonType> dialog = build(title, message, null, Sounds.Sound.QUESTION, cancel, ok);
        TextField field = new TextField(defaultValue == null ? "" : defaultValue);
        Label fieldLabel = new Label(label);
        fieldLabel.getStyleClass().add("form-label");
        javafx.scene.control.CheckBox option = new javafx.scene.control.CheckBox(optionLabel);
        option.setSelected(optionDefault);
        ((VBox) dialog.getDialogPane().getContent()).getChildren().addAll(fieldLabel, field, option);
        Node okButton = dialog.getDialogPane().lookupButton(ok);
        okButton.disableProperty().bind(field.textProperty().map(t -> t.isBlank()));
        javafx.application.Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
        if (dialog.showAndWait().orElse(cancel) != ok) return Optional.empty();
        return Optional.of(new NameAndOption(field.getText().trim(), option.isSelected()));
    }

    public static Optional<String> prompt(String title, String label, String defaultValue) {
        TextInputDialog dialog = new TextInputDialog(defaultValue == null ? "" : defaultValue);
        dialog.setTitle(title);
        dialog.setContentText(label);
        prepare(dialog);
        return dialog.showAndWait().map(String::trim).filter(s -> !s.isEmpty());
    }

    /**
     * Runs a synchronous, file-system-touching action (e.g. a recycle-bin
     * move) directly on the FX thread, showing an error dialog instead of
     * crashing the UI thread if it throws. Returns whether it succeeded, so
     * callers can skip their own follow-up (e.g. a refresh) on failure.
     */
    public static boolean tryRun(Runnable action, String errorTitle) {
        try {
            action.run();
            return true;
        } catch (RuntimeException e) {
            error(errorTitle, e.getMessage());
            return false;
        }
    }
}
