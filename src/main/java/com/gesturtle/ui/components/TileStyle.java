package com.gesturtle.ui.components;

import com.gesturtle.db.dao.SettingDao;
import com.gesturtle.model.ImageRecord;

/**
 * How image cards look wherever there's a grid of them (Gallery, Playlists,
 * Import, session review): square or as tall as their picture, and the
 * picture fitted with padding or cropped to fill. Each area keeps its own
 * (library settings keyed by area), set from that area's own toggles, since
 * a dense masonry Gallery and a tidy square import review want different
 * things.
 */
public record TileStyle(TileGrid.Layout layout, boolean fill) {
    public static final String GALLERY = "gallery";
    public static final String PLAYLISTS = "playlists";
    public static final String IMPORT = "import";
    public static final String REVIEW = "review";
    /** Very tall or wide pictures are clamped, so one panorama or strip can't swallow a masonry column. */
    private static final double MIN_ASPECT = 0.4, MAX_ASPECT = 2.5;

    /** An area's style: masonry and fill until the user picks otherwise; save() writes both, so a choice sticks. */
    public static TileStyle load(SettingDao settings, String area) {
        return new TileStyle(
                "square".equals(settings.get(layoutKey(area)).orElse(null)) ? TileGrid.Layout.SQUARE : TileGrid.Layout.MASONRY,
                !"fit".equals(settings.get(fillKey(area)).orElse(null)));
    }

    public void save(SettingDao settings, String area) {
        settings.set(layoutKey(area), layout == TileGrid.Layout.MASONRY ? "masonry" : "square");
        settings.set(fillKey(area), fill ? "fill" : "fit");
    }

    private static String layoutKey(String area) {
        return area + "_tile_layout";
    }

    private static String fillKey(String area) {
        return area + "_tile_fill";
    }

    public TileStyle withLayout(TileGrid.Layout newLayout) {
        return new TileStyle(newLayout, fill);
    }

    public TileStyle withFill(boolean newFill) {
        return new TileStyle(layout, newFill);
    }

    /** A card's height at a width: square, or (masonry) its picture's aspect plus the tile's padding. */
    public double heightFor(ImageRecord record, double width) {
        return heightFor(record.width(), record.height(), width);
    }

    /** heightFor from a picture's pixel size; 0 (not known yet) gives a square card. */
    public double heightFor(int pictureWidth, int pictureHeight, double width) {
        if (layout == TileGrid.Layout.SQUARE) return width;
        double aspect = pictureWidth > 0 && pictureHeight > 0 ? (double) pictureHeight / pictureWidth : 1;
        aspect = Math.max(MIN_ASPECT, Math.min(MAX_ASPECT, aspect));
        double inset = ImageTile.insetFor(fill);
        return Math.round((width - inset * 2) * aspect + inset * 2);
    }
}
