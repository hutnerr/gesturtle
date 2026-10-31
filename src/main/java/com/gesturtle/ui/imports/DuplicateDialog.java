package com.gesturtle.ui.imports;

import com.gesturtle.library.LibraryService;
import com.gesturtle.model.DuplicateAction;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.ImportCandidate;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Optional;

/**
 * The file-manager-style "this file already exists" prompt shown before an
 * import commits checked rows that are already in the library: Update,
 * Overwrite, or Skip, with "do this for the rest" so a presorted batch only
 * needs answering once.
 */
final class DuplicateDialog {
    record Choice(DuplicateAction action, boolean applyToRest) {
    }

    private static final ButtonType UPDATE = new ButtonType("Update", ButtonBar.ButtonData.YES);
    private static final ButtonType OVERWRITE = new ButtonType("Overwrite", ButtonBar.ButtonData.OTHER);
    private static final ButtonType SKIP = new ButtonType("Skip", ButtonBar.ButtonData.NO);

    private DuplicateDialog() {
    }

    /** remaining is how many more duplicates follow this one; empty means the user cancelled the whole import. */
    static Optional<Choice> ask(LibraryService library, ImportCandidate candidate, ImageRecord existing, int remaining) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Already in library");
        dialog.getDialogPane().getButtonTypes().addAll(UPDATE, OVERWRITE, SKIP, ButtonType.CANCEL);

        ImageView thumb = new ImageView(library.thumbnails().load(library.ref(), existing));
        thumb.setFitWidth(96);
        thumb.setFitHeight(96);
        thumb.setPreserveRatio(true);

        String where = existing.isDeleted() ? " (deleted, importing brings it back)" : "";
        String sameAs = candidate.fileName().equals(existing.originalName()) ? ""
                : " as \"" + existing.originalName() + "\"";
        Label message = new Label("\"" + candidate.fileName() + "\" is already in the library" + sameAs + where + ".");
        message.setWrapText(true);
        message.getStyleClass().add("section-title");
        Label explain = new Label("""
                Update: add this batch's tags.
                Overwrite: replace its tags and filename.
                Skip: leave it as is.""");
        explain.getStyleClass().add("muted-label");
        explain.setWrapText(true);
        VBox text = new VBox(10, message, explain);
        text.setPrefWidth(500);

        HBox body = new HBox(18, thumb, text);
        body.setAlignment(Pos.TOP_LEFT);
        VBox content = new VBox(16, body);
        CheckBox applyToRest = new CheckBox("Apply to the other " + remaining);
        if (remaining > 0) content.getChildren().add(applyToRest);
        dialog.getDialogPane().setContent(content);
        com.gesturtle.ui.common.Dialogs.prepare(dialog);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() == ButtonType.CANCEL) return Optional.empty();
        DuplicateAction action = result.get() == OVERWRITE ? DuplicateAction.OVERWRITE
                : result.get() == SKIP ? DuplicateAction.SKIP : DuplicateAction.UPDATE;
        return Optional.of(new Choice(action, applyToRest.isSelected()));
    }
}
