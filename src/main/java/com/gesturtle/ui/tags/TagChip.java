package com.gesturtle.ui.tags;

import com.gesturtle.model.Tag;
import com.gesturtle.model.TagColor;
import com.gesturtle.ui.components.Chip;
import javafx.scene.control.Tooltip;

/** A tag rendered as a {@link Chip}, optionally with a remove button. */
public class TagChip extends Chip {

    public TagChip(String text) {
        this(text, null);
    }

    public TagChip(String text, Runnable onRemove) {
        super(text, onRemove);
        getStyleClass().add("tag-chip");
    }

    /** Wears the tag's color, if it has one, and shows its description on hover. Returns this for chaining. */
    public TagChip styled(Tag tag) {
        TagColor color = TagColor.parse(tag.color());
        if (color != null) getStyleClass().addAll("tag-chip-colored", color.styleClass());
        if (tag.description() != null) Tooltip.install(this, new Tooltip(tag.description()));
        return this;
    }
}
