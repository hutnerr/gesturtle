package com.gesturtle.ui.home;

import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.Card;
import com.gesturtle.ui.components.Chip;
import com.gesturtle.ui.components.Spacer;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * One launcher card on the home screen: a saved preset or the
 * "Quick start" shortcut. Starting is an explicit button rather than a
 * whole-card click handler, so it never fights with the pin/edit/delete
 * buttons for the same mouse click.
 */
public class PresetCard extends Card {
    static final double CARD_WIDTH = 300;
    static final double CARD_HEIGHT = 230;
    /** An icon button's height, so a card without actions still reserves the row. */
    private static final double ACTIONS_HEIGHT = 38;

    public PresetCard(String name, String summary, boolean pinned, Runnable onStart,
                       Runnable onTogglePin, Runnable onEdit, Runnable onDelete) {
        interactive();
        com.gesturtle.ui.common.Motion.hoverLift(this, 3);
        setPrefWidth(CARD_WIDTH);
        setMinWidth(CARD_WIDTH);
        setMaxWidth(CARD_WIDTH);
        // Fixed, not content-derived, so every card in the row reports the same
        // preferred size to the FlowPane, names/summaries of different lengths
        // would otherwise make cards different heights. The summary label's
        // vgrow then soaks up whatever slack that leaves, pinning the button(s)
        // to the bottom edge consistently.
        setPrefHeight(CARD_HEIGHT);
        setMinHeight(CARD_HEIGHT);
        setSpacing(10);

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("card-title");
        nameLabel.setWrapText(true);
        HBox titleRow = new HBox(8, nameLabel, new Spacer());
        titleRow.setAlignment(Pos.CENTER_LEFT);
        if (pinned) titleRow.getChildren().add(new Chip("Pinned").neutral());

        Label summaryLabel = new Label(summary);
        summaryLabel.getStyleClass().add("card-description");
        summaryLabel.setWrapText(true);
        // A Label's own maxHeight defaults to its preferred height, which caps
        // vgrow at nothing to actually stretch into; without this, the VBox's
        // leftover space (from forcing a uniform card height above) goes
        // unclaimed at the bottom instead of pushing the button(s) down to it.
        summaryLabel.setMaxHeight(Double.MAX_VALUE);
        summaryLabel.setAlignment(Pos.TOP_LEFT);
        setVgrow(summaryLabel, Priority.ALWAYS);

        AppButton startButton = AppButton.primary("Start practice", onStart).fill().trailingIcon("arrow-right");
        add(titleRow, summaryLabel, startButton);

        // Every card gets the actions row, empty or not, so all the Start buttons sit at the same height.
        HBox actions = new HBox(4);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setMinHeight(ACTIONS_HEIGHT);
        if (onTogglePin != null) {
            AppButton pin = AppButton.icon("pin", pinned ? "Unpin" : "Pin to the front of Home", onTogglePin);
            if (pinned) pin.getStyleClass().add("btn-icon-active");
            actions.getChildren().add(pin);
        }
        if (onEdit != null) {
            actions.getChildren().add(AppButton.icon("pencil", "Edit preset", onEdit));
        }
        actions.getChildren().add(new Spacer());
        if (onDelete != null) {
            AppButton deleteButton = AppButton.icon("trash", "Delete preset", onDelete);
            deleteButton.getStyleClass().add("btn-ghost-danger");
            actions.getChildren().add(deleteButton);
        }
        add(actions);
    }
}
