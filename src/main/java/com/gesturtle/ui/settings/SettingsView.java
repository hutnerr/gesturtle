package com.gesturtle.ui.settings;

import com.gesturtle.config.AppConfig;
import com.gesturtle.config.ConfigStore;
import com.gesturtle.filter.Filter;
import com.gesturtle.library.ArchiveExporter;
import com.gesturtle.library.ArchiveImporter;
import com.gesturtle.library.ImageTrash;
import com.gesturtle.library.LibraryCheck;
import com.gesturtle.library.LibraryService;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.ui.common.AsyncTask;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.common.Shortcuts;
import com.gesturtle.ui.common.Sounds;
import com.gesturtle.ui.common.Theme;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.model.SavedView;
import com.gesturtle.ui.components.Card;
import com.gesturtle.ui.components.ColumnFlow;
import com.gesturtle.ui.components.FormRow;
import com.gesturtle.ui.components.Page;
import com.gesturtle.ui.components.SegmentedControl;
import com.gesturtle.ui.components.Topography;
import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Theme, import and practice options, sound, shortcuts, library location and health, and archive export/import. */
public class SettingsView extends Page {

    public SettingsView(LibraryService library, ConfigStore configStore, AppConfig config, Stage stage) {
        super("Settings");
        getStyleClass().add("settings-view");
        setSubtitle("Configure the almighty Gesturtle.");

        // Two columns of cards in a wide window, each going into the shorter one. The long shortcut list
        // comes once the short cards have filled both columns a little, so the rest even them out after it.
        setScrollingBody(false, new ColumnFlow(520, 2,
                buildThemeSection(library, configStore, config, stage),
                buildGallerySection(library),
                buildImportSection(configStore, config),
                buildPracticeSection(configStore, config),
                buildShortcutSection(configStore, config),
                buildSoundSection(configStore, config),
                buildLibrarySection(library, configStore, config, stage),
                buildArchiveSection(library, stage),
                buildDangerSection(library, configStore, config)));
    }

    /** Library settings: they travel with the library, like the Gallery's sort and tile size. */
    private Card buildGallerySection(LibraryService library) {
        CheckBox builtIns = new CheckBox("Show built-in views");
        builtIns.setSelected(SavedView.builtInsShown(library.settings().get(SavedView.BUILT_INS_SETTING).orElse(null)));
        builtIns.setOnAction(e -> library.settings().set(SavedView.BUILT_INS_SETTING, builtIns.isSelected() ? "on" : "off"));
        return new Card("Gallery").add(new FormRow("Views", builtIns)
                .hint("Favorites and Untagged."));
    }

