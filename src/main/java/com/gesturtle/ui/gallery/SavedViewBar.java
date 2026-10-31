package com.gesturtle.ui.gallery;

import com.gesturtle.ui.common.Motion;
import com.gesturtle.filter.Filter;
import com.gesturtle.filter.FilterCodec;
import com.gesturtle.library.LibraryService;
import com.gesturtle.model.SavedView;
import com.gesturtle.ui.common.AsyncTask;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.components.AppButton;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Saved Gallery queries ("views", as in ServiceNow): pick one to load its
 * conditions into the filter bar, save the current filter as a new view, or
 * update/rename/delete the one that's selected. Editing the filter after
 * picking a view marks it modified until it's updated or another is picked.
 * One view can be the default, which the Gallery opens on; "All images"
 * stays in the list either way. The built-in views (SavedView.BUILT_INS)
 * sit under it unless Settings turns them off.
 */
public class SavedViewBar extends HBox {
    private static final String ALL_IMAGES = "All images";
    /** Library setting: the default view's id, or blank for All images. */
    private static final String DEFAULT_VIEW_KEY = "gallery_default_view";

    private final LibraryService library;
    private final FilterBar filterBar;
    private final ComboBox<String> choice = new ComboBox<>();
    private final Label modifiedLabel = new Label("Modified");
    private final AppButton updateButton = AppButton.secondary("Update view", this::updateSelected).small().icon("bookmark");
    private final AppButton renameButton = AppButton.icon("pencil", "Rename view", this::renameSelected);
    private final AppButton deleteButton = AppButton.icon("trash", "Delete view", this::deleteSelected);
    private final Label defaultLabel = new Label("Default");
    private final AppButton defaultButton = AppButton.icon("home", "Open the Gallery on this view", this::makeDefault);
    private List<SavedView> views = List.of();
    private boolean showBuiltIns = true;
    /** Set once the first listing (which opens the default view) is in, so refresh() can't race it. */
    private boolean listed;
    /** The view the Gallery opens on; null for All images. */
    private Integer defaultViewId;
    /** The selected view's filter as the filter bar rebuilt it, so "modified" isn't fooled by normalization. */
    private String baseline;
    private boolean suppressLoad;

