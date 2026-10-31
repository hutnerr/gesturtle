package com.gesturtle.ui.common;

import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;

/**
 * Eased, faster wheel scrolling for a ScrollPane. JavaFX's own moves about
 * 40px per wheel notch and jumps there in one frame, which reads as
 * stuttery and crawls through a long page. This glides toward a target that
 * each notch pushes further, so quick flicks add up instead of queueing.
 *
 * Only scrolls nothing inside the content consumed first (a table or text
 * area keeps its own scrolling), and a scrollbar drag or a programmatic
 * jump takes over from a glide mid-way.
 */
public final class SmoothScroll {
    /** How quickly the glide closes on its target; smaller is snappier. */
    private static final double TIME_CONSTANT_SECONDS = 0.07;

    private final ScrollPane scroll;
    private final double speed;
    private double target;
    private long lastFrame;
    private boolean gliding;
    private boolean moving;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            step(now);
        }
    };

    private SmoothScroll(ScrollPane scroll, double speed) {
        this.scroll = scroll;
        this.speed = speed;
    }

    /** speed scales the wheel's pixel delta (1 = JavaFX's distance per notch). */
    public static void install(ScrollPane scroll, double speed) {
        SmoothScroll smooth = new SmoothScroll(scroll, speed);
        Node content = scroll.getContent();
        content.addEventHandler(ScrollEvent.SCROLL, smooth::onScroll);
        scroll.vvalueProperty().addListener((o, a, b) -> {
            if (!smooth.moving) smooth.stop();
        });
    }

    private double range() {
        Node content = scroll.getContent();
        return Math.max(0, content.getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight());
    }

    private void onScroll(ScrollEvent e) {
        if (e.getDeltaY() == 0) return;
        double range = range();
        if (range <= 0) return;
        if (!gliding) {
            target = scroll.getVvalue() * range;
            lastFrame = 0;
            gliding = true;
            timer.start();
        }
        target = clamp(target - e.getDeltaY() * speed, range);
        e.consume();
    }

    private void step(long now) {
        double range = range();
        if (range <= 0) {
            stop();
            return;
        }
        double dt = lastFrame == 0 ? 1.0 / 120 : (now - lastFrame) / 1e9;
        lastFrame = now;
        target = clamp(target, range);
        double current = scroll.getVvalue() * range;
        double next = current + (target - current) * (1 - Math.exp(-dt / TIME_CONSTANT_SECONDS));
        if (Math.abs(target - next) < 0.5) next = target;
        moving = true;
        scroll.setVvalue(next / range);
        moving = false;
        if (next == target) stop();
    }

    private void stop() {
        if (!gliding) return;
        gliding = false;
        timer.stop();
    }

    private static double clamp(double value, double range) {
        return Math.max(0, Math.min(range, value));
    }
}
