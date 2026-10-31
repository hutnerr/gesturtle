package com.gesturtle.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AppConfig {
    public String libraryRoot;
    public List<String> recentLibraries = new ArrayList<>();
    public double windowWidth = 1360;
    public double windowHeight = 880;
    /** "dark" (default) or "light"; see com.gesturtle.ui.common.Theme. */
    public String theme = "dark";
    /** Leave files already in the library, or repeated within the batch, out of the import staging table. */
    public boolean skipDuplicatesOnImport = false;
    /** Delete each imported file's original once the import commits (the library keeps its own copy either way). */
    public boolean deleteOriginalsOnImport = false;
    /** Images imported from a link also get the link in their notes (it's always their credit). */
    public boolean linkInNotes = false;
    /** Go straight back to practice setup when a session ends, without the review grid. */
    public boolean skipSessionReview = false;
    /** Practice audio cues (ticks, new image, breaks); "off" rather than "on" so a config saved before this setting existed reads as on. */
    public boolean soundsOff = false;
    /** Interface sounds (clicks, toggles, dialogs); same "off" convention. */
    public boolean uiSoundsOff = false;
    /** 0 to 1. Boxed so ConfigStore can tell an older config (null) from a deliberate 0. */
    public Double soundVolume = 0.7;
    /** Hide the faint contour-line pattern behind pages and the sidebar; same "off" convention. */
    public boolean topographyOff = false;
    /** The contour lines' opacity. Boxed so ConfigStore can tell an older config (null) from a saved value. */
    public Double topographyOpacity = 0.06;
    /** Keep the window above other apps while a session runs (for drawing in another program). */
    public boolean practiceAlwaysOnTop = false;
    /** Rebound keys: Shortcuts.Action name → KeyCode name (blank = unbound). Only changes from the defaults. */
    public Map<String, String> shortcuts = new LinkedHashMap<>();

    /** Puts every setting back to a fresh install's value, in place, so anyone holding this object sees the reset. */
    public void resetToDefaults() {
        AppConfig defaults = new AppConfig();
        libraryRoot = defaults.libraryRoot;
        recentLibraries = defaults.recentLibraries;
        windowWidth = defaults.windowWidth;
        windowHeight = defaults.windowHeight;
        theme = defaults.theme;
        skipDuplicatesOnImport = defaults.skipDuplicatesOnImport;
        deleteOriginalsOnImport = defaults.deleteOriginalsOnImport;
        linkInNotes = defaults.linkInNotes;
        skipSessionReview = defaults.skipSessionReview;
        soundsOff = defaults.soundsOff;
        uiSoundsOff = defaults.uiSoundsOff;
        soundVolume = defaults.soundVolume;
        topographyOff = defaults.topographyOff;
        topographyOpacity = defaults.topographyOpacity;
        practiceAlwaysOnTop = defaults.practiceAlwaysOnTop;
        shortcuts = defaults.shortcuts;
    }

    public void noteLibraryUsed(String path) {
        libraryRoot = path;
        recentLibraries.remove(path);
        recentLibraries.add(0, path);
        while (recentLibraries.size() > 8) {
            recentLibraries.remove(recentLibraries.size() - 1);
        }
    }
}
