package com.gesturtle.ui.imports;

import com.gesturtle.library.LibraryService;
import com.gesturtle.model.Playlist;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.Card;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Every playlist with a checkbox, for the import preview's side panel: the
 * playlists the file showing will be added to on import. Like TagPicker's
 * setOnChange, it reports each tick as it happens rather than on OK, and a
 * playlist created from its field starts ticked.
 */
final class PlaylistChecklist extends VBox {
    private final LibraryService library;
    private final ListView<Playlist> list = new ListView<>();
    private final TextField newPlaylistField = new TextField();
    private final Set<Integer> checked = new LinkedHashSet<>();
    private Runnable onChange;

    PlaylistChecklist(LibraryService library) {
        this.library = library;
        setSpacing(12);

        Label heading = new Label("Add to playlists");
        heading.getStyleClass().add("muted-label");

        list.setCellFactory(lv -> new CheckCell());
        list.setPlaceholder(new Label("No playlists yet"));
        list.setPrefHeight(160);
        Card listCard = new Card().flush().add(list);
        VBox.setVgrow(listCard, Priority.ALWAYS);

        newPlaylistField.setPromptText("New playlist");
        newPlaylistField.setOnAction(e -> createPlaylist());
        HBox.setHgrow(newPlaylistField, Priority.ALWAYS);
        HBox addRow = new HBox(8, newPlaylistField, AppButton.secondary("Add", this::createPlaylist).icon("plus"));
        addRow.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(heading, listCard, addRow);
        list.getItems().setAll(library.playlists().listAll());
    }

    void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    /** The caller setting the state, so onChange isn't called. */
    void setChecked(Set<Integer> playlistIds) {
        checked.clear();
        checked.addAll(playlistIds);
        list.refresh();
    }

    Set<Integer> checked() {
        return Set.copyOf(checked);
    }

    private void toggle(Playlist playlist, boolean on) {
        if (on) checked.add(playlist.id());
        else checked.remove(playlist.id());
        if (onChange != null) onChange.run();
    }

    private void createPlaylist() {
        String name = newPlaylistField.getText() == null ? "" : newPlaylistField.getText().trim();
        if (name.isEmpty()) return;
        try {
            int id = library.playlists().create(name);
            newPlaylistField.clear();
            list.getItems().setAll(library.playlists().listAll());
            checked.add(id);
            list.refresh();
            if (onChange != null) onChange.run();
        } catch (RuntimeException ex) {
            Dialogs.error("Could not create playlist", ex.getMessage());
        }
    }

    /** The whole row toggles, not just the box, like the tag tree's rows. */
    private final class CheckCell extends ListCell<Playlist> {
        private final CheckBox box = new CheckBox();

        CheckCell() {
            box.setOnAction(e -> {
                if (getItem() != null) toggle(getItem(), box.isSelected());
            });
            setOnMouseClicked(e -> {
                if (getItem() == null || e.getButton() != javafx.scene.input.MouseButton.PRIMARY
                        || e.getTarget() == box || box.isHover()) return;
                // fire() rather than setting it, so it's a click like one on the box: it makes the interface sound.
                box.fire();
            });
        }

        @Override
        protected void updateItem(Playlist playlist, boolean empty) {
            super.updateItem(playlist, empty);
            if (empty || playlist == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            box.setText(playlist.name());
            box.setSelected(checked.contains(playlist.id()));
            setText(null);
            setGraphic(box);
        }
    }
}
