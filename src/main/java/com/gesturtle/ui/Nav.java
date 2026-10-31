package com.gesturtle.ui;

import com.gesturtle.ui.common.Motion;
import com.gesturtle.ui.components.Icon;
import com.gesturtle.ui.components.Logo;
import com.gesturtle.ui.components.SlidingHighlight;
import com.gesturtle.ui.components.Topography;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The app's left-hand navigation sidebar: one toggle button per top-level
 * section, with footer pages (Settings, Help) pinned to the bottom below a
 * growing spacer.
 */
public class Nav extends VBox {
    private final ToggleGroup group = new ToggleGroup();
    private final Map<String, ToggleButton> buttons = new LinkedHashMap<>();
    private final Region spacer = new Region();
    private final SlidingHighlight highlight;

    public Nav() {
        getStyleClass().add("nav");
        setSpacing(4);
        setPadding(new Insets(28, 14, 20, 14));
        highlight = new SlidingHighlight(this, "nav-indicator");
        Topography.behind(this, "nav");

        Logo mark = new Logo(2);
        Label title = new Label("Gesturtle");
        title.getStyleClass().add("nav-title");
        HBox brand = new HBox(12, mark, title);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setPadding(new Insets(0, 16, 28, 16));
        getChildren().add(brand);

        VBox.setVgrow(spacer, Priority.ALWAYS);
        getChildren().add(spacer);
    }

    public void addItem(String key, String label, String icon, Consumer<String> onSelect) {
        ToggleButton button = new ToggleButton(label, navIcon(icon));
        button.setToggleGroup(group);
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("nav-item");
        Motion.pressFeedback(button);
        button.setOnAction(e -> onSelect.accept(key));
        buttons.put(key, button);
        getChildren().add(getChildren().indexOf(spacer), button);
    }

    /** Adds a page, same as addItem, but pinned below the spacer (e.g. Settings, grouped with Help). */
    public void addFooterItem(String key, String label, String icon, Consumer<String> onSelect) {
        ToggleButton button = new ToggleButton(label, navIcon(icon));
        button.setToggleGroup(group);
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("nav-item");
        Motion.pressFeedback(button);
        button.setOnAction(e -> onSelect.accept(key));
        buttons.put(key, button);
        getChildren().add(button);
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        highlight.layout();
    }

    private static Icon navIcon(String name) {
        return new Icon(name, 20);
    }

    public void select(String key) {
        ToggleButton button = buttons.get(key);
        if (button != null) {
            group.selectToggle(button);
            highlight.moveTo(button);
        }
    }
}
