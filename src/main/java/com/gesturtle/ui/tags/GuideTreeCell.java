package com.gesturtle.ui.tags;

import com.gesturtle.ui.common.Motion;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.RotateTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * A tree cell that draws hierarchy guide lines: [guide columns][chevron or
 * leaf dot][content]. Column j carries a vertical line while the ancestor at
 * depth j+1 still has siblings below it, and the last column joins the row to
 * its parent with a ├ or └. Used by the Tags page and the tag picker.
 *
 * The tree needs install() (style class + fixed row height), which turns off
 * the built-in indent and disclosure arrow in CSS (.tag-tree) so the guides
 * can be drawn in that space. Subclasses supply the content after the
 * chevron via content(), and call refreshGraphic() when it changes.
 */
public abstract class GuideTreeCell<T> extends TreeCell<T> {
    public static final double ROW_HEIGHT = 40;
    /** Width of one indent level; each level's guide line runs down its center. */
    private static final double INDENT = 26;

    /** The row's own expand/collapse changes its stub line and chevron, which updateItem alone won't redraw. */
    private final ChangeListener<Boolean> expandedListener = (o, a, b) -> {
        animateChevron = true;
        refreshGraphic();
    };
    /** Set for the redraw caused by expanding/collapsing this row, so its chevron turns instead of jumping. */
    private boolean animateChevron;
    /** Set when the click already turned the chevron (collapse), so the redraw doesn't turn it again. */
    private boolean chevronTurned;
    private TreeItem<T> watched;

    /** Fixed height so each row's guide lines meet the next row's exactly. */
    public static void install(TreeView<?> treeView) {
        treeView.getStyleClass().add("tag-tree");
        treeView.setFixedCellSize(ROW_HEIGHT);
    }

    /** The nodes shown after the chevron, for a non-empty row. */
    protected abstract List<Node> content(T value);

    @Override
    protected void updateItem(T value, boolean empty) {
        super.updateItem(value, empty);
        if (watched != null) watched.expandedProperty().removeListener(expandedListener);
        watched = null;
        setText(null);
        if (empty || value == null) {
            setGraphic(null);
            return;
        }
        watched = getTreeItem();
        if (watched != null) watched.expandedProperty().addListener(expandedListener);
        refreshGraphic();
    }

