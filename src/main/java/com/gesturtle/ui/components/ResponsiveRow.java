package com.gesturtle.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

import java.util.List;

/**
 * Sections side by side, sharing the width by weight (2 + 1, 1 + 1 + 1…),
 * all as tall as the tallest, when there's at least {@code breakpoint} of
 * width and every section fits its share; stacked full width otherwise, so
 * a half-screen window still reads top to bottom.
 */
public class ResponsiveRow extends Pane {
    private static final String WEIGHT_KEY = "responsive-row-weight";

    private final double breakpoint;
    private double gap = 20;

    public ResponsiveRow(double breakpoint, Node... children) {
        this.breakpoint = breakpoint;
        getChildren().addAll(children);
    }

    /** A child's share of the width when side by side; 1 unless set. */
    public static <N extends Node> N weight(N node, double weight) {
        node.getProperties().put(WEIGHT_KEY, weight);
        return node;
    }

    public ResponsiveRow gap(double gap) {
        this.gap = gap;
        requestLayout();
        return this;
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    private static double weightOf(Node node) {
        return node.getProperties().get(WEIGHT_KEY) instanceof Double d ? d : 1.0;
    }

    private boolean sideBySide(double width) {
        List<Node> children = getManagedChildren();
        if (width < breakpoint || children.size() < 2) return false;
        double[] widths = widths(width);
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] < children.get(i).minWidth(-1)) return false;
        }
        return true;
    }

    private double[] widths(double width) {
        List<Node> children = getManagedChildren();
        double total = 0;
        for (Node child : children) total += weightOf(child);
        double usable = width - gap * (children.size() - 1);
        double[] widths = new double[children.size()];
        for (int i = 0; i < widths.length; i++) widths[i] = Math.floor(usable * weightOf(children.get(i)) / total);
        return widths;
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
        for (Node child : getManagedChildren()) widest = Math.max(widest, child.minWidth(-1));
        return in.getLeft() + in.getRight() + widest;
    }

    @Override
    protected double computePrefWidth(double height) {
        Insets in = getInsets();
        double sum = 0;
        for (Node child : getManagedChildren()) sum += child.prefWidth(-1);
        return in.getLeft() + in.getRight() + sum + gap * Math.max(0, getManagedChildren().size() - 1);
    }

    @Override
    protected double computePrefHeight(double width) {
        Insets in = getInsets();
        if (width < 0) width = getWidth() > 0 ? getWidth() : computePrefWidth(-1);
        double inner = width - in.getLeft() - in.getRight();
        List<Node> children = getManagedChildren();
        double h = 0;
        if (sideBySide(inner)) {
            double[] widths = widths(inner);
            for (int i = 0; i < widths.length; i++) h = Math.max(h, children.get(i).prefHeight(widths[i]));
        } else {
            for (Node child : children) h += child.prefHeight(inner);
            h += gap * Math.max(0, children.size() - 1);
        }
        return in.getTop() + in.getBottom() + h;
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double inner = getWidth() - in.getLeft() - in.getRight();
        List<Node> children = getManagedChildren();
        if (sideBySide(inner)) {
            double[] widths = widths(inner);
            double height = getHeight() - in.getTop() - in.getBottom();
            double x = in.getLeft();
            for (int i = 0; i < widths.length; i++) {
                children.get(i).resizeRelocate(x, in.getTop(), widths[i], height);
                x += widths[i] + gap;
            }
        } else {
            double y = in.getTop();
            for (Node child : children) {
                double h = child.prefHeight(inner);
                child.resizeRelocate(in.getLeft(), y, inner, h);
                y += h + gap;
            }
        }
    }
}
