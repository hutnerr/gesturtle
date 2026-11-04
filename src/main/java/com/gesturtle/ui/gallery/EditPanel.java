package com.gesturtle.ui.gallery;

import com.gesturtle.ui.playlist.PlaylistChecklist;
import com.gesturtle.ui.tags.TagPicker;
import javafx.geometry.Insets;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.RowConstraints;

/**
 * The tag tree over the playlist checklist, docked beside a ViewerScreen for
 * going through images one after another: tick, press →, tick. The Gallery's
 * Edit mode and the Import preview both use it. Only the layout is shared:
 * the Gallery writes each tick to the library straight away, while Import
 * keeps it on the staged file until the import runs, so each owner wires up
 * the picker and checklist itself.
 */
public class EditPanel extends GridPane {
    /** The same width as the detail view's info sidebar, so switching between them doesn't move the image. */
    public static final double WIDTH = 340;

    public EditPanel(TagPicker tagPicker, PlaylistChecklist playlists) {
        getStyleClass().add("detail-sidebar");
        setPadding(new Insets(20));
        setVgap(16);
        setPrefWidth(WIDTH);
        setMinWidth(WIDTH);
        setMaxWidth(WIDTH);
        addColumn(0, tagPicker, playlists);
        // Tags over playlists, 2:1: tagging is most of the work, playlists the odd extra.
        RowConstraints tagsRow = new RowConstraints();
        tagsRow.setPercentHeight(200.0 / 3);
        RowConstraints playlistsRow = new RowConstraints();
        playlistsRow.setPercentHeight(100.0 / 3);
        getRowConstraints().addAll(tagsRow, playlistsRow);
        ColumnConstraints column = new ColumnConstraints();
        column.setPercentWidth(100);
        getColumnConstraints().add(column);
    }
}
