package com.gesturtle.ui.common;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.CacheHint;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

/**
 * The app's motion vocabulary, so every screen moves the same way: short,
 * eased, with a small overshoot on things that "land" (selection
 * highlights, popped stars, released buttons).
 *
 * Each helper stops the previous animation it started on the same node, so
 * rapid input never stacks transitions. Big or clipped nodes (pages, tiles)
 * are bitmap-cached while they fade, slide, or scale, so each frame is one
 * texture draw instead of a re-render of everything inside them.
 */
public final class Motion {
    /** Fast start, gentle stop. For fades and slides that shouldn't bounce. */
    public static final Interpolator EASE_OUT = Interpolator.SPLINE(0.2, 0.8, 0.2, 1.0);

    /** Ease-out-back: runs a little past the target, then settles. For things that land. */
    public static final Interpolator OVERSHOOT = new Interpolator() {
        private static final double C1 = 1.5, C3 = C1 + 1;

        @Override
        protected double curve(double t) {
            double u = t - 1;
            return 1 + C3 * u * u * u + C1 * u * u;
        }
    };

    private static final String RUNNING_KEY = "motion.running";

    private Motion() {
    }

    /** Squishes on press and springs back on release, like a physical button. */
    public static void pressFeedback(Node node) {
        node.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> scaleTo(node, 0.95, 70, EASE_OUT));
        node.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> scaleTo(node, 1.0, 220, OVERSHOOT));
    }

    /**
     * Rises a few pixels while hovered (tiles, cards) and dips back on press.
     * Moved rather than scaled: any scale other than 1 resamples the node's
     * pictures and text, which reads soft on a 100% display. Every resting
     * offset is a whole pixel, so the node stays pixel-sharp once it lands.
     */
    public static void hoverLift(Node node, double lift) {
        node.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> translateYTo(node, -lift, 130, EASE_OUT));
        node.addEventHandler(MouseEvent.MOUSE_EXITED, e -> translateYTo(node, 0, 160, EASE_OUT));
        node.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> translateYTo(node, 0, 60, EASE_OUT));
        node.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> translateYTo(node, node.isHover() ? -lift : 0, 220, OVERSHOOT));
    }

    /** A quick swell and settle, for a state that just turned on (a starred favorite). */
    public static void pop(Node node) {
        ScaleTransition up = new ScaleTransition(Duration.millis(90), node);
        up.setToX(1.35);
        up.setToY(1.35);
        up.setInterpolator(EASE_OUT);
        ScaleTransition down = new ScaleTransition(Duration.millis(260), node);
        down.setToX(1.0);
        down.setToY(1.0);
        down.setInterpolator(OVERSHOOT);
        play(node, new SequentialTransition(up, down));
    }

    /** A smaller pop for repeated signals (the last seconds of a timer). */
    public static void pulse(Node node) {
        node.setScaleX(1.18);
        node.setScaleY(1.18);
        scaleTo(node, 1.0, 320, OVERSHOOT);
    }

    /** Fades in while rising from riseBy pixels below. For pages and overlays arriving. */
    public static void enter(Node node, double riseBy, double millis) {
        node.setOpacity(0);
        node.setTranslateY(riseBy);
        FadeTransition fade = new FadeTransition(Duration.millis(millis), node);
        fade.setToValue(1);
        fade.setInterpolator(EASE_OUT);
        TranslateTransition rise = new TranslateTransition(Duration.millis(millis), node);
        rise.setToY(0);
        rise.setInterpolator(EASE_OUT);
        play(node, new ParallelTransition(fade, rise), true);
    }

    /** Fades and grows in from fromScale. For a view that opens over another (the image viewer). */
    public static void zoomIn(Node node, double fromScale, double millis) {
        node.setOpacity(0);
        node.setScaleX(fromScale);
        node.setScaleY(fromScale);
        FadeTransition fade = new FadeTransition(Duration.millis(millis), node);
        fade.setToValue(1);
        fade.setInterpolator(EASE_OUT);
        ScaleTransition grow = new ScaleTransition(Duration.millis(millis), node);
        grow.setToX(1);
        grow.setToY(1);
        grow.setInterpolator(EASE_OUT);
        play(node, new ParallelTransition(fade, grow), true);
    }

    /** The reverse of zoomIn; runs onFinished once it's gone. */
    public static void zoomOut(Node node, double toScale, double millis, Runnable onFinished) {
        FadeTransition fade = new FadeTransition(Duration.millis(millis), node);
        fade.setToValue(0);
        fade.setInterpolator(EASE_OUT);
        ScaleTransition shrink = new ScaleTransition(Duration.millis(millis), node);
        shrink.setToX(toScale);
        shrink.setToY(toScale);
        shrink.setInterpolator(EASE_OUT);
        ParallelTransition both = new ParallelTransition(fade, shrink);
        both.setOnFinished(e -> {
            onFinished.run();
            node.setOpacity(1);
            node.setScaleX(1);
            node.setScaleY(1);
        });
        play(node, both, true);
    }

    public static void fadeIn(Node node, double millis) {
        node.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(millis), node);
        fade.setToValue(1);
        fade.setInterpolator(EASE_OUT);
        play(node, fade, true);
    }

    private static final String SHOWN_KEY = "motion.shown";
    private static final String REVEAL_KEY = "motion.reveal";
    private static final Duration REVEAL = Duration.millis(220);

    /**
     * Shows or hides a node in place of setVisible+setManaged, growing or
     * collapsing it (height in a vertical stack, width in a row) while it
     * fades, so the content around it slides instead of jumping. The gap
     * the parent's spacing leaves for it eases in and out too.
     *
     * Applies instantly while the node isn't on screen yet (e.g. during a
     * view's construction), so screens still open in their final state.
     */
    public static void setShown(Node node, boolean show) {
        Object recorded = node.getProperties().get(SHOWN_KEY);
        boolean wasShown = recorded instanceof Boolean b ? b : node.isVisible() && node.isManaged();
        node.getProperties().put(SHOWN_KEY, show);
        if (recorded != null && wasShown == show) return;
        if (node.getProperties().get(REVEAL_KEY) instanceof Reveal running) running.finishNow();
        // A Reveal records the node's sizes to restore later, so they must be its own, not a height ease's pins.
        if (node.getProperties().get(HEIGHT_KEY) instanceof HeightChange easing) easing.finishNow();

        if (!(node instanceof javafx.scene.layout.Region region) || !onScreen(node) || wasShown == show) {
            node.setVisible(show);
            node.setManaged(show);
            return;
        }
        new Reveal(region, show).play();
    }

    /**
     * Runs change (which rebuilds the region's content) and then eases the
     * region from its old height to its new one instead of snapping, e.g.
     * when a filter condition or queued segment is added or removed.
     */
    public static void animateHeightChange(javafx.scene.layout.Region region, Runnable change) {
        // A change arriving mid-ease (a quick second click) restarts from where the last one had got to.
        // Without this, the new ease took the old one's pinned height and clip for the region's own
        // sizes and restored those when done, leaving the region stuck at a stale height with its new
        // content clipped away.
        HeightChange running = region.getProperties().get(HEIGHT_KEY) instanceof HeightChange h ? h : null;
        if (running != null) running.finishNow();
        boolean animate = onScreen(region) && region.isVisible() && region.isManaged() && region.getHeight() > 0
                && !(region.getProperties().get(REVEAL_KEY) instanceof Reveal);
        double from = region.getHeight();
        change.run();
        if (!animate) return;
        double width = region.getWidth();
        double to = region.prefHeight(width);
        if (Math.abs(to - from) < 1) return;
        new HeightChange(region, from, to).play();
    }

    private static final String HEIGHT_KEY = "motion.height";

    /** One animateHeightChange ease; pins the region's height while it runs and restores its own sizes and clip after. */
    private static final class HeightChange {
        private final javafx.scene.layout.Region region;
        private final double minH, prefH, maxH;
        private final Node clip;
        private final javafx.animation.Timeline timeline;

        HeightChange(javafx.scene.layout.Region region, double from, double to) {
            this.region = region;
            minH = region.getMinHeight();
            prefH = region.getPrefHeight();
            maxH = region.getMaxHeight();
            clip = region.getClip();
            javafx.scene.shape.Rectangle mask = new javafx.scene.shape.Rectangle();
            mask.widthProperty().bind(region.widthProperty());
            mask.heightProperty().bind(region.heightProperty());
            region.setClip(mask);
            javafx.beans.property.DoubleProperty height = new javafx.beans.property.SimpleDoubleProperty(from);
            height.addListener((o, a, h) -> pin(h.doubleValue()));
            pin(from);
            timeline = new javafx.animation.Timeline(new javafx.animation.KeyFrame(REVEAL,
                    new javafx.animation.KeyValue(height, to, EASE_OUT)));
            timeline.setOnFinished(e -> finishNow());
            region.getProperties().put(HEIGHT_KEY, this);
        }

        void play() {
            timeline.play();
        }

        private void pin(double h) {
            region.setMinHeight(h);
            region.setPrefHeight(h);
            region.setMaxHeight(h);
        }

        void finishNow() {
            timeline.stop();
            region.getProperties().remove(HEIGHT_KEY, this);
            region.setMinHeight(minH);
            region.setPrefHeight(prefH);
            region.setMaxHeight(maxH);
            region.setClip(clip);
        }
    }

    private static boolean onScreen(Node node) {
        if (node.getScene() == null || node.getScene().getWindow() == null
                || !node.getScene().getWindow().isShowing()) return false;
        for (Node n = node.getParent(); n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
        }
        return node.getParent() instanceof javafx.scene.layout.Region p && p.getWidth() > 0;
    }

    /** One grow/collapse of a region; restores every size, margin, and clip override when done. */
    private static final class Reveal {
        private final javafx.scene.layout.Region region;
        private final boolean show;
        private final boolean horizontal;
        private final double spacing;
        private final double minW, prefW, maxW, minH, prefH, maxH;
        private final Node clip;
        private final javafx.geometry.Insets margin;
        private final javafx.animation.Timeline timeline;
        private final javafx.beans.property.DoubleProperty progress = new javafx.beans.property.SimpleDoubleProperty();

        Reveal(javafx.scene.layout.Region region, boolean show) {
            this.region = region;
            this.show = show;
            Node parent = region.getParent();
            horizontal = parent instanceof javafx.scene.layout.HBox
                    || parent instanceof javafx.scene.layout.FlowPane fp && fp.getOrientation() == javafx.geometry.Orientation.HORIZONTAL;
            spacing = parent instanceof javafx.scene.layout.VBox v ? v.getSpacing()
                    : parent instanceof javafx.scene.layout.HBox h ? h.getSpacing()
                    : parent instanceof javafx.scene.layout.FlowPane f ? f.getHgap() : 0;
            minW = region.getMinWidth();
            prefW = region.getPrefWidth();
            maxW = region.getMaxWidth();
            minH = region.getMinHeight();
            prefH = region.getPrefHeight();
            maxH = region.getMaxHeight();
            clip = region.getClip();
            margin = margin(region);

            region.setManaged(true);
            region.setVisible(true);
            double full;
            if (show) {
                full = horizontal ? region.prefWidth(-1) : region.prefHeight(contentWidth(parent));
            } else {
                full = horizontal ? region.getWidth() : region.getHeight();
            }
            javafx.scene.shape.Rectangle mask = new javafx.scene.shape.Rectangle();
            mask.widthProperty().bind(region.widthProperty());
            mask.heightProperty().bind(region.heightProperty());
            region.setClip(mask);

            progress.addListener((o, a, p) -> apply(full, p.doubleValue()));
            progress.set(show ? 0 : 1);
            apply(full, progress.get());
            timeline = new javafx.animation.Timeline(new javafx.animation.KeyFrame(REVEAL,
                    new javafx.animation.KeyValue(progress, show ? 1 : 0, EASE_OUT)));
            timeline.setOnFinished(e -> finishNow());
            region.getProperties().put(REVEAL_KEY, this);
        }

        void play() {
            timeline.play();
        }

        private void apply(double full, double p) {
            double size = full * p;
            if (horizontal) {
                region.setMinWidth(size);
                region.setPrefWidth(size);
                region.setMaxWidth(size);
            } else {
                region.setMinHeight(size);
                region.setPrefHeight(size);
                region.setMaxHeight(size);
            }
            region.setOpacity(p);
            // Pull the following sibling in by the spacing this node's gap would leave.
            double pull = -spacing * (1 - p);
            javafx.geometry.Insets base = margin == null ? javafx.geometry.Insets.EMPTY : margin;
            setMargin(region, horizontal
                    ? new javafx.geometry.Insets(base.getTop(), base.getRight() + pull, base.getBottom(), base.getLeft())
                    : new javafx.geometry.Insets(base.getTop(), base.getRight(), base.getBottom() + pull, base.getLeft()));
        }

        void finishNow() {
            timeline.stop();
            region.getProperties().remove(REVEAL_KEY);
            region.setMinWidth(minW);
            region.setPrefWidth(prefW);
            region.setMaxWidth(maxW);
            region.setMinHeight(minH);
            region.setPrefHeight(prefH);
            region.setMaxHeight(maxH);
            region.setClip(clip);
            region.setOpacity(1);
            setMargin(region, margin);
            region.setVisible(show);
            region.setManaged(show);
        }

        private static double contentWidth(Node parent) {
            if (parent instanceof javafx.scene.layout.Region p) {
                return Math.max(0, p.getWidth() - p.snappedLeftInset() - p.snappedRightInset());
            }
            return -1;
        }

        private static javafx.geometry.Insets margin(Node node) {
            Node parent = node.getParent();
            if (parent instanceof javafx.scene.layout.VBox) return javafx.scene.layout.VBox.getMargin(node);
            if (parent instanceof javafx.scene.layout.HBox) return javafx.scene.layout.HBox.getMargin(node);
            if (parent instanceof javafx.scene.layout.FlowPane) return javafx.scene.layout.FlowPane.getMargin(node);
            return null;
        }

        private static void setMargin(Node node, javafx.geometry.Insets insets) {
            Node parent = node.getParent();
            if (parent instanceof javafx.scene.layout.VBox) javafx.scene.layout.VBox.setMargin(node, insets);
            else if (parent instanceof javafx.scene.layout.HBox) javafx.scene.layout.HBox.setMargin(node, insets);
            else if (parent instanceof javafx.scene.layout.FlowPane) javafx.scene.layout.FlowPane.setMargin(node, insets);
        }
    }

    private static void scaleTo(Node node, double scale, double millis, Interpolator interpolator) {
        scaleTo(node, scale, millis, interpolator, false);
    }

    private static void scaleTo(Node node, double scale, double millis, Interpolator interpolator, boolean cache) {
        ScaleTransition t = new ScaleTransition(Duration.millis(millis), node);
        t.setToX(scale);
        t.setToY(scale);
        t.setInterpolator(interpolator);
        play(node, t, cache);
    }

    private static void translateYTo(Node node, double y, double millis, Interpolator interpolator) {
        TranslateTransition t = new TranslateTransition(Duration.millis(millis), node);
        t.setToY(y);
        t.setInterpolator(interpolator);
        play(node, t);
    }

    private static void play(Node node, Animation animation) {
        play(node, animation, false);
    }

    private static final String CACHE_KEY = "motion.cache";

    /** The node's own cache settings, put back once its animations stop. */
    private record CacheState(boolean cache, CacheHint hint) {
    }

    /**
     * cache bitmap-caches the node until the animation stops. Not for small
     * text (buttons): the scaled bitmap reads a little soft mid-press.
     */
    private static void play(Node node, Animation animation, boolean cache) {
        Object previous = node.getProperties().put(RUNNING_KEY, animation);
        if (previous instanceof Animation p) p.stop();
        if (cache) {
            if (!(node.getProperties().get(CACHE_KEY) instanceof CacheState)) {
                node.getProperties().put(CACHE_KEY, new CacheState(node.isCache(), node.getCacheHint()));
                node.setCache(true);
                node.setCacheHint(CacheHint.SPEED);
            }
            animation.statusProperty().addListener((o, was, status) -> {
                if (status == Animation.Status.STOPPED && node.getProperties().get(RUNNING_KEY) == animation) {
                    restoreCache(node);
                }
            });
        } else {
            restoreCache(node);
        }
        animation.play();
    }

    private static void restoreCache(Node node) {
        if (node.getProperties().remove(CACHE_KEY) instanceof CacheState state) {
            node.setCache(state.cache());
            node.setCacheHint(state.hint());
        }
    }
}
