package com.gesturtle.ui.components;

import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.util.List;

/**
 * Replaces the OS title bar for an undecorated Stage: a draggable bar with
 * minimize/maximize/close buttons, plus 8 edge/corner regions that drive
 * manual resize (an undecorated window has no OS resize grips). Pass the
 * app's root to {@link #wrap} and set it as the Scene's root in place of it.
 */
public final class WindowChrome extends AnchorPane {
    private static final double RESIZE_MARGIN = 6;
    private static final double MIN_WIDTH = 760;
    private static final double MIN_HEIGHT = 520;

    private final Stage stage;
    private final BorderPane body = new BorderPane();
    private final HBox titleBar;
    private final AppButton maximizeButton;
    private boolean maximized;
    private double restoreX, restoreY, restoreWidth, restoreHeight;

    public static WindowChrome wrap(Stage stage, Region content) {
        return new WindowChrome(stage, content);
    }

    private WindowChrome(Stage stage, Region content) {
        this.stage = stage;
        getStyleClass().add("window-chrome");
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        AppButton minimizeButton = AppButton.icon("minimize", "Minimize", () -> stage.setIconified(true));
        maximizeButton = AppButton.icon("maximize", "Maximize", this::toggleMaximize);
        AppButton closeButton = AppButton.icon("close", "Close", this::requestClose);
        closeButton.getStyleClass().add("btn-ghost-danger");

        titleBar = new HBox(10, spacer, minimizeButton, maximizeButton, closeButton);
        titleBar.getStyleClass().add("window-titlebar");
        titleBar.setAlignment(Pos.CENTER_LEFT);

        body.getStyleClass().add("window-body");
        body.setTop(titleBar);
        body.setCenter(content);

        getChildren().add(body);
        AnchorPane.setTopAnchor(body, 0.0);
        AnchorPane.setBottomAnchor(body, 0.0);
        AnchorPane.setLeftAnchor(body, 0.0);
        AnchorPane.setRightAnchor(body, 0.0);

        installDrag();
        installResizeHandles();
    }

    /** Hidden in full screen, same as the sidebar: the OS/FX fullscreen hint banner already covers this ground. */
    public void setTitleBarVisible(boolean visible) {
        titleBar.setVisible(visible);
        titleBar.setManaged(visible);
    }

    private void requestClose() {
        stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
    }

    private void toggleMaximize() {
        if (maximized) {
            stage.setX(restoreX);
            stage.setY(restoreY);
            stage.setWidth(restoreWidth);
            stage.setHeight(restoreHeight);
            maximized = false;
        } else {
            restoreX = stage.getX();
            restoreY = stage.getY();
            restoreWidth = stage.getWidth();
            restoreHeight = stage.getHeight();
            Rectangle2D bounds = visualBoundsUnderStage();
            stage.setX(bounds.getMinX());
            stage.setY(bounds.getMinY());
            stage.setWidth(bounds.getWidth());
            stage.setHeight(bounds.getHeight());
            maximized = true;
        }
        maximizeButton.setGraphic(new Icon(maximized ? "fullscreen-exit" : "maximize", 16));
    }

    private Rectangle2D visualBoundsUnderStage() {
        List<Screen> screens = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
        return screen.getVisualBounds();
    }

    /** Dragging the bar moves the window; dragging it while maximized restores first, under the cursor. */
    private void installDrag() {
        double[] pressOffset = new double[2];
        titleBar.setOnMousePressed(e -> {
            pressOffset[0] = stage.getX() - e.getScreenX();
            pressOffset[1] = stage.getY() - e.getScreenY();
        });
        titleBar.setOnMouseDragged(e -> {
            if (maximized) {
                double ratio = e.getX() / Math.max(1, titleBar.getWidth());
                maximized = false;
                maximizeButton.setGraphic(new Icon("maximize", 16));
                stage.setWidth(restoreWidth);
                stage.setHeight(restoreHeight);
                pressOffset[0] = -restoreWidth * ratio;
                pressOffset[1] = -e.getY();
            }
            stage.setX(e.getScreenX() + pressOffset[0]);
            stage.setY(e.getScreenY() + pressOffset[1]);
        });
        titleBar.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) toggleMaximize();
        });
    }

    private void installResizeHandles() {
        double m = RESIZE_MARGIN;
        addHandle(Cursor.N_RESIZE, anchors(0.0, null, m, m), false, false, true, false);
        addHandle(Cursor.S_RESIZE, anchors(null, 0.0, m, m), false, false, false, true);
        addHandle(Cursor.W_RESIZE, anchors(m, m, 0.0, null), true, false, false, false);
        addHandle(Cursor.E_RESIZE, anchors(m, m, null, 0.0), false, true, false, false);
        addHandle(Cursor.NW_RESIZE, anchors(0.0, null, 0.0, null), true, false, true, false);
        addHandle(Cursor.NE_RESIZE, anchors(0.0, null, null, 0.0), false, true, true, false);
        addHandle(Cursor.SW_RESIZE, anchors(null, 0.0, 0.0, null), true, false, false, true);
        addHandle(Cursor.SE_RESIZE, anchors(null, 0.0, null, 0.0), false, true, false, true);
    }

    private record Anchors(Double top, Double bottom, Double left, Double right) {
    }

    private Anchors anchors(Double top, Double bottom, Double left, Double right) {
        return new Anchors(top, bottom, left, right);
    }

    private void addHandle(Cursor cursor, Anchors a, boolean left, boolean right, boolean top, boolean bottom) {
        Region handle = new Region();
        handle.setCursor(cursor);
        double m = RESIZE_MARGIN;
        handle.setPrefWidth(a.left() != null && a.right() != null ? Region.USE_COMPUTED_SIZE : m);
        handle.setPrefHeight(a.top() != null && a.bottom() != null ? Region.USE_COMPUTED_SIZE : m);
        if (a.top() != null) AnchorPane.setTopAnchor(handle, a.top());
        if (a.bottom() != null) AnchorPane.setBottomAnchor(handle, a.bottom());
        if (a.left() != null) AnchorPane.setLeftAnchor(handle, a.left());
        if (a.right() != null) AnchorPane.setRightAnchor(handle, a.right());
        getChildren().add(handle);
        installResizeDrag(handle, left, right, top, bottom);
    }

    private void installResizeDrag(Region handle, boolean left, boolean right, boolean top, boolean bottom) {
        double[] start = new double[6]; // stageX, stageY, width, height, screenX, screenY
        handle.setOnMousePressed(e -> {
            start[0] = stage.getX();
            start[1] = stage.getY();
            start[2] = stage.getWidth();
            start[3] = stage.getHeight();
            start[4] = e.getScreenX();
            start[5] = e.getScreenY();
            e.consume();
        });
        handle.setOnMouseDragged(e -> {
            if (maximized) return;
            double dx = e.getScreenX() - start[4];
            double dy = e.getScreenY() - start[5];
            if (right) stage.setWidth(Math.max(MIN_WIDTH, start[2] + dx));
            if (bottom) stage.setHeight(Math.max(MIN_HEIGHT, start[3] + dy));
            if (left) {
                double newWidth = Math.max(MIN_WIDTH, start[2] - dx);
                stage.setX(start[0] + (start[2] - newWidth));
                stage.setWidth(newWidth);
            }
            if (top) {
                double newHeight = Math.max(MIN_HEIGHT, start[3] - dy);
                stage.setY(start[1] + (start[3] - newHeight));
                stage.setHeight(newHeight);
            }
            e.consume();
        });
    }
}
