package com.gesturtle.ui.home;

import com.gesturtle.filter.Filter;
import com.gesturtle.filter.FilterSummary;
import com.gesturtle.library.LibraryService;
import com.gesturtle.model.Preset;
import com.gesturtle.model.Segment;
import com.gesturtle.model.SessionSpec;
import com.gesturtle.practice.SessionSnapshot;
import com.gesturtle.practice.SessionSpecCodec;
import com.gesturtle.ui.common.AsyncTask;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.common.Refreshable;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.Card;
import com.gesturtle.ui.components.EmptyState;
import com.gesturtle.ui.components.Page;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;

import java.util.List;
import java.util.function.Consumer;

/**
 * The launcher screen: a "Resume" card when a session was cut
 * short, one-click preset cards (pinned first, then most-recently-used), a
 * "Quick start" card for the last-used settings,
 * a small library stat line, and secondary links. Deliberately a launcher,
 * not a dashboard.
 */
public class HomeView extends Page implements Refreshable {
    private static final String LAST_SESSION_SPEC_KEY = "last_session_spec";

    private final LibraryService library;
    private final Consumer<SessionSpec> onStartSession;
    private final Consumer<Integer> onEditPreset;
    private final Consumer<SessionSnapshot> onResume;
    private final FlowPane cardsPane = new FlowPane(20, 20);

    public HomeView(LibraryService library, Consumer<SessionSpec> onStartSession, Consumer<Integer> onEditPreset,
                    Consumer<SessionSnapshot> onResume) {
        super("Home");
        this.library = library;
        this.onStartSession = onStartSession;
        this.onEditPreset = onEditPreset;
        this.onResume = onResume;
        getStyleClass().add("home-view");

        // Room for a hover-lifted card at the scroll area's edges.
        cardsPane.setPadding(new javafx.geometry.Insets(6));
        ScrollPane scroll = new ScrollPane(cardsPane);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        com.gesturtle.ui.common.SmoothScroll.install(scroll, 2);
        setBody(scroll);

        refresh();
    }

    @Override
    public void onShown() {
        refresh();
    }

    private record HomeData(int imageCount, int tagCount, int seenThisWeek, List<Preset> presets,
                             java.util.Map<Integer, SessionSpec> specs, String lastSessionJson, SessionSnapshot interrupted) {
    }

