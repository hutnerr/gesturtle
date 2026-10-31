package com.gesturtle.ui.components;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A pill-shaped row of mutually exclusive options: the app's replacement for
 * radio-button groups and Grid/List style toggles. Exactly one option is
 * always selected: clicking the active segment again leaves it selected
 * rather than clearing the group.
 */
public class SegmentedControl<T> extends HBox {
    private final ToggleGroup group = new ToggleGroup();
    private final Map<T, ToggleButton> segments = new LinkedHashMap<>();
    private final ObjectProperty<T> value = new SimpleObjectProperty<>();
    private final SlidingHighlight highlight = new SlidingHighlight(this, "segment-indicator");

    public SegmentedControl() {
        getStyleClass().add("segmented");
        setAlignment(Pos.CENTER_LEFT);
        setFillHeight(true);
        setMaxWidth(USE_PREF_SIZE);

        group.selectedToggleProperty().addListener((o, oldToggle, newToggle) -> {
            if (newToggle == null) {
                // Re-select instead of allowing "nothing selected".
                group.selectToggle(oldToggle);
                return;
            }
            @SuppressWarnings("unchecked")
            T selected = (T) newToggle.getUserData();
            value.set(selected);
        });
        value.addListener((o, a, b) -> {
            ToggleButton button = segments.get(b);
            if (button != null && !button.isSelected()) button.setSelected(true);
            highlight.moveTo(button);
        });
    }

    public SegmentedControl<T> option(T optionValue, String label) {
        ToggleButton button = new ToggleButton(label);
        button.getStyleClass().add("segment");
        button.setUserData(optionValue);
        button.setToggleGroup(group);
        button.setFocusTraversable(false);
        button.setMinWidth(USE_PREF_SIZE);
        com.gesturtle.ui.common.Motion.pressFeedback(button);
        segments.put(optionValue, button);
        getChildren().add(button);
        if (value.get() == null) setValue(optionValue);
        return this;
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        highlight.layout();
    }

    /** An option with an icon before its label. */
    public SegmentedControl<T> option(T optionValue, String label, String icon) {
        option(optionValue, label);
        ToggleButton button = segments.get(optionValue);
        button.setGraphic(new Icon(icon, 15));
        button.setGraphicTextGap(8);
        return this;
    }

    /** An icon-only option; the tooltip carries its name. */
    public SegmentedControl<T> iconOption(T optionValue, String icon, String tooltip) {
        option(optionValue, null);
        ToggleButton button = segments.get(optionValue);
        button.setGraphic(new Icon(icon, 15));
        button.getStyleClass().add("segment-icon");
        button.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        return this;
    }

    /** Drops the track behind the options, for use straight on the page background (e.g. Grid/List). */
    public SegmentedControl<T> bare() {
        getStyleClass().add("segmented-bare");
        return this;
    }

    public T getValue() {
        return value.get();
    }

    public void setValue(T newValue) {
        value.set(newValue);
    }

    public ObjectProperty<T> valueProperty() {
        return value;
    }

    /** Runs whenever the selection changes (not for the initial value). */
    public SegmentedControl<T> onChange(Consumer<T> listener) {
        value.addListener((o, a, b) -> listener.accept(b));
        return this;
    }
}
