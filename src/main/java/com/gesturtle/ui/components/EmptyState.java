package com.gesturtle.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** The centered "nothing here yet" message shown in place of an empty list, grid, or table. */
public class EmptyState extends VBox {
    private final Label titleLabel = new Label();
    private final Label messageLabel = new Label();

    public EmptyState(String title, String message, Node... actions) {
        getStyleClass().add("empty-state");
        setAlignment(Pos.CENTER);
        setSpacing(8);
        setPadding(new Insets(48, 24, 48, 24));

        titleLabel.getStyleClass().add("empty-state-title");
        getChildren().add(titleLabel);
        messageLabel.getStyleClass().add("empty-state-message");
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(440);
        messageLabel.setAlignment(Pos.CENTER);
        messageLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        getChildren().add(messageLabel);
        setText(title, message);
        if (actions.length > 0) {
            VBox.setMargin(actions[0], new Insets(8, 0, 0, 0));
            getChildren().addAll(actions);
        }
    }

    /** Changes what it says (a message of null hides that line). */
    public void setText(String title, String message) {
        titleLabel.setText(title);
        messageLabel.setText(message == null ? "" : message);
        messageLabel.setVisible(message != null);
        messageLabel.setManaged(message != null);
    }
}
