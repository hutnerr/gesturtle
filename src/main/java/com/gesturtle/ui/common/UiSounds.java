package com.gesturtle.ui.common;

import com.gesturtle.ui.common.Sounds.Sound;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;
import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.stage.Window;

/**
 * Interface sound effects for every control at once, rather than one call
 * per button: an ActionEvent filter on the scene (and on each dialog, via
 * Dialogs.prepare) picks a sound from what fired it, and every popup menu
 * (context menus, choice boxes, "More") gets the same treatment for its
 * items. Only user actions fire ActionEvents, so programmatic changes (a
 * filter loading, a binding flipping a toggle) stay silent.
 *
 * Filters, not handlers, so the sound is picked before the control's own
 * action runs; and played through Sounds.playUnlessSuperseded, so an action
 * that makes its own sound (or opens a dialog, which does) replaces the
 * generic click instead of doubling it.
 *
 * A control styled "quiet" is skipped, for places that play a more specific
 * sound themselves (favorite stars, the adjust panel's open/close).
 */
public final class UiSounds {
    public static final String QUIET = "quiet";
    private static boolean menusHooked;

    private UiSounds() {
    }

    /** Hooks a scene's controls, and (once) every popup menu the app shows. */
    public static void install(javafx.scene.Scene scene) {
        scene.addEventFilter(ActionEvent.ACTION, e -> play(e.getTarget()));
        if (menusHooked) return;
        menusHooked = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) {
                    if (w instanceof ContextMenu menu && !menu.getProperties().containsKey(UiSounds.class)) {
                        menu.getProperties().put(UiSounds.class, true);
                        menu.addEventFilter(ActionEvent.ACTION, e -> playMenu(e.getTarget()));
                    }
                }
            }
        });
    }

    /** For a dialog's own controls (its buttons, fields), which live outside the main scene. */
    public static void install(Node root) {
        root.addEventFilter(ActionEvent.ACTION, e -> play(e.getTarget()));
    }

    private static void play(EventTarget target) {
        Sound sound = soundFor(target);
        if (sound != null) Sounds.playUnlessSuperseded(sound);
    }

    /** The sound a control's action makes, or null for none. */
    static Sound soundFor(EventTarget target) {
        if (!(target instanceof Node node) || target instanceof TextInputControl) return null;
        if (node.getStyleClass().contains(QUIET)) return null;
        return switch (node) {
            case ToggleButton t when node.getStyleClass().contains("nav-item") -> Sound.NAV;
            case ToggleButton t when node.getStyleClass().contains("segment") -> Sound.SWITCH;
            case ToggleButton t -> t.isSelected() ? Sound.TOGGLE_ON : Sound.TOGGLE_OFF;
            case CheckBox c -> c.isSelected() ? Sound.TOGGLE_ON : Sound.TOGGLE_OFF;
            case ButtonBase b when node.getStyleClass().contains("nav-item") -> Sound.NAV;
            case ButtonBase b -> Sound.CLICK;
            default -> null;
        };
    }

    /** Menu items: picking one of several (sort order, tag color) switches, a checkable item toggles, the rest click. */
    private static void playMenu(EventTarget target) {
        if (target instanceof javafx.scene.control.MenuItem item && item.getStyleClass().contains(QUIET)) return;
        Sound sound = switch (target) {
            case RadioMenuItem r -> Sound.SWITCH;
            case CheckMenuItem c -> c.isSelected() ? Sound.TOGGLE_ON : Sound.TOGGLE_OFF;
            default -> Sound.CLICK;
        };
        Sounds.playUnlessSuperseded(sound);
    }
}
