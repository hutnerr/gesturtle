package com.gesturtle.ui.components;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.geometry.Orientation;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A scrolling, virtualized grid of image tiles, built to stay
 * smooth at 10k+ images. Columns share the width evenly, each at least the
 * tile size wide. In {@link Layout#SQUARE} every tile is a square of the
 * column width; in {@link Layout#MASONRY} each tile is as tall as its
 * picture needs and goes into the shortest column, so columns line up but
 * rows don't.
 *
 * Every tile's position is computed up front (cheap arithmetic, even for
 * thousands), but only tiles within about a screen of the viewport ever have
 * a Node built. A tile that stays in range keeps its Node while scrolling,
 * so a click or double-click never lands on a Node that was swapped out
 * mid-gesture. Tiles that are {@link Resizable} also keep their Node through
 * a window resize or a tile-size change, being moved and resized in place.
 */
public class TileGrid<T> extends StackPane {
    public enum Layout { SQUARE, MASONRY }

    /** Room around the tiles so a hover-grown one isn't cut off at the edge. */
    private static final double EDGE = 6;
    private static final double SCROLLBAR_ROOM = 14;
    /** The scrollbar thumb never gets shorter than this, however many images there are. */
    private static final double MIN_THUMB = 48;

    private final Pane canvas = new Pane();
    private final ScrollPane scroll = new ScrollPane(canvas);
    /**
     * Our own bar in place of the ScrollPane's: JavaFX shrinks a thumb in
     * proportion to the content without any minimum, so a few thousand images
     * left a 4px dot. This one is sized here (see updateScrollBar).
     */
    private final ScrollBar vbar = new ScrollBar();
    private final Function<T, Object> keyOf;
    /** Builds a tile for an item at the given width and height. */
    private final TileFactory<T> factory;
    /** A MASONRY tile's height for an item at a column width. */
    private BiFunction<T, Double, Double> heightFor = (item, width) -> width;
    private final Map<Object, Node> nodes = new HashMap<>();
    private List<T> items = List.of();
    private double[] xs = new double[0], ys = new double[0], ws = new double[0], hs = new double[0];
    private Layout layout = Layout.SQUARE;
    private double tileSize = 200;
    private double gap = 14;
    private double laidOutWidth = -1;
    private Node placeholder;

    @FunctionalInterface
    public interface TileFactory<T> {
        Node build(T item, double width, double height);
    }

    /** A tile that can take a new size in place; others are rebuilt when their size changes. */
    public interface Resizable {
        void setTileSize(double width, double height);
    }

    public TileGrid(Function<T, Object> keyOf, TileFactory<T> factory) {
        this.keyOf = keyOf;
        this.factory = factory;
        getStyleClass().add("tile-grid");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("tile-grid-scroll");
        scroll.setFocusTraversable(false);
        canvas.setPadding(new Insets(EDGE));
        // The grid fits whatever width it's given; its tiles' positions mustn't feed back into how wide it asks to be.
        canvas.setMinWidth(0);
        canvas.setPrefWidth(0);
        scroll.setMinWidth(0);
        setMinWidth(0);
        vbar.setOrientation(Orientation.VERTICAL);
        vbar.setMin(0);
        vbar.setMax(1);
        vbar.valueProperty().bindBidirectional(scroll.vvalueProperty());
        vbar.heightProperty().addListener((o, a, b) -> updateScrollBar());
        HBox body = new HBox(scroll, vbar);
        HBox.setHgrow(scroll, Priority.ALWAYS);
        getChildren().add(body);

        scroll.viewportBoundsProperty().addListener((o, a, b) -> {
            if (b.getWidth() != laidOutWidth) relayout();
            else refreshVisible();
            updateScrollBar();
        });
        scroll.vvalueProperty().addListener((o, a, b) -> refreshVisible());
        // Faster than ScrollPane's default, which crawls through a tall grid.
        com.gesturtle.ui.common.SmoothScroll.install(scroll, 2.5);
    }

    /** Replaces the items, keeping the scroll position. */
    public void setItems(List<T> newItems) {
        items = new ArrayList<>(newItems);
        clearNodes();
        relayout();
        if (placeholder != null) {
            placeholder.setVisible(items.isEmpty());
        }
    }

    /** Swaps one item's data (e.g. its favorite flag) without rebuilding its tile, which updated itself. */
    public void replaceItem(int index, T item) {
        if (index >= 0 && index < items.size()) items.set(index, item);
    }

    public void setLayout(Layout layout, double tileSize, BiFunction<T, Double, Double> heightFor) {
        this.layout = layout;
        this.tileSize = tileSize;
        if (heightFor != null) this.heightFor = heightFor;
        clearNodes();
        relayout();
    }

    /** Changes only the tile size, resizing tiles in place where it can (e.g. while a size slider drags). */
    public void setTileSize(double tileSize) {
        if (tileSize == this.tileSize) return;
        this.tileSize = tileSize;
        relayout();
    }

    public void setGap(double gap) {
        this.gap = gap;
    }

    public void setPlaceholder(Node node) {
        if (placeholder != null) getChildren().remove(placeholder);
        placeholder = node;
        node.setVisible(items.isEmpty());
        getChildren().add(node);
    }

    /** The tile now built for an item, if it's in range; null otherwise. */
    public Node nodeFor(Object key) {
        return nodes.get(key);
    }

    public ScrollPane scrollPane() {
        return scroll;
    }

    public void scrollToTop() {
        scroll.setVvalue(0);
    }

    private void clearNodes() {
        nodes.clear();
        canvas.getChildren().clear();
    }

    /** Recomputes every tile's position for the current width, then builds the ones in range. */
    private void relayout() {
        double width = scroll.getViewportBounds().getWidth();
        if (width <= 0) width = getWidth() - SCROLLBAR_ROOM;
        if (width <= 0) return;
        laidOutWidth = width;
        double usable = width - EDGE * 2;
        int columns = Math.max(1, (int) ((usable + gap) / (tileSize + gap)));
        double columnWidth = Math.floor((usable - gap * (columns - 1)) / columns);

        int n = items.size();
        xs = new double[n];
        ys = new double[n];
        ws = new double[n];
        hs = new double[n];
        double[] columnBottoms = new double[columns];
        for (int i = 0; i < n; i++) {
            int column;
            double height;
            if (layout == Layout.SQUARE) {
                column = i % columns;
                height = columnWidth;
            } else {
                column = 0;
                for (int c = 1; c < columns; c++) {
                    if (columnBottoms[c] < columnBottoms[column] - 0.5) column = c;
                }
                height = Math.round(heightFor.apply(items.get(i), columnWidth));
            }
            xs[i] = EDGE + column * (columnWidth + gap);
            ys[i] = EDGE + columnBottoms[column];
            ws[i] = columnWidth;
            hs[i] = height;
            columnBottoms[column] += height + gap;
            if (layout == Layout.SQUARE && column == columns - 1) {
                // Square rows stay level: every column moves down together.
                java.util.Arrays.fill(columnBottoms, columnBottoms[column]);
            }
        }
        double contentHeight = 0;
        for (double bottom : columnBottoms) contentHeight = Math.max(contentHeight, bottom);
        canvas.setPrefHeight(contentHeight + EDGE * 2 - (n > 0 ? gap : 0));
        canvas.setMinHeight(canvas.getPrefHeight());
        refreshVisible();
        updateScrollBar();
    }

    /** Thumb in proportion to how much shows, but never below MIN_THUMB; a click in the track pages by a screen. */
    private void updateScrollBar() {
        double viewport = scroll.getViewportBounds().getHeight();
        double content = canvas.getPrefHeight();
        double track = vbar.getHeight();
        boolean scrolls = content > viewport && viewport > 0;
        vbar.setDisable(!scrolls);
        vbar.setOpacity(scrolls ? 1 : 0);
        if (!scrolls || track <= 0) {
            vbar.setVisibleAmount(1);
            return;
        }
        vbar.setVisibleAmount(Math.min(1, Math.max(viewport / content, MIN_THUMB / track)));
        double range = content - viewport;
        vbar.setBlockIncrement(viewport / range);
        vbar.setUnitIncrement(120 / range);
    }

    /** Builds tiles that came into range and drops ones that left it. */
    private void refreshVisible() {
        if (xs.length != items.size()) return;
        double viewportHeight = scroll.getViewportBounds().getHeight();
        double contentHeight = canvas.getPrefHeight();
        double top = scroll.getVvalue() * Math.max(0, contentHeight - viewportHeight);
        double from = top - viewportHeight;
        double to = top + viewportHeight * 2;

        Set<Object> wanted = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            if (ys[i] + hs[i] < from || ys[i] > to) continue;
            T item = items.get(i);
            Object key = keyOf.apply(item);
            wanted.add(key);
            Node existing = nodes.get(key);
            if (existing != null && !fitTo(existing, i)) {
                canvas.getChildren().remove(existing);
                nodes.remove(key);
                existing = null;
            }
            if (existing == null) {
                Node node = factory.build(item, ws[i], hs[i]);
                node.relocate(xs[i], ys[i]);
                node.setManaged(false);
                nodes.put(key, node);
                canvas.getChildren().add(node);
                if (node instanceof javafx.scene.layout.Region region) region.resize(ws[i], hs[i]);
            }
        }
        nodes.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey())) return false;
            canvas.getChildren().remove(entry.getValue());
            return true;
        });
    }

    /** Moves a built tile to item i's slot; false if it can't take that slot's size and must be rebuilt. */
    private boolean fitTo(Node node, int i) {
        if (node.getLayoutBounds().getWidth() != ws[i] || node.getLayoutBounds().getHeight() != hs[i]) {
            if (!(node instanceof Resizable resizable)) return false;
            resizable.setTileSize(ws[i], hs[i]);
            if (node instanceof javafx.scene.layout.Region region) region.resize(ws[i], hs[i]);
        }
        node.relocate(xs[i], ys[i]);
        return true;
    }
}
