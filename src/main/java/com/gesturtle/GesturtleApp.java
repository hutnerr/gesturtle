package com.gesturtle;

import com.gesturtle.config.AppConfig;
import com.gesturtle.config.ConfigStore;
import com.gesturtle.filter.Filter;
import com.gesturtle.library.IncomingImages;
import com.gesturtle.library.ImageTrash;
import com.gesturtle.library.LibraryService;
import com.gesturtle.model.SessionSpec;
import com.gesturtle.practice.SessionSnapshot;
import com.gesturtle.ui.Shell;
import com.gesturtle.ui.common.AsyncTask;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.common.Shortcuts;
import com.gesturtle.ui.common.Sounds;
import com.gesturtle.ui.common.Theme;
import com.gesturtle.ui.common.UiSounds;
import com.gesturtle.ui.components.Topography;
import com.gesturtle.ui.components.WindowChrome;
import com.gesturtle.ui.gallery.GalleryView;
import com.gesturtle.ui.home.HomeView;
import com.gesturtle.ui.imports.ImportView;
import com.gesturtle.ui.playlist.PlaylistView;
import com.gesturtle.ui.practice.PracticeContainerView;
import com.gesturtle.ui.settings.AboutView;
import com.gesturtle.ui.settings.HelpView;
import com.gesturtle.ui.settings.SettingsView;
import com.gesturtle.ui.tags.TagManagerView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** App entry point: owns config, the chosen library, and the navigation shell. */
public class GesturtleApp extends Application {
    private final ConfigStore configStore = new ConfigStore();
    private AppConfig config;
    private LibraryService library;
    private Shell shell;

    @Override
    public void start(Stage stage) {
        stage.setTitle("Gesturtle");
        stage.getIcons().setAll(com.gesturtle.ui.components.Logo.windowIcons());
        stage.initStyle(StageStyle.UNDECORATED);
        config = configStore.load();
        Sounds.configure(!config.soundsOff, !config.uiSoundsOff, config.soundVolume);
        Sounds.preload();
        Shortcuts.load(config.shortcuts);
        Topography.SHOWN.set(!config.topographyOff);
        Topography.OPACITY.set(config.topographyOpacity);

        String libraryRoot = resolveLibraryRoot(stage);
        if (libraryRoot == null) {
            return;
        }
        config.noteLibraryUsed(libraryRoot);
        configStore.save(config);

        // Opening reads meta/ into the database, which can take a few seconds the first time on a
        // machine or after a big git pull, so the window shows a spinner meanwhile.
        Scene scene = new Scene(loadingScreen(), config.windowWidth, config.windowHeight);
        Theme.apply(scene, config.theme);
        stage.setScene(scene);
        Dialogs.setMainWindow(stage);
        stage.show();
        AsyncTask.runDb(() -> {
            LibraryService opened = new LibraryService(libraryRoot);
            try {
                ImageTrash.reconcile(opened);
                opened.flushMeta();
            } catch (RuntimeException e) {
                // Deleted images just stay hidden until the next open.
            }
            boolean fresh = opened.images().count(new Filter.All()) == 0 && opened.tags().listAll().isEmpty();
            return new Opened(opened, fresh);
        }, opened -> {
            library = opened.library();
            AsyncTask.setAfterDbWork(library::flushMeta);
            showLibrary(stage, scene, opened.fresh());
            reportUnreadable(library.openedWith());
            fixOrientation(library);
        }, err -> {
            Dialogs.error("Could not open the library", libraryRoot + "\n\n" + err.getMessage());
            javafx.application.Platform.exit();
        });
    }

    private record Opened(LibraryService library, boolean fresh) {
    }

    /** Once per library: phone photos imported before EXIF orientation was honored get their size and thumbnail fixed. */
    private void fixOrientation(LibraryService opened) {
        // Failures are already logged by AsyncTask; it tries again next open.
        Consumer<Throwable> logged = err -> {
        };
        AsyncTask.runDb(() -> com.gesturtle.library.OrientationFix.pending(opened), jpegs -> {
            if (jpegs.isEmpty()) return;
            AsyncTask.runIo(() -> com.gesturtle.library.OrientationFix.measure(opened, jpegs), fixes ->
                    AsyncTask.runDb(() -> com.gesturtle.library.OrientationFix.apply(opened, fixes), () -> {
                        if (!fixes.isEmpty() && shell != null) shell.refreshCurrent();
                    }, logged), logged);
        }, logged);
    }

    /** The logo and a spinner on a blank window, while the library opens. */
    private static javafx.scene.Parent loadingScreen() {
        javafx.scene.control.ProgressIndicator spinner = new javafx.scene.control.ProgressIndicator();
        spinner.setMaxSize(40, 40);
        javafx.scene.layout.VBox column = new javafx.scene.layout.VBox(32, new com.gesturtle.ui.components.Logo(5), spinner);
        column.setAlignment(javafx.geometry.Pos.CENTER);
        javafx.scene.layout.StackPane pane = new javafx.scene.layout.StackPane(column);
        pane.getStyleClass().add("loading-screen");
        return pane;
    }

