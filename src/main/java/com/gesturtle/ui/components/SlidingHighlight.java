package com.gesturtle.ui.components;

import com.gesturtle.ui.common.Motion;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * A selection background that slides (with a small overshoot) from the old
 * selected child to the new one, instead of each child painting its own
 * :selected background. Used by the nav sidebar and segmented controls.
 *
 * It is an unmanaged, mouse-transparent child placed first, so it draws
 * behind the options. The owner calls layout() from its layoutChildren() so
 * the highlight follows resizes, and moveTo() when the selection changes.
 */
public final class SlidingHighlight {
    private static final Duration SLIDE = Duration.millis(260);

    private final Region region = new Region();
    private final DoubleProperty x = new SimpleDoubleProperty();
    private final DoubleProperty y = new SimpleDoubleProperty();
    private final DoubleProperty width = new SimpleDoubleProperty();
    private final DoubleProperty height = new SimpleDoubleProperty();
    private Region target;
    private Timeline slide;
    private boolean placed;

    public SlidingHighlight(Pane owner, String styleClass) {
        region.getStyleClass().add(styleClass);
        region.setManaged(false);
        region.setMouseTransparent(true);
        region.setVisible(false);
        owner.getChildren().add(0, region);
        Runnable apply = () -> region.resizeRelocate(x.get(), y.get(), width.get(), height.get());
        x.addListener((o, a, b) -> apply.run());
        y.addListener((o, a, b) -> apply.run());
        width.addListener((o, a, b) -> apply.run());
        height.addListener((o, a, b) -> apply.run());
    }

    /** Slides to target; jumps there instead if nothing has been placed yet (first show). */
    public void moveTo(Region newTarget) {
        boolean animate = placed && target != null && newTarget != null && newTarget != target
                && region.getScene() != null && region.isVisible();
        target = newTarget;
        if (newTarget == null) {
            region.setVisible(false);
            return;
        }
        if (!animate) {
            snap();
            return;
        }
        if (slide != null) slide.stop();
        slide = new Timeline(new KeyFrame(SLIDE,
                new KeyValue(x, newTarget.getLayoutX(), Motion.OVERSHOOT),
                new KeyValue(y, newTarget.getLayoutY(), Motion.OVERSHOOT),
                new KeyValue(width, newTarget.getWidth(), Motion.EASE_OUT),
                new KeyValue(height, newTarget.getHeight(), Motion.EASE_OUT)));
        // Settle on the target's final bounds: a selected segment turns bold, so it can be
        // a little wider than when the slide started.
        slide.setOnFinished(e -> {
            slide = null;
            snap();
        });
        slide.play();
    }

    /** Keeps the highlight on its target through resizes; leaves a running slide alone. */
    public void layout() {
        if (slide == null) snap();
    }

    private void snap() {
        if (target == null || target.getWidth() <= 0) return;
        x.set(target.getLayoutX());
        y.set(target.getLayoutY());
        width.set(target.getWidth());
        height.set(target.getHeight());
        region.setVisible(true);
        placed = true;
    }
}
