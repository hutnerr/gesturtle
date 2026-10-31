package com.gesturtle.ui.common;

import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.scene.text.Font;

/**
 * Applies the light/dark stylesheet pair to a Scene. Dark is
 * a color-only overlay on top of the base stylesheet: layout, padding, and
 * font rules live once in style.css, and style-dark.css only repaints them.
 *
 * Alert/Dialog/TextInputDialog/ChoiceDialog each open their own top-level
 * window with their own Scene, so they never automatically inherit the main
 * window's stylesheets. {@link #style(DialogPane)} must be called on every
 * one of those so they stay visually consistent with the rest of the app.
 */
public final class Theme {
    public static final String LIGHT = "light";
    public static final String DARK = "dark";

    private static final String BASE_CSS = "/style.css";
    private static final String DARK_CSS = "/style-dark.css";

    /** Bundled so the UI looks the same on every OS, instead of falling back to whatever sans-serif is installed. */
    private static final String[] FONTS = {
            "/fonts/Inter-Regular.ttf", "/fonts/Inter-Medium.ttf", "/fonts/Inter-SemiBold.ttf", "/fonts/Inter-Bold.ttf"};

    /**
     * On Windows, text renders with LCD (ClearType-style) smoothing instead of
     * JavaFX's default grayscale, which looks soft and blocky there next to
     * native apps. A style class on each root, so style.css can scope it.
     */
    private static final String WINDOWS_CLASS = "os-windows";
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    /** True on Windows, where text uses LCD smoothing (see WINDOWS_CLASS). */
    public static boolean usesLcdText() {
        return WINDOWS;
    }

    /** Dark until apply() runs, so dialogs shown before the main window (first-run welcome, startup errors) match the default theme. */
    private static String current = DARK;
    private static boolean fontsLoaded = false;

    private Theme() {
    }

    /** Registers the bundled Inter weights with JavaFX; style.css then refers to them by family name. */
    private static void loadFonts() {
        if (fontsLoaded) return;
        fontsLoaded = true;
        for (String path : FONTS) {
            try (var in = Theme.class.getResourceAsStream(path)) {
                if (in != null) Font.loadFont(in, 16);
            } catch (java.io.IOException ignored) {
                // falls back to the next family in style.css's -fx-font-family list
            }
        }
    }

    public static void apply(Scene scene, String theme) {
        loadFonts();
        current = theme;
        scene.getStylesheets().setAll(stylesheets(theme));
        if (WINDOWS && scene.getRoot() != null && !scene.getRoot().getStyleClass().contains(WINDOWS_CLASS)) {
            scene.getRoot().getStyleClass().add(WINDOWS_CLASS);
        }
    }

    /** Styles a dialog/alert's pane to match the app's current theme (see class doc). */
    public static void style(DialogPane pane) {
        loadFonts();
        pane.getStylesheets().setAll(stylesheets(current));
        if (WINDOWS && !pane.getStyleClass().contains(WINDOWS_CLASS)) pane.getStyleClass().add(WINDOWS_CLASS);
    }

    private static java.util.List<String> stylesheets(String theme) {
        java.util.List<String> sheets = new java.util.ArrayList<>();
        var base = Theme.class.getResource(BASE_CSS);
        if (base != null) sheets.add(base.toExternalForm());
        if (DARK.equals(theme)) {
            var dark = Theme.class.getResource(DARK_CSS);
            if (dark != null) sheets.add(dark.toExternalForm());
        }
        return sheets;
    }
}
