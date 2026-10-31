package com.gesturtle.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * One labeled line of a form: a fixed-width label column on the left and the
 * control(s) on the right, with an optional muted hint underneath the
 * controls. The fixed label width is what lines every form in the app up on
 * the same vertical edge. In a narrow window the label column gives up some
 * of its width and the controls wrap onto more lines, rather than pushing
 * the page wider than the window.
 */
public class FormRow extends HBox {
    private static final double LABEL_WIDTH = 190;
    private static final double COMPACT_LABEL_WIDTH = 130;
    private static final double MIN_LABEL_WIDTH = 110;

    private final Label labelNode;
    private final FlowPane controls = new FlowPane(12, 8);
    private final VBox right = new VBox(6, controls);

    public FormRow(String label, Node... controlNodes) {
        getStyleClass().add("form-row");
        setSpacing(16);
        setAlignment(Pos.TOP_LEFT);

        labelNode = new Label(label);
        labelNode.getStyleClass().add("form-label");
        labelNode.setMinWidth(MIN_LABEL_WIDTH);
        labelNode.setPrefWidth(LABEL_WIDTH);
        labelNode.setWrapText(true);
        // Nudge the label down so its baseline sits level with a 44px control beside it.
        labelNode.setPadding(new Insets(10, 0, 0, 0));

        controls.setAlignment(Pos.CENTER_LEFT);
        // Wide enough that the controls' preferred layout is one line; they wrap only when actually squeezed.
        controls.setPrefWrapLength(2000);
        controls.getChildren().addAll(controlNodes);
        HBox.setHgrow(right, javafx.scene.layout.Priority.ALWAYS);
        getChildren().addAll(labelNode, right);
    }

    /** A narrower label column, for forms in a card that shares its row with another. */
    public FormRow compact() {
        labelNode.setPrefWidth(COMPACT_LABEL_WIDTH);
        return this;
    }

    /**
     * A fixed label column width, for a card whose labels run longer than compact fits on one line.
     * Also the minimum: a squeezed row would otherwise shrink the label back to MIN_LABEL_WIDTH and
     * wrap it anyway, so here the controls give up the space instead.
     */
    public FormRow labelWidth(double width) {
        labelNode.setMinWidth(width);
        labelNode.setPrefWidth(width);
        return this;
    }

    public FormRow hint(String text) {
        Label hint = new Label(text);
        hint.getStyleClass().add("hint-label");
        hint.setWrapText(true);
        right.getChildren().add(hint);
        return this;
    }

    /** Adds a full-width node under the controls (e.g. a nested filter builder). */
    public FormRow below(Node node) {
        right.getChildren().add(node);
        return this;
    }

    /** Shows/hides the whole row and collapses its space when hidden. */
    /** Animated: the row grows or collapses into place (see Motion.setShown). */
    public void setShown(boolean shown) {
        com.gesturtle.ui.common.Motion.setShown(this, shown);
    }
}
