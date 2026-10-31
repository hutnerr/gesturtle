package com.gesturtle.model;

import java.util.Locale;

/**
 * The colors a tag can wear: gruvbox's accent hues. Stored by name, and
 * styled by the "tag-color-<name>" class, so each theme picks its own shade.
 */
public enum TagColor {
    RED, ORANGE, YELLOW, GREEN, AQUA, BLUE, PURPLE, GRAY;

    public String styleClass() {
        return "tag-color-" + name().toLowerCase(Locale.ROOT);
    }

    public String label() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }

    /** Null for null or an unknown name (e.g. from a newer build's archive). */
    public static TagColor parse(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