    /**
     * The skin offsets leaf rows and parent rows differently (it reserves
     * disclosure-arrow space unevenly), which would break the guide lines'
     * alignment; the graphic draws the whole indent itself, so pin it to the
     * cell's left edge.
     */
    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        Node graphic = getGraphic();
        if (graphic != null) {
            graphic.relocate(snappedLeftInset(), graphic.getLayoutY());
            if (getDisclosureNode() != null) getDisclosureNode().setVisible(false);
        }
    }

    protected void refreshGraphic() {
        TreeItem<T> item = getTreeItem();
        T value = getItem();
        if (item == null || value == null || getTreeView() == null) return;
        int depth = getTreeView().getTreeItemLevel(item) - (getTreeView().isShowRoot() ? 0 : 1);

        List<TreeItem<T>> path = new ArrayList<>();
        for (TreeItem<T> t = item; t != null && t.getParent() != null; t = t.getParent()) path.add(0, t);

        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);
        double mid = Math.floor(INDENT / 2) + 0.5, half = Math.floor(ROW_HEIGHT / 2) + 0.5;
        for (int j = 0; j < depth; j++) {
            Pane column = guideColumn();
            boolean last = j == depth - 1;
            boolean continues = path.get(j + 1).nextSibling() != null;
            if (!last) {
                if (continues) column.getChildren().add(guide(mid, 0, mid, ROW_HEIGHT));
            } else {
                column.getChildren().add(guide(mid, 0, mid, continues ? ROW_HEIGHT : half));
                column.getChildren().add(guide(mid, half, INDENT, half));
            }
            row.getChildren().add(column);
        }

        Pane node = guideColumn();
        if (!item.getChildren().isEmpty()) {
            SVGPath chevron = new SVGPath();
            chevron.setContent("M0 0 L5 5 L0 10");
            chevron.getStyleClass().add("tree-chevron");
            double angle = item.isExpanded() ? 90 : 0;
            if (chevronTurned) {
                chevron.setRotate(angle);
            } else if (animateChevron) {
                chevron.setRotate(90 - angle);
                RotateTransition turn = new RotateTransition(Duration.millis(200), chevron);
                turn.setToAngle(angle);
                turn.setInterpolator(Motion.OVERSHOOT);
                turn.play();
            } else {
                chevron.setRotate(angle);
            }
            StackPane hit = new StackPane(chevron);
            hit.getStyleClass().add("tree-chevron-hit");
            hit.setPrefSize(INDENT, ROW_HEIGHT);
            hit.setOnMousePressed(e -> {
                if (item.isExpanded()) {
                    // Collapsing waits for the rows to animate out; turn the chevron now so the click feels immediate.
                    RotateTransition turn = new RotateTransition(Duration.millis(200), chevron);
                    turn.setToAngle(0);
                    turn.setInterpolator(Motion.OVERSHOOT);
                    turn.play();
                    chevronTurned = true;
                }
                toggleAnimated(getTreeView(), item);
                e.consume();
            });
            if (depth > 0) node.getChildren().add(guide(0, half, mid - 8, half));
            if (item.isExpanded()) node.getChildren().add(guide(mid, half + 8, mid, ROW_HEIGHT));
            node.getChildren().add(hit);
        } else {
            if (depth > 0) node.getChildren().add(guide(0, half, mid - 4, half));
            Circle dot = new Circle(mid, half, 3);
            dot.getStyleClass().add("tree-leaf-dot");
            node.getChildren().add(dot);
        }
        row.getChildren().add(node);
        row.getChildren().addAll(content(value));
        setGraphic(row);
        animateChevron = false;
        chevronTurned = false;
    }

    private static final String TOGGLING_KEY = "guideTree.toggling";
    private static final double STAGGER_MS = 18;

    /**
     * Expands or collapses item with motion instead of rows popping in and out.
     * Expanding: the new child rows fade and slide down into place one after
     * another while the rows below glide down to make room. Collapsing: the
     * children fade up and the rows below glide up over them, and only then
     * does the branch actually close.
     */
    public static <T> void toggleAnimated(TreeView<T> tree, TreeItem<T> item) {
        if (tree == null || tree.getProperties().containsKey(TOGGLING_KEY)) return;
        int row = tree.getRow(item);
        if (!item.isExpanded()) {
            item.setExpanded(true);
            int added = visibleDescendants(item);
            tree.layout();
            for (TreeCell<?> cell : cells(tree)) {
                int index = cell.getIndex();
                if (index > row && index <= row + added) {
                    cell.setOpacity(0);
                    cell.setTranslateY(-10);
                    FadeTransition fade = new FadeTransition(Duration.millis(200), cell);
                    fade.setToValue(1);
                    TranslateTransition drop = new TranslateTransition(Duration.millis(240), cell);
                    drop.setToY(0);
                    drop.setInterpolator(Motion.OVERSHOOT);
                    ParallelTransition in = new ParallelTransition(fade, drop);
                    in.setDelay(Duration.millis(Math.min(index - row - 1, 12) * STAGGER_MS));
                    in.play();
                } else if (index > row + added) {
                    cell.setTranslateY(-added * ROW_HEIGHT);
                    TranslateTransition glide = new TranslateTransition(Duration.millis(240), cell);
                    glide.setToY(0);
                    glide.setInterpolator(Motion.EASE_OUT);
                    glide.play();
                }
            }
            return;
        }
        int removed = visibleDescendants(item);
        if (removed == 0) {
            item.setExpanded(false);
            return;
        }
        tree.getProperties().put(TOGGLING_KEY, true);
        List<TreeCell<?>> touched = new ArrayList<>();
        ParallelTransition out = new ParallelTransition();
        for (TreeCell<?> cell : cells(tree)) {
            int index = cell.getIndex();
            if (index > row && index <= row + removed) {
                FadeTransition fade = new FadeTransition(Duration.millis(140), cell);
                fade.setToValue(0);
                TranslateTransition lift = new TranslateTransition(Duration.millis(160), cell);
                lift.setToY(-8);
                out.getChildren().addAll(fade, lift);
                touched.add(cell);
            } else if (index > row + removed) {
                TranslateTransition glide = new TranslateTransition(Duration.millis(200), cell);
                glide.setToY(-removed * ROW_HEIGHT);
                glide.setInterpolator(Motion.EASE_OUT);
                out.getChildren().add(glide);
                touched.add(cell);
            }
        }
        out.setOnFinished(e -> {
            for (TreeCell<?> cell : touched) {
                cell.setOpacity(1);
                cell.setTranslateY(0);
            }
            item.setExpanded(false);
            tree.getProperties().remove(TOGGLING_KEY);
        });
        out.play();
    }

    /** Rows that showing item's children adds below it (children, plus their open branches). */
    private static int visibleDescendants(TreeItem<?> item) {
        int count = 0;
        for (TreeItem<?> child : item.getChildren()) {
            count++;
            if (child.isExpanded()) count += visibleDescendants(child);
        }
        return count;
    }

    private static List<TreeCell<?>> cells(TreeView<?> tree) {
        List<TreeCell<?>> cells = new ArrayList<>();
        for (Node n : tree.lookupAll(".tree-cell")) {
            if (n instanceof TreeCell<?> cell && cell.getItem() != null && cell.isVisible()) cells.add(cell);
        }
        return cells;
    }

    /** True if node is the chevron (or inside it), so row-click handlers can leave expand/collapse alone. */
    protected static boolean isChevron(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.getStyleClass().contains("tree-chevron-hit")) return true;
        }
        return false;
    }

    private static Pane guideColumn() {
        Pane column = new Pane();
        column.setMinSize(INDENT, ROW_HEIGHT);
        column.setPrefSize(INDENT, ROW_HEIGHT);
        column.setMaxSize(INDENT, ROW_HEIGHT);
        return column;
    }

    private static Line guide(double x1, double y1, double x2, double y2) {
        Line line = new Line(x1, y1, x2, y2);
        line.getStyleClass().add("tree-guide");
        return line;
    }
}