    /** The saved snapshot, minus images deleted since; null if there's none, it's unreadable, or nothing is left to show. */
    private SessionSnapshot loadInterrupted() {
        String json = library.settings().get(SessionSnapshot.SETTING_KEY).orElse("");
        if (json.isBlank()) return null;
        try {
            SessionSnapshot snapshot = SessionSnapshot.decode(json).withoutImages(id ->
                    library.images().findById(id).map(r -> !r.isDeleted()).orElse(false));
            return snapshot.cursor() < snapshot.total() ? snapshot : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void refresh() {
        AsyncTask.runDb(
                () -> {
                    List<Preset> presets = library.presets().listAll();
                    // Specs are read here, off the FX thread, rather than as each card is built.
                    java.util.Map<Integer, SessionSpec> specs = new java.util.HashMap<>();
                    for (Preset preset : presets) specs.put(preset.id(), library.presets().loadSpec(preset.id()));
                    return new HomeData(
                            library.images().count(new Filter.All()),
                            library.tags().listAll().size(),
                            library.images().countSeenSince(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000),
                            presets,
                            specs,
                            library.settings().get(LAST_SESSION_SPEC_KEY).orElse(null),
                            loadInterrupted());
                },
                this::render,
                err -> Dialogs.error("Could not load home screen", err.getMessage()));
    }

    private void render(HomeData data) {
        setSubtitle(data.imageCount() + " images  ·  " + data.tagCount() + " tags  ·  "
                + data.seenThisWeek() + " practiced this week");

        cardsPane.getChildren().clear();
        if (data.interrupted() != null) {
            cardsPane.getChildren().add(buildResumeCard(data.interrupted()));
        }
        if (data.lastSessionJson() != null) {
            cardsPane.getChildren().add(buildQuickStartCard(data.lastSessionJson()));
        }
        for (Preset preset : data.presets()) {
            cardsPane.getChildren().add(buildPresetCard(preset, data.specs().get(preset.id())));
        }
        if (data.presets().isEmpty() && data.lastSessionJson() == null) {
            EmptyState empty = new EmptyState("No presets yet", data.imageCount() == 0
                    ? "Import images, then start a session from Practice."
                    : "Save a session as a preset to start it here in one click.");
            empty.prefWidthProperty().bind(cardsPane.widthProperty());
            cardsPane.getChildren().add(empty);
        }
    }

    private PresetCard buildPresetCard(Preset preset, SessionSpec spec) {
        return new PresetCard(preset.name(), summarize(spec), preset.pinned(),
                () -> onStartSession.accept(spec),
                () -> {
                    library.presets().setPinned(preset.id(), !preset.pinned());
                    refresh();
                },
                () -> onEditPreset.accept(preset.id()),
                () -> {
                    if (Dialogs.confirmDanger("Delete preset", "Delete \"" + preset.name() + "\"?", "Delete")) {
                        library.presets().delete(preset.id());
                        refresh();
                    }
                });
    }

    private PresetCard buildQuickStartCard(String lastSessionJson) {
        SessionSpec spec = SessionSpecCodec.decode(lastSessionJson);
        PresetCard card = new PresetCard("Quick start", summarize(spec) + "\nLast used", false,
                () -> onStartSession.accept(spec), null, null, null);
        card.accent();
        return card;
    }

    private Card buildResumeCard(SessionSnapshot snapshot) {
        String when = new java.text.SimpleDateFormat("EEE, MMM d 'at' h:mm a").format(new java.util.Date(snapshot.savedAt()));
        javafx.scene.control.Label summary = new javafx.scene.control.Label(
                "Image " + (snapshot.cursor() + 1) + " of " + snapshot.total() + " · " + summarize(snapshot.spec())
                        + "\nInterrupted " + when);
        summary.getStyleClass().add("card-description");
        summary.setWrapText(true);
        summary.setMaxHeight(Double.MAX_VALUE);
        summary.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        javafx.scene.layout.VBox.setVgrow(summary, javafx.scene.layout.Priority.ALWAYS);
        AppButton resume = AppButton.primary("Resume", () -> onResume.accept(snapshot)).fill().trailingIcon("arrow-right");
        AppButton discard = AppButton.icon("close", "Discard this session", () -> {
            if (!Dialogs.confirm("Discard session", "Forget this interrupted session? The practice you did is still counted.", "Discard")) return;
            AsyncTask.runDb(() -> library.settings().set(SessionSnapshot.SETTING_KEY, ""), this::refresh,
                    err -> Dialogs.error("Could not discard session", err.getMessage()));
        });
        javafx.scene.layout.HBox actions = new javafx.scene.layout.HBox(new com.gesturtle.ui.components.Spacer(), discard);
        Card card = new Card("Resume session").add(summary, resume, actions);
        card.accent();
        card.setSpacing(10);
        card.setPrefWidth(PresetCard.CARD_WIDTH);
        card.setMinWidth(PresetCard.CARD_WIDTH);
        card.setMaxWidth(PresetCard.CARD_WIDTH);
        card.setPrefHeight(PresetCard.CARD_HEIGHT);
        card.setMinHeight(PresetCard.CARD_HEIGHT);
        return card;
    }

    private String summarize(SessionSpec spec) {
        if (spec.segments().size() != 1) {
            return spec.segments().size() + " segments";
        }
        Segment segment = spec.segments().get(0);
        String source = switch (segment.sourceKind()) {
            case FILTER -> FilterSummary.describe(segment.filter(), id -> library.tags().get(id).map(t -> t.name()).orElse("?"));
            case PLAYLIST -> "playlist";
            case VIEW -> "saved view";
        };
        String count = segment.itemCount() == null ? "unlimited" : segment.itemCount() + " images";
        String timing = segment.isTimed() ? segment.secondsPerImage() + "s/image" : "untimed";
        return source + " · " + count + " · " + timing;
    }
}
