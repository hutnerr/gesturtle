package com.gesturtle.ui;

import com.gesturtle.ui.common.Motion;
import com.gesturtle.ui.common.Refreshable;
import com.gesturtle.ui.common.UndoBar;
import com.gesturtle.ui.components.Page;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** The app's navigation shell: a sidebar plus a center area that swaps between views. */
public class Shell extends BorderPane {
    private final Nav nav = new Nav();
    private final StackPane content = new StackPane();
    private final Map<String, Supplier<Region>> factories = new LinkedHashMap<>();
    private final Map<String, Region> cache = new LinkedHashMap<>();

    public Shell() {
        getStyleClass().add("shell");
        setLeft(nav);
        // The undo bar floats over whichever view is showing, outside content so show() doesn't replace it.
        UndoBar undoBar = new UndoBar(this::refreshCurrent);
        StackPane center = new StackPane(content, undoBar);
        StackPane.setAlignment(undoBar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(undoBar, new Insets(0, 0, 24, 0));
        setCenter(center);
        // Ctrl+Z undoes the action on offer, unless a text field has focus (it has its own undo).
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.Z && e.isShortcutDown() && !e.isShiftDown()
                    && !(getScene() != null && getScene().getFocusOwner() instanceof TextInputControl)
                    && UndoBar.undoIfOffered()) {
                e.consume();
            }
        });
    }

    /** Full-screen practice hides the sidebar so the image gets the whole screen. */
    public void setNavVisible(boolean visible) {
        setLeft(visible ? nav : null);
    }

    public void registerView(String key, String label, String icon, Supplier<Region> factory) {
        factories.put(key, factory);
        nav.addItem(key, label, icon, this::show);
    }

    /** Same as registerView, but pinned below the spacer at the bottom of the sidebar (e.g. Settings, grouped with Help). */
    public void registerFooterView(String key, String label, String icon, Supplier<Region> factory) {
        factories.put(key, factory);
        nav.addFooterItem(key, label, icon, this::show);
    }

    /** Registers a view reachable only by calling show() directly (e.g. from a button elsewhere), with no sidebar entry. */
    public void registerHiddenView(String key, Supplier<Region> factory) {
        factories.put(key, factory);
    }

    /**
     * Builds every view not built yet, so the first click on a tab doesn't wait for it. One view per
     * runLater rather than all in one go, so the window keeps painting and answering clicks between them.
     */
    public void prebuildAll() {
        java.util.Iterator<String> keys = new java.util.ArrayList<>(factories.keySet()).iterator();
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            if (!keys.hasNext()) return;
            String key = keys.next();
            cache.computeIfAbsent(key, k -> factories.get(k).get());
            javafx.application.Platform.runLater(next[0]);
        };
        javafx.application.Platform.runLater(next[0]);
    }

    /** Reloads the view now showing, e.g. after a git pull changed the library under it. */
    public void refreshCurrent() {
        if (!content.getChildren().isEmpty() && content.getChildren().get(0) instanceof Refreshable refreshable) {
            refreshable.onShown();
        }
    }

    /** Returns the view now showing, so callers can hand it work (e.g. files dropped on the window). */
    public Region show(String key) {
        Region view = cache.computeIfAbsent(key, k -> factories.get(k).get());
        boolean changed = content.getChildren().isEmpty() || content.getChildren().get(0) != view;
        content.getChildren().setAll(view);
        nav.select(key);
        if (view instanceof Refreshable refreshable) {
            refreshable.onShown();
        }
        // After onShown, so whatever it does on this thread doesn't eat the first frames of the entrance.
        if (changed) enter(view);
        return view;
    }

    /**
     * A Page's background and contour lines look the same on every screen, so only its header and
     * body animate in; fading the whole page made the background blink on every switch. Practice
     * wraps its page in a container, hence the look one level down.
     */
    private static void enter(Region view) {
        Node inner = view.getChildrenUnmodifiable().size() == 1 ? view.getChildrenUnmodifiable().get(0) : null;
        Page page = view instanceof Page p ? p : inner instanceof Page p ? p : null;
        if (page != null) {
            for (Node part : new Node[] {page.getTop(), page.getCenter(), page.getBottom()}) {
                if (part != null) Motion.enter(part, 8, 180);
            }
        } else {
            Motion.enter(view, 8, 180);
        }
    }
}