    private Card buildThemeSection(LibraryService library, ConfigStore configStore, AppConfig config, Stage stage) {
        SegmentedControl<String> themeChoice = new SegmentedControl<String>()
                .option(Theme.LIGHT, "Light")
                .option(Theme.DARK, "Dark");
        themeChoice.setValue(Theme.LIGHT.equals(config.theme) ? Theme.LIGHT : Theme.DARK);
        themeChoice.onChange(theme -> {
            config.theme = theme;
            configStore.save(config);
            if (stage.getScene() != null) Theme.apply(stage.getScene(), config.theme);
        });

        CheckBox topography = new CheckBox("Contour lines");
        topography.setSelected(!config.topographyOff);
        topography.setOnAction(e -> {
            config.topographyOff = !topography.isSelected();
            configStore.save(config);
            Topography.SHOWN.set(topography.isSelected());
        });
        // Live while dragging, so you can see it land; saved once you let go.
        Slider topographyStrength = new Slider(Topography.MIN_OPACITY, Topography.MAX_OPACITY,
                Math.max(Topography.MIN_OPACITY, Math.min(Topography.MAX_OPACITY, config.topographyOpacity)));
        topographyStrength.setPrefWidth(180);
        topographyStrength.disableProperty().bind(topography.selectedProperty().not());
        topographyStrength.valueProperty().addListener((o, was, v) -> Topography.OPACITY.set(v.doubleValue()));
        Runnable saveStrength = () -> {
            config.topographyOpacity = topographyStrength.getValue();
            configStore.save(config);
        };
        topographyStrength.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing) saveStrength.run();
        });
        topographyStrength.setOnMouseReleased(e -> saveStrength.run());

        return new Card("Appearance").add(
                new FormRow("Theme", themeChoice),
                new FormRow("Background", topography),
                new FormRow("Contour strength", volumeRow(topographyStrength)));
    }

    private Card buildImportSection(ConfigStore configStore, AppConfig config) {
        CheckBox linkInNotes = new CheckBox("Add the link to notes");
        linkInNotes.setSelected(config.linkInNotes);
        linkInNotes.setOnAction(e -> {
            config.linkInNotes = linkInNotes.isSelected();
            configStore.save(config);
        });

        return new Card("Import").add(
                new FormRow("From a link", linkInNotes)
                        .hint("Images from a link always get it as their credit. This also puts it in their notes."));
    }

    private Card buildPracticeSection(ConfigStore configStore, AppConfig config) {
        CheckBox skipReview = new CheckBox("Skip session review");
        skipReview.setSelected(config.skipSessionReview);
        skipReview.setOnAction(e -> {
            config.skipSessionReview = skipReview.isSelected();
            configStore.save(config);
        });

        return new Card("Practice").add(
                new FormRow("After a session", skipReview));
    }

    /** Practice cues and interface sounds, each switchable, with one shared volume. */
    private Card buildSoundSection(ConfigStore configStore, AppConfig config) {
        CheckBox practiceSounds = new CheckBox("Practice sounds");
        practiceSounds.setSelected(!config.soundsOff);
        practiceSounds.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        CheckBox uiSounds = new CheckBox("Interface sounds");
        uiSounds.setSelected(!config.uiSoundsOff);
        uiSounds.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        Slider volume = new Slider(0, 1, config.soundVolume);
        volume.setPrefWidth(180);
        volume.disableProperty().bind(practiceSounds.selectedProperty().not().and(uiSounds.selectedProperty().not()));
        Runnable applySound = () -> {
            config.soundsOff = !practiceSounds.isSelected();
            config.uiSoundsOff = !uiSounds.isSelected();
            config.soundVolume = volume.getValue();
            Sounds.configure(practiceSounds.isSelected(), uiSounds.isSelected(), volume.getValue());
            configStore.save(config);
        };
        // Applied in a filter, before the click itself is heard, so turning interface sounds on clicks
        // and turning them off is silent.
        practiceSounds.addEventFilter(javafx.event.ActionEvent.ACTION, e -> applySound.run());
        uiSounds.addEventFilter(javafx.event.ActionEvent.ACTION, e -> applySound.run());
        volume.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing) applySound.run();
        });
        volume.setOnMouseReleased(e -> {
            applySound.run();
            Sounds.play(practiceSounds.isSelected() ? Sounds.Sound.IMAGE : Sounds.Sound.CONFIRM);
        });
        AppButton testPractice = AppButton.icon("volume", "Play a practice sound", () -> Sounds.playAnyway(Sounds.Sound.TICK));
        testPractice.getStyleClass().add(com.gesturtle.ui.common.UiSounds.QUIET);
        AppButton testUi = AppButton.icon("volume", "Play an interface sound", () -> Sounds.playAnyway(Sounds.Sound.FAVORITE));
        testUi.getStyleClass().add(com.gesturtle.ui.common.UiSounds.QUIET);

        return new Card("Sound").add(
                new FormRow("Practice", practiceSounds, testPractice),
                new FormRow("Interface", uiSounds, testUi),
                new FormRow("Volume", volumeRow(volume)));
    }

    /** The slider in a row as tall as the other controls, so it lines up with its label. */
    private static javafx.scene.layout.HBox volumeRow(Slider volume) {
        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(volume);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.setMinHeight(44);
        return row;
    }

    /**
     * One row per rebindable action. Clicking a key button waits for the next
     * key press and binds it; a key already used elsewhere moves to this
     * action. Esc cancels the wait. The fixed keys follow as plain keycaps, so
     * this is the one complete list of shortcuts in the app.
     */
    private Card buildShortcutSection(ConfigStore configStore, AppConfig config) {
        ColumnFlow grid = new ColumnFlow(300, 4).gaps(24, 6).columnsFirst();
        Map<Shortcuts.Action, ToggleButton> buttons = new EnumMap<>(Shortcuts.Action.class);
        Runnable refresh = () -> buttons.forEach((action, button) -> button.setText(Shortcuts.describe(action)));
        for (Shortcuts.Action action : Shortcuts.Action.values()) {
            Label label = new Label(action.label);
            label.getStyleClass().add("body-text");
            label.setWrapText(true);
            if (action.scope == Shortcuts.Scope.PRACTICE) label.setTooltip(new Tooltip("Only during practice"));
            ToggleButton keyButton = AppButton.small(AppButton.toggle(Shortcuts.describe(action), AppButton.Variant.SECONDARY));
            keyButton.getStyleClass().add("shortcut-key");
            keyButton.setMinWidth(110);
            keyButton.setOnAction(e -> {
                if (!keyButton.isSelected()) {
                    refresh.run();
                    return;
                }
                buttons.values().forEach(b -> {
                    if (b != keyButton) b.setSelected(false);
                });
                refresh.run();
                keyButton.setText("Press a key…");
            });
            keyButton.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (!keyButton.isSelected()) return;
                e.consume();
                keyButton.setSelected(false);
                if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE || !Shortcuts.bindable(e.getCode())) {
                    refresh.run();
                    return;
                }
                Shortcuts.Action displaced = Shortcuts.rebind(action, e.getCode());
                config.shortcuts = Shortcuts.overrides();
                configStore.save(config);
                refresh.run();
                if (displaced != null && Shortcuts.describe(displaced).equals("—")) {
                    Dialogs.info("Shortcut moved", Shortcuts.keyName(e.getCode()) + " was " + displaced.label
                            + ", which now has no key. Give it a new one here if you want it back.");
                }
            });
            keyButton.focusedProperty().addListener((o, was, focused) -> {
                if (!focused && keyButton.isSelected()) {
                    keyButton.setSelected(false);
                    refresh.run();
                }
            });
            buttons.put(action, keyButton);
            HBox row = new HBox(14, keyButton, label);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            grid.getChildren().add(row);
        }
        Shortcuts.FIXED.forEach((key, what) -> {
            // The key buttons' shape, but disabled so it reads as greyed out. Mouse-transparent so
            // hovering it still reaches the row's tooltip, which a disabled button would swallow.
            AppButton cap = AppButton.small(new AppButton(key, AppButton.Variant.SECONDARY));
            cap.getStyleClass().add("shortcut-key");
            cap.setMinWidth(110);
            cap.setDisable(true);
            cap.setMouseTransparent(true);
            Label label = new Label(what);
            label.getStyleClass().addAll("body-text", "shortcut-fixed-label");
            label.setWrapText(true);
            HBox row = new HBox(14, cap, label);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            Tooltip.install(row, new Tooltip("Can't be changed"));
            grid.getChildren().add(row);
        });
        AppButton reset = AppButton.icon("restart", "Reset all shortcuts to their defaults", () -> {
            Shortcuts.reset();
            config.shortcuts = Shortcuts.overrides();
            configStore.save(config);
            refresh.run();
        });
        return new Card("Keyboard shortcuts",
                "Click a key, then press the new one. The keycaps at the end can't be changed.")
                .headerActions(reset).add(grid);
    }

    private Card buildLibrarySection(LibraryService library, ConfigStore configStore, AppConfig config, Stage stage) {
        Label pathLabel = new Label(library.root().toString());
        pathLabel.setWrapText(true);
        // A long path would otherwise demand its full length as a minimum and widen the whole page.
        pathLabel.setMinWidth(0);
        pathLabel.setTooltip(new Tooltip(library.root().toString()));
        pathLabel.getStyleClass().add("muted-label");

        return new Card("Library", "Where images, tags, and settings live.").add(
                new FormRow("Location").below(pathLabel),
                new FormRow("",
                        AppButton.secondary("Reveal in file manager", () -> reveal(library.root())).icon("folder"),
                        AppButton.secondary("Change library…", () -> changeLibrary(stage, configStore, config))),
                new FormRow("Health", AppButton.secondary("Check library…", () -> checkLibrary(library, stage)).icon("check"))
                        .hint("Finds images with missing files and restores them from a folder you pick."));
    }

    private void checkLibrary(LibraryService library, Stage stage) {
        AsyncTask.runDb(() -> LibraryCheck.findMissing(library), missing -> {
            if (missing.isEmpty()) {
                Dialogs.info("Library is healthy", "Every image's file is where it should be.");
                return;
            }
            ButtonType find = new ButtonType("Find in folder…", ButtonBar.ButtonData.OK_DONE);
            ButtonType remove = new ButtonType("Remove from library", ButtonBar.ButtonData.OTHER);
            ButtonType close = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Missing files");
            Label heading = new Label(missing.size() + (missing.size() == 1 ? " image is" : " images are") + " missing a file");
            heading.getStyleClass().add("dialog-title");
            String names = String.join("\n", missing.stream().limit(8)
                    .map(r -> r.originalName() == null ? r.hash() : r.originalName()).toList())
                    + (missing.size() > 8 ? "\n…and " + (missing.size() - 8) + " more" : "");
            Label body = new Label("Their files aren't in the library folder (a moved drive or an incomplete sync?).\n\n"
                    + names + "\n\nPick a folder that has them and Gesturtle will copy them back, matching by content. "
                    + "Or remove these entries from the library.");
            body.getStyleClass().add("dialog-message");
            body.setWrapText(true);
            javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(10, heading, body);
            content.getStyleClass().add("dialog-body");
            content.setPrefWidth(480);
            dialog.getDialogPane().setContent(content);
            dialog.getDialogPane().getButtonTypes().addAll(close, remove, find);
            Dialogs.prepare(dialog);
            ButtonType choice = dialog.showAndWait().orElse(close);
            if (choice == find) relink(library, missing, stage);
            else if (choice == remove) removeMissing(library, missing);
        }, err -> Dialogs.error("Could not check library", err.getMessage()));
    }

    private void relink(LibraryService library, List<ImageRecord> missing, Stage stage) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Folder with the missing images");
        File folder = chooser.showDialog(stage);
        if (folder == null) return;
        AsyncTask.runIo(() -> {
            try {
                return LibraryCheck.relinkFrom(library, missing, folder.toPath());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, result -> Dialogs.info("Relink finished",
                result.restored() + " of " + missing.size() + " restored after checking " + result.filesScanned()
                        + (result.filesScanned() == 1 ? " file." : " files.")
                        + (result.stillMissing() > 0 ? "\n\n" + result.stillMissing() + " still missing. Run Check library again with another folder." : "")),
                err -> Dialogs.error("Relink failed", err.getMessage()));
    }

    private void removeMissing(LibraryService library, List<ImageRecord> missing) {
        if (!Dialogs.confirmDanger("Remove from library",
                "Remove " + missing.size() + (missing.size() == 1 ? " entry" : " entries")
                        + "? Tags and history are kept, so if a file turns up in the library folder again, its image comes back as it was.",
                "Remove")) return;
        AsyncTask.runDb(() -> missing.forEach(r -> ImageTrash.delete(library, r)),
                () -> Dialogs.info("Done", "Removed from the library."),
                err -> Dialogs.error("Could not remove entries", err.getMessage()));
    }

    private Card buildArchiveSection(LibraryService library, Stage stage) {
        return new Card("Backup",
                "Back up, move, or merge libraries.").add(
                new FormRow("Archive",
                        AppButton.secondary("Export library…", () -> exportLibrary(library, stage)),
                        AppButton.secondary("Import archive…", () -> importArchive(library, stage))));
    }

    private Card buildDangerSection(LibraryService library, ConfigStore configStore, AppConfig config) {
        return new Card("Delete library",
                "Permanently deletes all images, tags, playlists, presets, and history, and resets settings. Gesturtle then closes.")
                .add(new FormRow("", AppButton.danger("Delete library…", () -> deleteLibrary(library, configStore, config)).icon("trash")));
    }

    /**
     * Two steps on purpose: a summary of what goes, then typing DELETE. The
     * deletion runs as the last job on the DB thread, so nothing still queued
     * there can touch the database after it's closed and removed.
     */
    private void deleteLibrary(LibraryService library, ConfigStore configStore, AppConfig config) {
        AsyncTask.runDb(() -> library.images().count(new Filter.All()), imageCount -> {
            Path root = library.root();
            boolean confirmed = Dialogs.confirmDanger("Delete library",
                    "Permanently delete " + imageCount + (imageCount == 1 ? " image" : " images")
                            + " and everything else in\n" + root
                            + "\n\nThis skips the " + com.gesturtle.library.SystemTrash.NAME + " and can't be undone. Settings are reset. Exports are kept.",
                    "Delete library");
            if (!confirmed) return;
            boolean typed = Dialogs.prompt("Delete library", "Type DELETE to confirm", "")
                    .map("DELETE"::equals).orElse(false);
            if (!typed) return;

            AsyncTask.runDb(() -> {
                library.close();
                try {
                    LibraryService.deleteLibraryFiles(library.ref());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return null;
            }, ignored -> {
                config.resetToDefaults();
                configStore.save(config);
                Dialogs.info("Library deleted", "Gesturtle will now close.");
                Platform.exit();
            }, err -> {
                // The library is already closed, so carrying on would hit a dead connection; reset and exit anyway.
                config.resetToDefaults();
                configStore.save(config);
                Dialogs.error("Could not delete everything",
                        "Some files could not be removed: " + err.getMessage()
                                + "\n\nSettings were reset. Gesturtle will close. Delete the folder by hand:\n" + root);
                Platform.exit();
            });
        }, err -> Dialogs.error("Could not read library", err.getMessage()));
    }

    private void exportLibrary(LibraryService library, Stage stage) {
        boolean includeHistory = Dialogs.ask("Export options", "Include practice history?", "Include", "Skip");
        boolean includeThumbnails = Dialogs.ask("Export options",
                "Include thumbnails? They regenerate on import anyway.", "Include", "Skip");

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export library archive");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Gesturtle archive", "*.zip"));
        chooser.setInitialFileName("gesturtle-export.zip");
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;
        Path zipPath = file.toPath();

        AsyncTask.runDb(() -> {
            try {
                ArchiveExporter.export(library, zipPath, new ArchiveExporter.Options(includeHistory, includeThumbnails));
                return null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, ignored -> Dialogs.info("Export complete", "Library exported to\n" + zipPath),
                err -> Dialogs.error("Export failed", err.getMessage()));
    }

    private void importArchive(LibraryService library, Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import library archive");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Gesturtle archive", "*.zip"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;
        Path zipPath = file.toPath();

        AsyncTask.runDb(() -> {
            try {
                return ArchiveImporter.dryRun(library, zipPath);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, summary -> {
            String message = count(summary.newImages(), "new image") + ", " + summary.alreadyPresentImages()
                    + " already present, " + count(summary.newTags(), "new tag") + ", "
                    + count(summary.totalPlaylists(), "playlist") + ", " + count(summary.totalPresets(), "preset")
                    + ".\n\nImport now?";
            if (!Dialogs.confirm("Import archive", message, "Import")) return;
            applyImport(library, zipPath);
        }, err -> Dialogs.error("Could not read archive", err.getMessage()));
    }

    /** "1 playlist" / "2 playlists", for the archive summaries. */
    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private void applyImport(LibraryService library, Path zipPath) {
        AsyncTask.runDb(() -> {
            try {
                return ArchiveImporter.apply(library, zipPath);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, result -> Dialogs.info("Import complete",
                count(result.newImages(), "new image") + " imported, "
                        + count(result.taggedExisting(), "existing image") + " tagged, "
                        + count(result.newTagsCreated(), "new tag") + ", " + count(result.playlistsImported(), "playlist")
                        + ", " + count(result.presetsImported(), "preset") + "."),
                err -> Dialogs.error("Import failed", err.getMessage()));
    }

    /**
     * Desktop.open() shells out to the system file manager and blocks until
     * it returns, which on some Linux file managers can take seconds (or
     * hang); run it off the FX thread so it can't freeze the UI.
     */
    private void reveal(Path root) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Dialogs.info("Library folder", root.toString());
            return;
        }
        AsyncTask.runIo(() -> {
            try {
                Desktop.getDesktop().open(root.toFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return null;
        }, ignored -> {
        }, err -> Dialogs.error("Could not open file manager", root + "\n\n" + err.getMessage()));
    }

    private void changeLibrary(Stage stage, ConfigStore configStore, AppConfig config) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose a library folder");
        var chosen = chooser.showDialog(stage);
        if (chosen == null) return;
        config.noteLibraryUsed(chosen.getAbsolutePath());
        configStore.save(config);
        Dialogs.info("Library changed", "Restart Gesturtle to open \"" + chosen.getAbsolutePath() + "\".");
    }
}
