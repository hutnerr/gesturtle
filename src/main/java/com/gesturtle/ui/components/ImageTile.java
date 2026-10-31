package com.gesturtle.ui.components;

import com.gesturtle.ui.common.Motion;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.util.function.Consumer;

/**
 * One thumbnail in an image grid (Gallery, Playlists), in a card-like tile
 * with a selection ring, an optional favorite badge, and an optional corner
 * overlay. By default the whole picture is fitted inside the tile's padding
 * (never cropped, composition matters for reference images) with its own
 * corners rounded; in fill mode it covers the tile edge to edge instead,
 * cropping whatever overhangs. Tiles are square unless given a height.
 */
public class ImageTile extends StackPane implements TileGrid.Resizable {
    private static final double INSET = 10;
    private static final double RADIUS = 8;
    /** Fill mode keeps a sliver of tile around the picture, so the selection ring still shows. */
    private static final double FILL_INSET = 3;
    private static final double FILL_RADIUS = 11;

    /** The padding around the picture: wide when fitted, a sliver when filled. */
    public static double insetFor(boolean fill) {
        return fill ? FILL_INSET : INSET;
    }

    public ImageTile(Image thumbnail, double size) {
        this(thumbnail, size, size, false);
    }

    private final ImageView imageView;
    private final Rectangle clip;
    private Image thumbnail;
    private final boolean fill;
    private double innerW, innerH;

    public ImageTile(Image thumbnail, double width, double height, boolean fill) {
        this.thumbnail = thumbnail;
        this.fill = fill;
        getStyleClass().add("image-tile");
        if (fill) getStyleClass().add("image-tile-fill");

        double radius = fill ? FILL_RADIUS : RADIUS;
        imageView = new ImageView(thumbnail);
        imageView.setPreserveRatio(!fill);
        imageView.setSmooth(true);

        // Clip to the image's actual fitted size (not the box), so a
        // landscape or portrait picture gets rounded corners of its own.
        // Thumbnails decode in the background, so the size is only known once
        // the image reports its dimensions; until then, clip to the box.
        clip = new Rectangle();
        clip.setArcWidth(radius * 2);
        clip.setArcHeight(radius * 2);
        imageView.setClip(clip);
        watch(thumbnail);
        setTileSize(width, height);

        getChildren().add(imageView);
        Motion.hoverLift(this, 3);
    }

    /**
     * Resizes the tile in place, so a grid can follow a window resize or the
     * size slider without rebuilding (and re-decoding) every tile.
     */
    @Override
    public void setTileSize(double width, double height) {
        if (width == getPrefWidth() && height == getPrefHeight()) return;
        setPrefSize(width, height);
        setMinSize(width, height);
        setMaxSize(width, height);
        double inset = fill ? FILL_INSET : INSET;
        innerW = width - inset * 2;
        innerH = height - inset * 2;
        imageView.setFitWidth(innerW);
        imageView.setFitHeight(innerH);
        clip.setWidth(innerW);
        clip.setHeight(innerH);
        fit();
    }

    /**
     * Swaps in a thumbnail that finished decoding after the tile was built
     * (the Import page's cards, whose files aren't in the thumbnail cache).
     */
    public void setThumbnail(Image image) {
        thumbnail = image;
        imageView.setImage(image);
        imageView.setViewport(null);
        watch(image);
        fit();
    }

    private void watch(Image image) {
        if (image == null) return;
        image.widthProperty().addListener((o, a, b) -> { if (image == thumbnail) fit(); });
        image.heightProperty().addListener((o, a, b) -> { if (image == thumbnail) fit(); });
    }

    private void fit() {
        if (thumbnail == null) return;
        if (fill) cover(imageView, thumbnail, innerW, innerH);
        else fitClip(clip, thumbnail, innerW, innerH);
    }

    private static void fitClip(Rectangle clip, Image image, double boxW, double boxH) {
        if (image.getWidth() <= 0 || image.getHeight() <= 0) return;
        double scale = Math.min(boxW / image.getWidth(), boxH / image.getHeight());
        clip.setWidth(image.getWidth() * scale);
        clip.setHeight(image.getHeight() * scale);
    }

    /** Shows the centered part of the image that, scaled up, covers the whole box. */
    private static void cover(ImageView view, Image image, double boxW, double boxH) {
        if (image.getWidth() <= 0 || image.getHeight() <= 0) return;
        double scale = Math.max(boxW / image.getWidth(), boxH / image.getHeight());
        double w = boxW / scale;
        double h = boxH / scale;
        view.setViewport(new javafx.geometry.Rectangle2D((image.getWidth() - w) / 2, (image.getHeight() - h) / 2, w, h));
    }

    /** A read-only star badge, shown only on favorites. */
    public ImageTile favorite(boolean favorite) {
        if (!favorite) return this;
        Label badge = new Label(null, new Icon("favorite-filled", 15));
        badge.getStyleClass().addAll("image-tile-badge", "on");
        overlay(badge, Pos.TOP_RIGHT);
        return this;
    }

    /**
     * A clickable star in the corner: always shown on favorites, and on
     * hover otherwise (or always, with alwaysShown). Clicking flips it right
     * away and reports the new value; the press is consumed so it doesn't
     * also select the tile.
     */
    public ImageTile favoriteToggle(boolean favorite, boolean alwaysShown, Consumer<Boolean> onChange) {
        Label star = new Label();
        star.getStyleClass().addAll("image-tile-badge", "tile-favorite");
        if (alwaysShown) star.getStyleClass().add("always-shown");
        boolean[] state = {favorite};
        Runnable sync = () -> {
            star.setGraphic(new Icon(state[0] ? "favorite-filled" : "favorite", 15));
            star.getStyleClass().remove("on");
            if (state[0]) star.getStyleClass().add("on");
            star.setTooltip(new Tooltip(state[0] ? "Unfavorite" : "Favorite"));
        };
        sync.run();
        star.setOnMousePressed(e -> {
            e.consume();
            state[0] = !state[0];
            sync.run();
            if (state[0]) Motion.pop(star.getGraphic());
            com.gesturtle.ui.common.Sounds.play(state[0] ? com.gesturtle.ui.common.Sounds.Sound.FAVORITE
                    : com.gesturtle.ui.common.Sounds.Sound.UNFAVORITE);
            onChange.accept(state[0]);
        });
        overlay(star, Pos.TOP_RIGHT);
        return this;
    }

    /** Pins a node (a remove button, a badge…) to a corner, inside the tile's padding. */
    public ImageTile overlay(Node node, Pos position) {
        StackPane.setAlignment(node, position);
        StackPane.setMargin(node, new Insets(12));
        getChildren().add(node);
        return this;
    }

    public void setSelected(boolean selected) {
        if (selected) {
            if (!getStyleClass().contains("selected")) getStyleClass().add("selected");
        } else {
            getStyleClass().remove("selected");
        }
    }
}
