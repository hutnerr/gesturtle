package com.gesturtle.ui.common;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rebindable keyboard shortcuts for practice and the image viewers, in
 * one table, so the key handlers, Settings' editor, and Help all read the
 * same bindings. Each action has default keys; the user can rebind it to a
 * single key (saved in AppConfig.shortcuts by action name). Esc and F11 are
 * fixed, so nobody can lock themselves into full screen.
 *
 * Shortcuts never fire with Ctrl, Alt, or Meta held, which leaves Ctrl+V
 * and friends to the rest of the app. Shift is ignored, since "+" is
 * Shift+= on most keyboards.
 */
public final class Shortcuts {
    public enum Scope { PRACTICE, VIEWER, BOTH }

    public enum Action {
        PAUSE("Pause / resume", Scope.PRACTICE, KeyCode.SPACE),
        PREVIOUS("Previous image", Scope.BOTH, KeyCode.LEFT),
        NEXT("Next image", Scope.BOTH, KeyCode.RIGHT),
        EXTEND("Add 30 seconds", Scope.PRACTICE, KeyCode.E),
        RESTART("Restart the timer", Scope.PRACTICE, KeyCode.BACK_SPACE),
        ALWAYS_ON_TOP("Keep window on top", Scope.PRACTICE, KeyCode.P),
        FAVORITE("Favorite", Scope.BOTH, KeyCode.F),
        TAG("Tag", Scope.BOTH, KeyCode.T),
        HIDE_CONTROLS("Hide controls", Scope.BOTH, KeyCode.Z),
        GRAYSCALE("Grayscale", Scope.BOTH, KeyCode.G),
        BLUR("Blur", Scope.BOTH, KeyCode.B),
        VALUES("Value study (2 → 3 → 4 tones → off)", Scope.BOTH, KeyCode.L),
        INVERT("Invert colors", Scope.BOTH, KeyCode.I),
        ADJUST("Brightness & contrast panel", Scope.BOTH, KeyCode.A),
        GRID("Grid", Scope.BOTH, KeyCode.R),
        FLIP_H("Flip horizontally", Scope.BOTH, KeyCode.H),
        FLIP_V("Flip vertically", Scope.BOTH, KeyCode.V),
        ROTATE_LEFT("Rotate left 30°", Scope.BOTH, KeyCode.OPEN_BRACKET),
        ROTATE_RIGHT("Rotate right 30°", Scope.BOTH, KeyCode.CLOSE_BRACKET),
        ZOOM_IN("Zoom in", Scope.BOTH, KeyCode.PLUS, KeyCode.ADD, KeyCode.EQUALS),
        ZOOM_OUT("Zoom out", Scope.BOTH, KeyCode.MINUS, KeyCode.SUBTRACT),
        FIT("Fit to window", Scope.BOTH, KeyCode.DIGIT0, KeyCode.NUMPAD0),
        ACTUAL_SIZE("Actual size (100%)", Scope.BOTH, KeyCode.DIGIT1, KeyCode.NUMPAD1);

        public final String label;
        public final Scope scope;
        final List<KeyCode> defaults;

        Action(String label, Scope scope, KeyCode... defaults) {
            this.label = label;
            this.scope = scope;
            this.defaults = List.of(defaults);
        }
    }

    /** Keys that can't be rebound, shown in Help alongside the table. */
    public static final Map<String, String> FIXED = new LinkedHashMap<>();

    static {
        FIXED.put("Esc", "Leave full screen, then exit");
        FIXED.put("F11", "Full screen");
        FIXED.put("Double-click", "Zoom in / back to fit");
    }

    private static final Map<Action, List<KeyCode>> bindings = new EnumMap<>(Action.class);

    static {
        reset();
    }

    private Shortcuts() {
    }

    /** Applies saved overrides (action name → KeyCode name; blank means unbound) on top of the defaults. */
    public static void load(Map<String, String> overrides) {
        reset();
        if (overrides == null) return;
        overrides.forEach((name, key) -> {
            try {
                Action action = Action.valueOf(name);
                bindings.put(action, key == null || key.isBlank() ? List.of() : List.of(KeyCode.valueOf(key)));
            } catch (IllegalArgumentException ignored) {
                // An action or key a newer build saved; keep the default.
            }
        });
    }

    /** Back to the defaults. */
    public static void reset() {
        bindings.clear();
        for (Action a : Action.values()) bindings.put(a, a.defaults);
    }

    /** The overrides to save: only actions that differ from their defaults. */
    public static Map<String, String> overrides() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Action a : Action.values()) {
            List<KeyCode> keys = bindings.get(a);
            if (!keys.equals(a.defaults)) out.put(a.name(), keys.isEmpty() ? "" : keys.get(0).name());
        }
        return out;
    }

    /**
     * Binds action to key alone. Any other action already using that key
     * loses it (an action and a key it doesn't share stay put); returns the
     * action that lost it, or null.
     */
    public static Action rebind(Action action, KeyCode key) {
        Action displaced = null;
        for (Action other : Action.values()) {
            if (other != action && bindings.get(other).contains(key)) {
                List<KeyCode> rest = bindings.get(other).stream().filter(k -> k != key).toList();
                bindings.put(other, rest);
                displaced = other;
            }
        }
        bindings.put(action, List.of(key));
        return displaced;
    }

    /** Whether a key may be bound at all: not a fixed key or a bare modifier. */
    public static boolean bindable(KeyCode key) {
        return key != KeyCode.ESCAPE && key != KeyCode.F11 && !key.isModifierKey() && key != KeyCode.UNDEFINED;
    }

    /** The action this key press means in a screen of the given scope (PRACTICE or VIEWER), or null. */
    public static Action match(KeyEvent e, Scope screen) {
        if (e.isControlDown() || e.isAltDown() || e.isMetaDown()) return null;
        for (Action a : Action.values()) {
            if (a.scope != Scope.BOTH && a.scope != screen) continue;
            if (bindings.get(a).contains(e.getCode())) return a;
        }
        return null;
    }

    /** "G", "Space", "+ / Num +"…, or "—" when unbound. */
    public static String describe(Action action) {
        List<KeyCode> keys = bindings.get(action);
        if (keys.isEmpty()) return "—";
        return String.join(" / ", keys.stream().map(Shortcuts::keyName).distinct().toList());
    }

    /** The first key's name, for tooltips: "Grayscale (G)". */
    public static String hint(Action action) {
        List<KeyCode> keys = bindings.get(action);
        return keys.isEmpty() ? action.label : action.label + " (" + keyName(keys.get(0)) + ")";
    }

    public static String keyName(KeyCode key) {
        return switch (key) {
            case PLUS, ADD, EQUALS -> "+";
            case MINUS, SUBTRACT -> "-";
            case DIGIT0, NUMPAD0 -> "0";
            case DIGIT1, NUMPAD1 -> "1";
            case OPEN_BRACKET -> "[";
            case CLOSE_BRACKET -> "]";
            case LEFT -> "←";
            case RIGHT -> "→";
            case UP -> "↑";
            case DOWN -> "↓";
            case BACK_SPACE -> "Backspace";
            default -> key.getName();
        };
    }
}
