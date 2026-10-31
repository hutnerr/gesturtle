package com.gesturtle;

import com.gesturtle.config.ConfigStore;
import com.gesturtle.ui.common.Dialogs;
import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Where errors go. A packaged build has no console, so anything printed
 * would vanish: instead java.util.logging (and sqlite-jdbc's SLF4J logging,
 * routed into it by slf4j-jdk14) writes to logs/gesturtle.log in the app's
 * config folder, and an exception nothing caught is logged and shown in a
 * dialog naming that file, so a bug report can include it.
 */
public final class AppLog {
    private static final Logger LOG = Logger.getLogger("com.gesturtle");
    private static Path logFile;
    /** One crash dialog at a time: a failure in a pulse or a layout pass can repeat every frame. */
    private static volatile boolean dialogShowing;

    private AppLog() {
    }

    /** Call first thing in main, before JavaFX starts. */
    public static void install() {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %3$s: %5$s%6$s%n");
        try {
            Path dir = logDir();
            Files.createDirectories(dir);
            // Three files of up to 1 MB, newest is gesturtle.0.log; appended to across runs.
            FileHandler handler = new FileHandler(dir.resolve("gesturtle.%g.log").toString(), 1_000_000, 3, true);
            handler.setFormatter(new SimpleFormatter());
            handler.setLevel(Level.INFO);
            Logger.getLogger("").addHandler(handler);
            logFile = dir.resolve("gesturtle.0.log");
        } catch (IOException | RuntimeException e) {
            // No log file then; the console handler (when there's a console) still gets everything.
        }
        LOG.info("Gesturtle " + AppVersion.display() + " starting, Java " + System.getProperty("java.version")
                + " on " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught(thread, error));
    }

    public static Logger logger() {
        return LOG;
    }

    /** Where the log files go, for "Open log folder". */
    public static Path logDir() {
        return ConfigStore.appDir().resolve("logs");
    }

    private static void uncaught(Thread thread, Throwable error) {
        LOG.log(Level.SEVERE, "Uncaught exception on " + thread.getName(), error);
        if (dialogShowing) return;
        dialogShowing = true;
        try {
            Runnable show = () -> {
                try {
                    Dialogs.error("Something went wrong",
                            "Gesturtle hit an unexpected error:\n\n" + error
                                    + (logFile != null ? "\n\nDetails were saved to:\n" + logFile
                                    + "\n\nPlease include that file if you report this." : ""));
                } finally {
                    dialogShowing = false;
                }
            };
            // Always later, even on the FX thread: showAndWait isn't allowed mid-layout or mid-animation.
            Platform.runLater(show);
        } catch (Throwable ignored) {
            // JavaFX isn't running (or is shutting down): the log has it.
            dialogShowing = false;
        }
    }
}