    private void showLibrary(Stage stage, Scene scene, boolean freshLibrary) {
        Shell shell = new Shell();
        this.shell = shell;
        // Built on first use, not up front: the setup form is a third of a second of work that shouldn't
        // hold up the window. prebuildAll() below builds it right after, along with the other views.
        PracticeContainerView[] practiceView = new PracticeContainerView[1];
        Supplier<PracticeContainerView> practice = () -> {
            if (practiceView[0] == null) practiceView[0] = new PracticeContainerView(library, config, configStore);
            return practiceView[0];
        };
        Consumer<SessionSpec> startSession = spec -> {
            practice.get().startSpec(spec);
            shell.show("practice");
        };
        Consumer<Integer> startPracticeWithPlaylist = playlistId -> {
            practice.get().showSetupWithPlaylist(playlistId);
            shell.show("practice");
        };
        Consumer<Integer> editPreset = presetId -> {
            practice.get().showSetupForEdit(presetId, library.presets().loadSpec(presetId));
            shell.show("practice");
        };
        Consumer<SessionSnapshot> resumeSession = snapshot -> {
            practice.get().resume(snapshot);
            shell.show("practice");
        };
        HomeView homeView = new HomeView(library, startSession, editPreset, resumeSession);

        shell.registerView("home", "Home", "home", () -> homeView);
        shell.registerView("gallery", "Gallery", "gallery", () -> new GalleryView(library));
        shell.registerView("import", "Import", "import", () -> new ImportView(library, configStore, config));
        shell.registerView("tags", "Tags", "tag", () -> new TagManagerView(library));
        shell.registerView("playlists", "Playlists", "queue", () -> new PlaylistView(library, startPracticeWithPlaylist));
        shell.registerView("practice", "Practice", "brush", practice::get);
        shell.registerFooterView("about", "About", "info", () -> new AboutView(getHostServices()));
        shell.registerFooterView("help", "Help", "help", () -> new HelpView(getHostServices()));
        shell.registerFooterView("settings", "Settings", "settings",
                () -> new SettingsView(library, configStore, config, stage));
        shell.show("home");
        shell.prebuildAll();
        // PracticeView owns entering/leaving full screen (F11, Esc), so JavaFX's
        // built-in Esc-to-exit can't fire alongside its "Exit session" prompt.
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        stage.setFullScreenExitHint("Press F11 or Esc to leave full screen");
        WindowChrome chrome = WindowChrome.wrap(stage, shell);
        stage.fullScreenProperty().addListener((o, was, on) -> {
            shell.setNavVisible(!on);
            chrome.setTitleBarVisible(!on);
        });

        scene.setRoot(chrome);
        acceptDroppedFiles(scene, shell);
        acceptPaste(scene, shell);
        UiSounds.install(scene);

        stage.setOnCloseRequest(e -> shutdown(stage, scene));
        // Coming back from a terminal is when a git pull may have changed meta/ (or removed the database).
        stage.focusedProperty().addListener((o, was, focused) -> {
            if (focused) {
                checkDatabaseStillOpen(stage, scene);
                readMetaChanges(shell);
            }
        });

        if (freshLibrary) {
            runFirstRunFlow(shell);
        }
    }

    /**
     * Files or folders dropped anywhere on the window are staged on the
     * Import page, which is brought up. So is an image dragged out of a
     * browser: by its link when it has one (downloaded, and credited to the
     * link), else as the picture itself.
     */
    private void acceptDroppedFiles(Scene scene, Shell shell) {
        scene.setOnDragOver(e -> {
            var board = e.getDragboard();
            if (board.hasFiles() || board.hasImage() || (board.hasUrl() && IncomingImages.isImageLink(board.getUrl()))) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            var board = e.getDragboard();
            boolean accepted = true;
            if (board.hasFiles()) {
                List<Path> paths = board.getFiles().stream().map(File::toPath).toList();
                ((ImportView) shell.show("import")).stagePaths(paths);
            } else if (board.hasUrl() && IncomingImages.isImageLink(board.getUrl())) {
                ((ImportView) shell.show("import")).stageLink(board.getUrl());
            } else if (board.hasImage()) {
                ((ImportView) shell.show("import")).stageImage(board.getImage(), null);
            } else {
                accepted = false;
            }
            e.setDropCompleted(accepted);
            e.consume();
        });
    }

