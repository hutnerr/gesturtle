package com.gesturtle.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;

/**
 * A horizontal bar of controls with a left group, an optional centered group,
 * and a right group. {@link #divider()} draws a thin vertical rule between
 * clusters of related tools. Used anywhere a screen needs a row of actions
 * docked above or below its content (gallery, practice, image detail).
 *
 * When the bar is narrower than its contents, it first drops collapsible
 * buttons to icon-only, then folds overflowable tools, last first, into a
 * "⋯" menu, so the bar stays one row and never forces the page wider than
 * the window.
 */
public class Toolbar extends HBox {
    private final HBox left = new HBox(8);
    private final HBox center = new HBox(8);
    private final HBox right = new HBox(8);
    private final List<Labeled> collapsible = new ArrayList<>();
    private final List<ButtonBase> overflowable = new ArrayList<>();
    private final MenuButton overflowButton = new MenuButton();
    /** Width needed with every label showing, measured on first layout; 0 until then. */
    private double fullWidth;
    /** Labels dropped to icons, and dividers hidden, to save room. */
    private boolean compact;

    public Toolbar() {
        getStyleClass().add("app-toolbar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(12);
        setPadding(new Insets(12, 20, 12, 20));
        for (HBox group : new HBox[]{left, center, right}) {
            group.setAlignment(Pos.CENTER_LEFT);
            group.setMinWidth(USE_PREF_SIZE);
        }
        // The bar fits itself to whatever width it's given (below), so it must never demand more.
        setMinWidth(0);
        getChildren().addAll(left, new Spacer(), center, new Spacer(), right);
        widthProperty().addListener((o, a, w) -> fit(w.doubleValue()));

        overflowButton.setGraphic(new Icon("more", 18));
        overflowButton.getStyleClass().addAll("btn", "btn-ghost", "btn-icon", "toolbar-overflow");
        overflowButton.setTooltip(new Tooltip("More tools"));
        overflowButton.setOnShowing(e -> fillOverflowMenu());
        overflowButton.setVisible(false);
        overflowButton.setManaged(false);
    }

    public Toolbar left(Node... nodes) {
        left.getChildren().addAll(nodes);
        return this;
    }

    public Toolbar center(Node... nodes) {
        center.getChildren().addAll(nodes);
        return this;
    }

    public Toolbar right(Node... nodes) {
        right.getChildren().addAll(nodes);
        return this;
    }

    /**
     * Buttons that drop to icon-only when the bar is too narrow for every
     * label (a half-screen window), keeping their label as a tooltip. Dividers
     * hide too while collapsed.
     */
    public Toolbar collapsible(Labeled... nodes) {
        for (Labeled node : nodes) {
            if (node.getTooltip() == null && node.getText() != null) node.setTooltip(new Tooltip(node.getText()));
            collapsible.add(node);
        }
        return this;
    }

    /**
     * Tools, already added to the bar, that can move into the "⋯" menu when
     * there isn't room for them, the last one first. The menu button sits
     * just after them. Pick tools the screen can do without at a glance (the
     * image viewers pass their flip/rotate/filter buttons), not navigation.
     */
    public Toolbar overflowable(List<? extends Node> nodes) {
        Node last = null;
        for (Node node : nodes) {
            if (node instanceof ButtonBase button) {
                overflowable.add(button);
                last = node;
            }
        }
        if (last != null && last.getParent() instanceof HBox group && !group.getChildren().contains(overflowButton)) {
            group.getChildren().add(group.getChildren().indexOf(last) + 1, overflowButton);
        }
        return this;
    }

    private void fit(double width) {
        if (width <= 0) return;
        if (!collapsible.isEmpty()) {
            // Labels like Pause/Resume change length, so leave some slack before collapsing.
            if (fullWidth == 0) fullWidth = prefWidth(-1) + 16;
            setCompact(width < fullWidth);
        }
        if (!overflowable.isEmpty()) {
            for (ButtonBase b : overflowable) show(b, true);
            show(overflowButton, false);
            tidyDividers();
            for (int i = overflowable.size() - 1; i >= 0 && prefWidth(-1) > width; i--) {
                show(overflowable.get(i), false);
                show(overflowButton, true);
                tidyDividers();
            }
        }
    }

    /**
     * A divider shows only between two visible controls: not at a group's
     * edge and not next to another divider, so folding a whole cluster into
     * "⋯" takes its divider with it. A compact bar with no "⋯" menu drops
     * its dividers too, to save their room; one with the menu keeps them,
     * since the menu already makes the room and the groups still help.
     */
    private void tidyDividers() {
        for (HBox group : new HBox[]{left, center, right}) {
            List<Node> children = group.getChildren();
            Node pending = null;
            boolean controlBefore = false;
            for (Node child : children) {
                if (child.getStyleClass().contains("toolbar-divider")) {
                    show(child, false);
                    if (controlBefore && !(compact && overflowable.isEmpty())) pending = child;
                    controlBefore = false;
                } else if (child.isManaged()) {
                    if (pending != null) show(pending, true);
                    pending = null;
                    controlBefore = true;
                }
            }
        }
    }

    private void setCompact(boolean compact) {
        this.compact = compact;
        for (Labeled node : collapsible) node.setContentDisplay(compact ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
        // The dividers' spacing is the next-biggest saving once labels are gone.
        tidyDividers();
    }

    private static void show(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** Rebuilt each time it opens, so a toggle's check mark matches its current state (a key may have flipped it). */
    private void fillOverflowMenu() {
        overflowButton.getItems().clear();
        for (ButtonBase b : overflowable) {
            if (b.isManaged()) continue;
            String label = b.getTooltip() != null ? b.getTooltip().getText() : b.getText();
            Node icon = b.getGraphic() instanceof Icon i ? new Icon(i.getStyleClass().stream()
                    .filter(c -> c.startsWith("icon-") && !c.equals("icon-shape") && !c.equals("icon-line"))
                    .map(c -> c.substring(5)).findFirst().orElse("more"), 16) : null;
            MenuItem item;
            if (b instanceof ToggleButton t) {
                CheckMenuItem check = new CheckMenuItem(label, icon);
                check.setSelected(t.isSelected());
                item = check;
            } else {
                item = new MenuItem(label, icon);
            }
            // Quiet: the button it fires makes the sound.
            item.getStyleClass().add(com.gesturtle.ui.common.UiSounds.QUIET);
            item.setOnAction(e -> b.fire());
            overflowButton.getItems().add(item);
        }
    }

    /** Transparent variant, for toolbars sitting directly on a page instead of docked to an edge. */
    public Toolbar plain() {
        getStyleClass().add("app-toolbar-plain");
        setPadding(Insets.EMPTY);
        return this;
    }

    public static Region divider() {
        Region rule = new Region();
        rule.getStyleClass().add("toolbar-divider");
        return rule;
    }
}