    public SavedViewBar(LibraryService library, FilterBar filterBar) {
        this.library = library;
        this.filterBar = filterBar;
        getStyleClass().add("saved-view-bar");
        setSpacing(6);
        setAlignment(Pos.CENTER_LEFT);

        Label label = new Label("View");
        label.getStyleClass().add("muted-label");
        choice.setMinWidth(220);
        // The open list marks the default view; the closed box shows just the name, with the Default label beside it.
        refreshCells();
        choice.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                setText(empty ? null : name);
            }
        });
        choice.getSelectionModel().selectedItemProperty().addListener((o, was, name) -> {
            if (!suppressLoad && name != null) load(name);
        });
        modifiedLabel.getStyleClass().add("hint-label");
        defaultLabel.getStyleClass().add("hint-label");
        defaultLabel.setTooltip(new javafx.scene.control.Tooltip("The Gallery opens on this view"));
        modifiedLabel.setTooltip(new javafx.scene.control.Tooltip("The filter differs from the saved view. Update it, or save a new one."));
        updateButton.setTooltip(new javafx.scene.control.Tooltip("Save the current filter into this view"));

        getChildren().addAll(label, choice, defaultLabel, modifiedLabel, updateButton, renameButton, deleteButton,
                defaultButton, AppButton.icon("bookmark", "Save the current filter as a new view", this::saveAsNew));
        reload(null, true);
    }

    /** Re-reads the view list (e.g. after Settings turned the built-in views on or off), keeping the selection. */
    public void refresh() {
        if (listed) reload(choice.getValue());
    }

    /** Call with every filter change, so the modified marker and Update button stay current. */
    public void filterChanged(Filter filter) {
        boolean modified = selected().isPresent() && !FilterCodec.encode(filter).equals(baseline);
        Motion.setShown(modifiedLabel, modified);
        Motion.setShown(updateButton, modified && !isBuiltIn(selected()));
    }

    /** A row in the open list: the view's name, and "Default" after the one the Gallery opens on. */
    private class ViewCell extends ListCell<String> {
        private final Label name = new Label();
        private final Label tag = new Label("Default");
        private final HBox row = new HBox(10, name, tag);

        ViewCell() {
            tag.getStyleClass().add("hint-label");
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            name.setText(item);
            tag.setVisible(isDefault(item));
            tag.setManaged(tag.isVisible());
            setGraphic(row);
        }
    }

    /** A new cell factory makes the open list rebuild its rows, so the Default tag moves. */
    private void refreshCells() {
        choice.setCellFactory(list -> new ViewCell());
    }

    private boolean isDefault(String name) {
        if (ALL_IMAGES.equals(name)) return defaultViewId == null;
        return allViews().stream().anyMatch(v -> v.name().equals(name) && Objects.equals(v.id(), defaultViewId));
    }

    private Optional<SavedView> selected() {
        String name = choice.getValue();
        return allViews().stream().filter(v -> v.name().equals(name)).findFirst();
    }

    private List<SavedView> allViews() {
        List<SavedView> all = new java.util.ArrayList<>(showBuiltIns ? SavedView.BUILT_INS : List.of());
        all.addAll(views);
        return all;
    }

    private static boolean isBuiltIn(Optional<SavedView> view) {
        return view.map(SavedView::isBuiltIn).orElse(false);
    }

    private void load(String name) {
        Filter filter = selected().map(SavedView::filter).orElse(new Filter.All());
        filterBar.loadFilter(filter);
        baseline = FilterCodec.encode(filterBar.buildFilter());
        syncButtons();
    }

    private void reload(String selectName) {
        reload(selectName, false);
    }

    private record Listing(List<SavedView> views, Integer defaultViewId, boolean showBuiltIns) {
    }

    /**
     * Re-reads the view list and selects selectName (or "All images") without
     * reloading the filter bar. With openDefault, selects the default view
     * instead and loads it, which is how the Gallery opens.
     */
    private void reload(String selectName, boolean openDefault) {
        AsyncTask.runDb(() -> new Listing(library.savedViews().listAll(), library.settings().get(DEFAULT_VIEW_KEY)
                .filter(v -> !v.isBlank()).map(Integer::valueOf).orElse(null),
                SavedView.builtInsShown(library.settings().get(SavedView.BUILT_INS_SETTING).orElse(null))), listing -> {
            views = listing.views();
            showBuiltIns = listing.showBuiltIns();
            listed = true;
            defaultViewId = allViews().stream().anyMatch(v -> Objects.equals(v.id(), listing.defaultViewId())) ? listing.defaultViewId() : null;
            String name = selectName;
            if (openDefault) {
                name = allViews().stream().filter(v -> defaultViewId != null && v.id() == defaultViewId)
                        .map(SavedView::name).findFirst().orElse(null);
            }
            suppressLoad = true;
            List<String> names = new java.util.ArrayList<>(List.of(ALL_IMAGES));
            allViews().forEach(v -> {
                if (!names.contains(v.name())) names.add(v.name());
            });
            // Cleared first: re-selecting the view that was already selected is otherwise no change to the
            // ComboBox, so its button cell, blanked while the items were swapped, never redraws.
            choice.getSelectionModel().clearSelection();
            choice.getItems().setAll(names);
            choice.getSelectionModel().select(name != null && choice.getItems().contains(name) ? name : ALL_IMAGES);
            suppressLoad = false;
            refreshCells();
            if (openDefault && selected().isPresent()) {
                load(choice.getValue());
            } else {
                syncButtons();
            }
        }, err -> Dialogs.error("Could not load views", err.getMessage()));
    }

    private void syncButtons() {
        boolean hasView = selected().isPresent() && !isBuiltIn(selected());
        boolean isDefault = Objects.equals(selected().map(SavedView::id).orElse(null), defaultViewId);
        Motion.setShown(renameButton, hasView);
        Motion.setShown(deleteButton, hasView);
        Motion.setShown(defaultLabel, selected().isPresent() && isDefault);
        Motion.setShown(defaultButton, !isDefault);
        filterChanged(filterBar.buildFilter());
    }

    /** Makes the selected view (or All images) the one the Gallery opens on. */
    private void makeDefault() {
        Integer id = selected().map(SavedView::id).orElse(null);
        AsyncTask.runDb(() -> library.settings().set(DEFAULT_VIEW_KEY, id == null ? "" : String.valueOf(id)), () -> {
            defaultViewId = id;
            refreshCells();
            syncButtons();
        }, err -> Dialogs.error("Could not set the default view", err.getMessage()));
    }

    private void saveAsNew() {
        String suggested = selected().filter(v -> v.id() > 0).map(SavedView::name).orElse("My view");
        Dialogs.prompt("Save view", "View name", suggested).ifPresent(name -> {
            Filter filter = filterBar.buildFilter();
            AsyncTask.runDb(() -> library.savedViews().create(name, filter), id -> {
                com.gesturtle.ui.common.Sounds.play(com.gesturtle.ui.common.Sounds.Sound.CONFIRM);
                baseline = FilterCodec.encode(filter);
                reload(name);
            }, err -> Dialogs.error("Could not save view", err.getMessage()));
        });
    }

    private void updateSelected() {
        selected().filter(v -> v.id() > 0).ifPresent(view -> {
            Filter filter = filterBar.buildFilter();
            AsyncTask.runDb(() -> library.savedViews().updateFilter(view.id(), filter), () -> {
                baseline = FilterCodec.encode(filter);
                reload(view.name());
            }, err -> Dialogs.error("Could not update view", err.getMessage()));
        });
    }

    private void renameSelected() {
        selected().filter(v -> v.id() > 0).ifPresent(view -> Dialogs.prompt("Rename view", "View name", view.name()).ifPresent(name ->
                AsyncTask.runDb(() -> library.savedViews().rename(view.id(), name), () -> reload(name),
                        err -> Dialogs.error("Could not rename view", err.getMessage()))));
    }

    private void deleteSelected() {
        selected().filter(v -> v.id() > 0).ifPresent(view -> {
            if (!Dialogs.confirmDanger("Delete view", "Delete view \"" + view.name() + "\"? Presets using it will have no images for that segment.", "Delete")) return;
            AsyncTask.runDb(() -> {
                library.savedViews().delete(view.id());
                if (Objects.equals(view.id(), defaultViewId)) library.settings().set(DEFAULT_VIEW_KEY, "");
            }, () -> reload(null),
                    err -> Dialogs.error("Could not delete view", err.getMessage()));
        });
    }
}