    /**
     * Ctrl+V anywhere outside a text field stages the clipboard's image,
     * files, or image link on the Import page. A clipboard with nothing
     * importable leaves the key alone.
     */
    private void acceptPaste(Scene scene, Shell shell) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.V || !e.isShortcutDown() || e.isAltDown()) return;
            if (scene.getFocusOwner() instanceof javafx.scene.control.TextInputControl) return;
            var clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            boolean importable = clipboard.hasFiles() || clipboard.hasImage()
                    || (clipboard.hasUrl() && IncomingImages.isImageLink(clipboard.getUrl()))
                    || (clipboard.hasString() && IncomingImages.isImageLink(clipboard.getString()));
            if (!importable) return;
            ((ImportView) shell.show("import")).stageClipboard();
            e.consume();
        });
    }

    /** No library yet → point at Import. */
    private void runFirstRunFlow(Shell shell) {
        Dialogs.info("Welcome to Gesturtle", "Your library is empty. Import some images to start.");
        shell.show("import");
    }

    private String resolveLibraryRoot(Stage stage) {
        if (config.libraryRoot != null && Files.isDirectory(Path.of(config.libraryRoot))) {
            return config.libraryRoot;
        }
        Dialogs.info("Welcome to Gesturtle",
                "Choose a folder for your library.");
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose a library folder");
        File chosen = chooser.showDialog(stage);
        if (chosen == null) {
            return null;
        }
        return chosen.getAbsolutePath();
    }

    /** Picks up meta/ files a git pull changed while Gesturtle was open, and shows them. */
    private void readMetaChanges(Shell shell) {
        if (library == null || replacedNoticeShown) return;
        AsyncTask.runDb(() -> library.metaChangedOnDisk() ? library.syncMeta() : null, result -> {
            if (result == null) return;
            if (result.changed()) shell.refreshCurrent();
            reportUnreadable(result);
        }, err -> Dialogs.error("Could not read library changes", err.getMessage()));
    }

    /** Files Gesturtle couldn't read are left as they are; most likely git left conflict markers in them. */
    private Set<String> reportedUnreadable = Set.of();

    private boolean tooNewShown;

    private void reportUnreadable(com.gesturtle.meta.MetaStore.SyncResult result) {
        if (!result.tooNew().isEmpty() && !tooNewShown) {
            tooNewShown = true;
            Dialogs.info("Update Gesturtle",
                    result.tooNew().size() + (result.tooNew().size() == 1 ? " file" : " files") + " in this library were saved by a newer version of Gesturtle. "
                            + "This version leaves them as they are, so the things they describe may be missing or "
                            + "out of date here. Update Gesturtle on this machine to see them.");
        }
        Set<String> unreadable = new java.util.TreeSet<>(result.unreadable());
        if (unreadable.isEmpty() || unreadable.equals(reportedUnreadable)) return;
        reportedUnreadable = unreadable;
        String list = String.join("\n", unreadable.stream().limit(8).map(p -> "meta/" + p).toList())
                + (unreadable.size() > 8 ? "\n…and " + (unreadable.size() - 8) + " more" : "");
        Dialogs.info("Some library files can't be read",
                "These files in the library folder aren't valid, probably because a git merge left conflict markers "
                        + "in them:\n\n" + list + "\n\nGesturtle leaves what they describe as it was. Fix the files "
                        + "(keep one side of each conflict), then come back to Gesturtle and it will read them.");
    }

    private boolean replacedNoticeShown;

    /**
     * If gesturtle.db was replaced or removed on disk (e.g. the first pull after
     * another machine stopped tracking it in git), nothing more
     * can be saved safely: the connection still points at the old file. Quit,
     * so the next start opens the new one.
     */
    private void checkDatabaseStillOpen(Stage stage, Scene scene) {
        if (library == null || replacedNoticeShown) return;
        AsyncTask.runDb(library::databaseReplacedOnDisk, replaced -> {
            if (!replaced || replacedNoticeShown) return;
            replacedNoticeShown = true;
            Dialogs.info("Library changed on disk",
                    "gesturtle.db was removed or replaced, probably by a git pull or checkout. Gesturtle will close now "
                            + "so nothing is saved to the old copy. Open it again and it rebuilds from the meta/ folder.");
            shutdown(stage, scene);
            javafx.application.Platform.exit();
        }, err -> {
        });
    }

    private void shutdown(Stage stage, Scene scene) {
        config.windowWidth = scene.getWidth();
        config.windowHeight = scene.getHeight();
        try {
            configStore.save(config);
        } catch (RuntimeException e) {
            // Losing the window size is fine; skipping the library's last meta/ writes below isn't.
            AppLog.logger().log(java.util.logging.Level.WARNING, "Could not save config on exit", e);
        }
        // Closed on the DB thread, after any work still queued there, so its last meta/ writes aren't cut off.
        LibraryService open = library;
        AsyncTask.shutdown(() -> {
            if (open != null) open.close();
        });
    }
}
