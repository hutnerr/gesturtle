package com.gesturtle.ui.practice;

import com.gesturtle.config.AppConfig;
import com.gesturtle.config.ConfigStore;
import com.gesturtle.library.LibraryService;
import com.gesturtle.model.SessionSpec;
import com.gesturtle.practice.SessionSnapshot;
import com.gesturtle.ui.common.Refreshable;
import javafx.scene.layout.StackPane;

import java.util.List;

/** Swaps between session setup and the running session. */
public class PracticeContainerView extends StackPane implements Refreshable {
    private final LibraryService library;
    private final AppConfig config;
    private final ConfigStore configStore;

    public PracticeContainerView(LibraryService library, AppConfig config, ConfigStore configStore) {
        this.library = library;
        this.config = config;
        this.configStore = configStore;
        showSetup();
    }

    /**
     * Only initializes a blank setup screen the first time this is ever
     * shown. Any other time, including right after showSetupWithPlaylist()
     * or showSetupForEdit() called shell.show("practice") to display what
     * they just built, there's already real content here (a configured
     * SessionSetupView or a running PracticeView), and resetting it on every
     * nav visit would both discard that pre-filled state and wipe an
     * in-progress (not-yet-started) form if the user just clicked away and back.
     */
    @Override
    public void onShown() {
        if (getChildren().isEmpty()) {
            showSetup();
        } else if (getChildren().get(0) instanceof SessionSetupView setup) {
            // Playlists and views may have changed elsewhere since (or Settings hid the built-in views).
            setup.refreshSources();
        }
    }

    /** Skips setup and starts running a SessionSpec directly. */
    public void startSpec(SessionSpec spec) {
        show(new PracticeView(library, config, configStore, spec, shown -> showReview(spec, shown)));
    }

    /** Picks an interrupted session back up where its snapshot left off. */
    public void resume(SessionSnapshot snapshot) {
        show(new PracticeView(library, config, configStore, snapshot, shown -> showReview(snapshot.spec(), shown)));
    }

    private void show(PracticeView view) {
        getChildren().setAll(view);
        // Without this, keyboard focus stays wherever it was (often the nav
        // sidebar, once the old setup form's focused button gets swapped out
        // of the scene); PracticeView's own arrow/space/etc. shortcuts never
        // receive those key presses, which instead toggle the nav buttons.
        view.requestFocus();
    }

    /** Opens setup with Playlist pre-selected as the source, so timing/order/count can still be chosen. */
    public void showSetupWithPlaylist(int playlistId) {
        showSetup(new SessionSetupView(library, this::startSpec, playlistId));
    }

    /** Opens setup pre-filled from an existing preset, with "Save as preset…" swapped for "Update preset" (Home page's "Edit" action). */
    public void showSetupForEdit(int presetId, SessionSpec spec) {
        showSetup(new SessionSetupView(library, this::startSpec, spec, presetId));
    }

    /** The end-of-session grid; skipped when nothing was shown (e.g. no matches) or turned off in Settings. */
    private void showReview(SessionSpec spec, List<Integer> shownImageIds) {
        if (shownImageIds.isEmpty() || config.skipSessionReview) {
            showSetup();
            return;
        }
        getChildren().setAll(new SessionReviewView(library, spec, shownImageIds, this::showSetup, this::startSpec));
    }

    private void showSetup() {
        showSetup(new SessionSetupView(library, this::startSpec));
    }

    private void showSetup(SessionSetupView setup) {
        setup.setOnReset(this::showSetup);
        getChildren().setAll(setup);
    }
}
