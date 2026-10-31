package com.gesturtle.ui.components;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Group;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A faint contour-line pattern (resources/patterns/topography.svg, a 600px
 * repeating tile) drawn behind a few spots for flavor: whole pages (showing
 * between their cards) and the nav sidebar. It is an unmanaged, mouse-transparent first
 * child of its owner, sized to cover it, so it never affects layout.
 *
 * The lines get "topography-line", so style.css sets their color. A
 * per-place variant class ("topography-" + variant) lets style.css tune each
 * spot.
 *
 * {@link #SHOWN} and {@link #OPACITY} are the Appearance settings; every
 * instance follows them live.
 */
public final class Topography extends Region {
    public static final BooleanProperty SHOWN = new SimpleBooleanProperty(true);
    /** How strongly the lines show, as their opacity. */
    public static final DoubleProperty OPACITY = new SimpleDoubleProperty(0.06);
    public static final double MIN_OPACITY = 0.01;
    public static final double MAX_OPACITY = 0.2;

    private static final double TILE = 600;
    private static final Pattern D_ATTR = Pattern.compile("\\sd=\"([^\"]+)\"");
    private static String pathData;

    private final Group lines = new Group();
    private final Rectangle clip = new Rectangle();

    private Topography(String variant) {
        getStyleClass().addAll("topography", "topography-" + variant);
        lines.getStyleClass().add("topography-lines");
        lines.setManaged(false);
        lines.opacityProperty().bind(OPACITY);
        getChildren().add(lines);
        setClip(clip);
        setMouseTransparent(true);
        setFocusTraversable(false);
        visibleProperty().bind(SHOWN);
        // Tiles are only built once it's shown, so turning it off costs nothing.
        visibleProperty().addListener((o, was, shown) -> requestLayout());
    }

    /** Puts a pattern behind everything else in owner, covering it. */
    public static Topography behind(Pane owner, String variant) {
        Topography topography = new Topography(variant);
        topography.setManaged(false);
        owner.getChildren().add(0, topography);
        owner.layoutBoundsProperty().addListener((o, was, bounds) ->
                topography.resizeRelocate(0, 0, bounds.getWidth(), bounds.getHeight()));
        return topography;
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        clip.setWidth(w);
        clip.setHeight(h);
        if (!isVisible() || w <= 0 || h <= 0) return;
        int needed = (int) Math.ceil(w / TILE) * (int) Math.ceil(h / TILE);
        while (lines.getChildren().size() < needed) {
            SVGPath tile = new SVGPath();
            tile.setContent(pathData());
            tile.getStyleClass().add("topography-line");
            // Drawn from a bitmap: the path is too detailed to rasterize every time something over it
            // repaints, and a row growing or collapsing repaints the whole page each frame.
            tile.setCache(true);
            lines.getChildren().add(tile);
        }
        int columns = (int) Math.ceil(w / TILE);
        for (int i = 0; i < lines.getChildren().size(); i++) {
            var tile = lines.getChildren().get(i);
            tile.setVisible(i < needed);
            tile.setLayoutX((i % columns) * TILE);
            tile.setLayoutY((i / columns) * TILE);
        }
    }

    private static String pathData() {
        if (pathData != null) return pathData;
        try (InputStream in = Topography.class.getResourceAsStream("/patterns/topography.svg")) {
            if (in == null) throw new IllegalStateException("Missing /patterns/topography.svg");
            Matcher d = D_ATTR.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!d.find()) throw new IllegalStateException("topography.svg has no path");
            pathData = d.group(1);
            return pathData;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
