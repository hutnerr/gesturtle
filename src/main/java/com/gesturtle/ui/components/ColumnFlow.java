package com.gesturtle.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

import java.util.List;

/**
 * Lays its children out in as many equal columns as fit at least
 * {@code minColumnWidth} each, so a wide window gets two or three columns of
 * sections instead of one long, mostly empty one. By default each child goes
 * into the shortest column (a masonry of cards); {@link #columnsFirst()}
 * fills each column top to bottom before the next instead, which reads
 * better for lists (shortcuts). A child marked {@link #spanAll(Node)} takes
 * the full width, below everything before it.
 */
public class ColumnFlow extends Pane {
    private static final String SPAN_KEY = "column-flow-span";

    private final double minColumnWidth;
    private final int maxColumns;
    private double hgap = 20;
    private double vgap = 20;
    private boolean columnsFirst;
    private boolean fixedWidth;

    public ColumnFlow(double minColumnWidth, int maxColumns, Node... children) {
        this.minColumnWidth = minColumnWidth;
        this.maxColumns = maxColumns;
        getChildren().addAll(children);
    }

    public ColumnFlow gaps(double hgap, double vgap) {
        this.hgap = hgap;
        this.vgap = vgap;
        requestLayout();
        return this;
    }

    public ColumnFlow columnsFirst() {
        columnsFirst = true;
        requestLayout();
        return this;
    }

    /** Columns stay exactly minColumnWidth wide (for fixed-size cards) instead of sharing out the slack. */
    public ColumnFlow fixedWidth() {
        fixedWidth = true;
        requestLayout();
        return this;
    }

    /** Makes a child take the whole width, under everything added before it. */
    public static <N extends Node> N spanAll(N node) {
        node.getProperties().put(SPAN_KEY, true);
        return node;
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    /** Never squeezed shorter than its content, e.g. by a VBox making room for a table that wants it all. */
    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computeMinWidth(double height) {
        Insets in = getInsets();
        double widest = 0;
        for (Node child : managed()) widest = Math.max(widest, child.minWidth(-1));
        return in.getLeft() + in.getRight() + widest;
    }

    @Override
    protected double computePrefWidth(double height) {
        Insets in = getInsets();
        return in.getLeft() + in.getRight() + minColumnWidth;
    }

    @Override
    protected double computePrefHeight(double width) {
        if (width < 0) width = getWidth() > 0 ? getWidth() : computePrefWidth(-1);
        Insets in = getInsets();
        return in.getTop() + in.getBottom() + place(width - in.getLeft() - in.getRight(), false);
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        place(getWidth() - in.getLeft() - in.getRight(), true);
    }

    private List<Node> managed() {
        return getManagedChildren();
    }

    private int columnsFor(double width) {
        int columns = (int) ((width + hgap) / (minColumnWidth + hgap));
        return Math.max(1, Math.min(maxColumns, columns));
    }

    /** Positions every child (when apply) and returns the height used. */
    private double place(double width, boolean apply) {
        List<Node> children = managed();
        Insets in = getInsets();
        int columns = columnsFor(width);
        double columnWidth = fixedWidth ? minColumnWidth : (width - hgap * (columns - 1)) / columns;
        double[] bottoms = new double[columns];
        // Children in the current run (between spanning ones), for columnsFirst.
        int runStart = 0;
        for (int i = 0; i <= children.size(); i++) {
            boolean end = i == children.size();
            boolean span = !end && Boolean.TRUE.equals(children.get(i).getProperties().get(SPAN_KEY));
            if (!end && !span) continue;
            List<Node> run = children.subList(runStart, i);
            if (columnsFirst) {
                int perColumn = (int) Math.ceil(run.size() / (double) columns);
                for (int j = 0; j < run.size(); j++) {
                    int column = perColumn == 0 ? 0 : j / perColumn;
                    bottoms[column] = put(run.get(j), column, columnWidth, bottoms[column], apply, in);
                }
            } else {
                for (Node child : run) {
                    int column = 0;
                    for (int c = 1; c < columns; c++) if (bottoms[c] < bottoms[column]) column = c;
                    bottoms[column] = put(child, column, columnWidth, bottoms[column], apply, in);
                }
            }
            if (span) {
                double top = max(bottoms);
                Node child = children.get(i);
                double h = child.prefHeight(width);
                if (apply) child.resizeRelocate(in.getLeft(), in.getTop() + top, width, h);
                java.util.Arrays.fill(bottoms, top + h + vgap);
            }
            runStart = i + 1;
        }
        double used = max(bottoms);
        return used > 0 ? used - vgap : 0;
    }

    private double put(Node child, int column, double columnWidth, double top, boolean apply, Insets in) {
        double h = child.prefHeight(columnWidth);
        if (apply) child.resizeRelocate(in.getLeft() + column * (columnWidth + hgap), in.getTop() + top, columnWidth, h);
        return top + h + vgap;
    }

    private static double max(double[] values) {
        double m = 0;
        for (double v : values) m = Math.max(m, v);
        return m;
    }
}
