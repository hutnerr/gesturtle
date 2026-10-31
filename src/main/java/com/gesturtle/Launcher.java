package com.gesturtle;

import javafx.application.Application;

public class Launcher {
    private static final String PULSE_PROP = "javafx.animation.pulse";

    public static void main(String[] args) {
        AppLog.install();
        matchPulseToDisplay();
        Application.launch(GesturtleApp.class, args);
    }

    /**
     * JavaFX animates at a fixed 60 frames a second whatever the screen, so
     * on a 120Hz+ display every animation and scroll looks half as smooth as
     * the rest of the desktop. Asks the display for its refresh rate (about
     * 25ms through AWT) and runs JavaFX's pulse at that instead. An explicit
     * -Djavafx.animation.pulse wins.
     *
     * Skipped on macOS, where starting AWT ahead of JavaFX can tangle the two
     * over the main thread.
     */
    private static void matchPulseToDisplay() {
        if (System.getProperty(PULSE_PROP) != null) return;
        if (System.getProperty("os.name", "").toLowerCase().startsWith("mac")) return;
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) return;
            int best = 0;
            for (var device : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                best = Math.max(best, device.getDisplayMode().getRefreshRate());
            }
            if (best > 60) System.setProperty(PULSE_PROP, String.valueOf(Math.min(best, 240)));
        } catch (Throwable ignored) {
            // Stays at JavaFX's 60.
        }
    }
}
