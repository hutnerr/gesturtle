package com.gesturtle.ui.components;

import com.gesturtle.ui.common.Motion;
import com.gesturtle.ui.common.Shortcuts;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.Effect;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.List;

/**
 * The image surface shared by practice and the Gallery's detail view: fitted
 * display, scroll or double-click to zoom, drag to pan, flip, rotate,
 * grayscale, blur ("squint"), a proportion grid, fit, and actual size. An
 * adjustments panel adds brightness, contrast, saturation, invert, and a
 * value study that posterizes the picture into 2–5 gray tones. Both screens
 * put toolButtons() in their toolbar and pass shortcut actions to
 * handle(), so the two stay equally capable.
 *
 * Every adjustment is a view of the original, which is never changed.
 * Brightness/contrast/saturation run as a GPU effect, live. Invert and the
 * value study can't be expressed as effects, so they're computed into a
 * separate bitmap (capped at {@link #PROCESS_MAX_DIM} on its long side, so a
 * huge scan stays quick) that's shown in the original's place.
 */
public class ImageViewer extends StackPane {
    /** Grid sizes R steps through: off, then 3×3, then 4×4. */
    private static final int[] GRID_STEPS = {0, 3, 4};
    /** Tone counts the value-study shortcut steps through; 0 is off. */
    private static final int[] VALUE_STEPS = {0, 2, 3, 4};
    /** Degrees per press of a rotate button. */
    private static final double ROTATE_STEP = 30;
    /** How far a double-click zooms in from fitted. */
    private static final double DOUBLE_CLICK_ZOOM = 2.5;
    private static final double MAX_ZOOM = 16;
    private static final int PROCESS_MAX_DIM = 2400;
    private static final Duration EASE = Duration.millis(220);

    private final ImageView imageView = new ImageView();
    /** Lines over the image itself (not the whole pane), so they zoom, pan, and flip with it. */
    private final Pane gridLayer = new Pane();
    private final Group imageGroup = new Group(imageView, gridLayer);
    private final BooleanProperty grayscale = new SimpleBooleanProperty(false);
    private final BooleanProperty blur = new SimpleBooleanProperty(false);
    private final IntegerProperty gridDivisions = new SimpleIntegerProperty(0);
    private final BooleanProperty flipH = new SimpleBooleanProperty(false);
    private final BooleanProperty flipV = new SimpleBooleanProperty(false);
    private final DoubleProperty brightness = new SimpleDoubleProperty(0);
    private final DoubleProperty contrast = new SimpleDoubleProperty(0);
    private final DoubleProperty saturation = new SimpleDoubleProperty(0);
    private final BooleanProperty invert = new SimpleBooleanProperty(false);
    /** 0 = off, else the number of gray tones. */
    private final IntegerProperty valueLevels = new SimpleIntegerProperty(0);
    private final BooleanProperty adjustPanelShown = new SimpleBooleanProperty(false);
    private final StackPane adjustPanel;
    /** Re-runs the pixel pass once the sliders settle, rather than on every drag step. */
    private final PauseTransition reprocess = new PauseTransition(Duration.millis(90));

    /** The picture as loaded; imageView may show a processed copy of it instead. */
    private Image source;
    private double zoom = 1.0;
    /** The shown angle, eased toward rotationTarget; unnormalized, so -30 from 0 turns back rather than round. */
    private final DoubleProperty rotation = new SimpleDoubleProperty(0);
    private double rotationTarget = 0;
    private Timeline rotateAnimation;
    private Timeline zoomAnimation;
    /** Wheel zoom glides toward wheelTarget, keeping the point under the pointer (wheelX, wheelY) still. */
    private double wheelTarget, wheelX, wheelY;
    private long wheelLastFrame;
    private final javafx.animation.AnimationTimer wheelZoom = new javafx.animation.AnimationTimer() {
        @Override
        public void handle(long now) {
            stepWheelZoom(now);
        }
    };
    private boolean wheelZooming;
    private double dragStartX, dragStartY, groupStartX, groupStartY;

    public ImageViewer() {
        getStyleClass().add("image-area");
        getChildren().add(imageGroup);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        // The image's natural size must never become this pane's minimum size, or a large
        // image loading before the first layout pass pushes the surrounding toolbars off-screen.
        setMinSize(0, 0);
        setInset(0);

        flipH.addListener((o, a, on) -> applyFlip());
        flipV.addListener((o, a, on) -> applyFlip());
        grayscale.addListener((o, a, on) -> applyEffect());
        blur.addListener((o, a, on) -> applyEffect());
        reprocess.setOnFinished(e -> refreshDisplayed());
        for (DoubleProperty p : List.of(brightness, contrast, saturation)) {
            p.addListener((o, a, v) -> {
                if (pixelMode()) reprocess.playFromStart();
                else applyEffect();
            });
        }
        invert.addListener((o, a, on) -> refreshDisplayed());
        valueLevels.addListener((o, a, n) -> refreshDisplayed());
        gridLayer.setMouseTransparent(true);
        gridLayer.visibleProperty().bind(imageView.visibleProperty().and(gridDivisions.greaterThan(0)));
        gridDivisions.addListener((o, a, n) -> drawGrid());
        // Fitted size changes with the window and with each new image; the grid and blur track it.
        imageView.layoutBoundsProperty().addListener((o, a, b) -> {
            applyScale();
            if (blur.get()) applyEffect();
        });
        rotation.addListener((o, a, r) -> {
            imageGroup.setRotate(r.doubleValue());
            applyScale();
        });

        adjustPanel = buildAdjustPanel();
        getChildren().add(adjustPanel);
        StackPane.setAlignment(adjustPanel, Pos.TOP_RIGHT);
        StackPane.setMargin(adjustPanel, new Insets(16));
        adjustPanel.visibleProperty().bind(adjustPanelShown);
        adjustPanelShown.addListener((o, a, on) -> {
            if (on) Motion.zoomIn(adjustPanel, 0.95, 160);
            com.gesturtle.ui.common.Sounds.play(on ? com.gesturtle.ui.common.Sounds.Sound.OPEN : com.gesturtle.ui.common.Sounds.Sound.CLOSE);
        });

        setOnScroll(e -> {
            // Touchpad gestures fire a flood of deltaY=0 phase/inertia events alongside the
            // real ±N ticks; treating 0 as "scroll down" let that noise outvote scroll-up.
            if (e.getDeltaY() != 0) wheelZoomBy(e);
            e.consume();
        });
        setOnMousePressed(e -> {
            stopZoomAnimation();
            dragStartX = e.getSceneX();
            dragStartY = e.getSceneY();
            groupStartX = imageGroup.getTranslateX();
            groupStartY = imageGroup.getTranslateY();
        });
        setOnMouseDragged(e -> {
            imageGroup.setTranslateX(groupStartX + (e.getSceneX() - dragStartX));
            imageGroup.setTranslateY(groupStartY + (e.getSceneY() - dragStartY));
        });
        // A handler, not setOnMouseClicked: practice sets that for click-to-continue.
        addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 2 || !e.isStillSincePress()) return;
            toggleZoomAt(e.getX(), e.getY());
        });
    }

    /** Empty space kept around the fitted image, in pixels. */
    public void setInset(double inset) {
        imageView.fitWidthProperty().bind(widthProperty().subtract(inset * 2));
        imageView.fitHeightProperty().bind(heightProperty().subtract(inset * 2));
    }

    /** Fades the picture in once it has actually decoded (JavaFX loads in the background). */
    public void setImage(Image image) {
        source = image;
        if (image == null) {
            imageView.setImage(null);
            return;
        }
        if (image.getProgress() >= 1) {
            imageView.setImage(displayed());
            Motion.fadeIn(imageView, 220);
        } else {
            imageView.setImage(pixelMode() ? null : image);
            imageView.setOpacity(0);
            image.progressProperty().addListener((o, a, p) -> {
                if (p.doubleValue() >= 1 && source == image) {
                    imageView.setImage(displayed());
                    Motion.fadeIn(imageView, 220);
                }
            });
        }
    }

    /** Hides the picture but keeps the surface (memory drawing's hidden phase). */
    public void setImageVisible(boolean visible) {
        imageView.setVisible(visible);
    }

    /** Back to fitted, centered, unzoomed. Flip, rotation, and filters are left alone. */
    public void fit() {
        stopZoomAnimation();
        setZoom(1.0);
        imageGroup.setTranslateX(0);
        imageGroup.setTranslateY(0);
    }

    /** One image pixel per screen pixel, centered; the picture's true size, e.g. to judge detail. */
    public void actualSize() {
        Bounds b = imageView.getLayoutBounds();
        if (source == null || b.getWidth() <= 0) return;
        stopZoomAnimation();
        animateZoom(source.getWidth() / b.getWidth(), 0, 0);
    }

    public void clearFlips() {
        flipH.set(false);
        flipV.set(false);
    }

    public void flipHorizontal() {
        flipH.set(!flipH.get());
    }

    public void flipVertical() {
        flipV.set(!flipV.get());
    }

    /** Turns the picture by degrees (positive is clockwise), shrinking it as needed so its corners stay in view. */
    public void rotate(double degrees) {
        rotationTarget += degrees;
        if (rotateAnimation != null) rotateAnimation.stop();
        rotateAnimation = new Timeline(new KeyFrame(EASE, new KeyValue(rotation, rotationTarget, Motion.EASE_OUT)));
        rotateAnimation.play();
    }

    /** Back to upright, at once (for a new image). */
    public void clearRotation() {
        if (rotateAnimation != null) rotateAnimation.stop();
        rotationTarget = 0;
        rotation.set(0);
    }

    /** Grayscale, blur, value study, invert, and the sliders back to off. */
    public void clearFilters() {
        grayscale.set(false);
        blur.set(false);
        resetAdjustments();
    }

    private void resetAdjustments() {
        brightness.set(0);
        contrast.set(0);
        saturation.set(0);
        invert.set(false);
        valueLevels.set(0);
    }

    /**
     * Double-click: from fitted, zooms in on the clicked point (it stays
     * under the pointer); when already zoomed or panned, goes back to fit.
     */
    private void toggleZoomAt(double x, double y) {
        boolean fitted = Math.abs(zoom - 1.0) < 0.01
                && Math.abs(imageGroup.getTranslateX()) < 0.5 && Math.abs(imageGroup.getTranslateY()) < 0.5;
        if (!fitted) {
            animateZoom(1.0, 0, 0);
            return;
        }
        // Zoom and rotation pivot on the group's center c, so a point p on screen is c + t + s·(local − c)
        // (rotated). Keeping p fixed while the scale goes s → s2 needs t2 = p − c − (s2/s)(p − c − t).
        Bounds lb = imageGroup.getLayoutBounds();
        double cx = imageGroup.getLayoutX() + lb.getMinX() + lb.getWidth() / 2;
        double cy = imageGroup.getLayoutY() + lb.getMinY() + lb.getHeight() / 2;
        double ratio = DOUBLE_CLICK_ZOOM / zoom;
        double tx = imageGroup.getTranslateX(), ty = imageGroup.getTranslateY();
        animateZoom(DOUBLE_CLICK_ZOOM, x - cx - ratio * (x - cx - tx), y - cy - ratio * (y - cy - ty));
    }

    /** Where zoom is headed, so a key held or tapped quickly compounds from there rather than mid-ease. */
    private double zoomTarget() {
        return zoomAnimation != null ? zoomAnimationTarget : wheelZooming ? wheelTarget : zoom;
    }

    private double zoomAnimationTarget;

    private void animateZoom(double toZoom, double toX, double toY) {
        stopZoomAnimation();
        zoomAnimationTarget = toZoom;
        DoubleProperty z = new SimpleDoubleProperty(zoom);
        z.addListener((o, a, v) -> setZoom(v.doubleValue()));
        zoomAnimation = new Timeline(new KeyFrame(EASE,
                new KeyValue(z, toZoom, Motion.EASE_OUT),
                new KeyValue(imageGroup.translateXProperty(), toX, Motion.EASE_OUT),
                new KeyValue(imageGroup.translateYProperty(), toY, Motion.EASE_OUT)));
        zoomAnimation.setOnFinished(e -> zoomAnimation = null);
        zoomAnimation.play();
    }

    /** Scroll, drag, and fit take over from a double-click zoom still easing in. */
    private void stopZoomAnimation() {
        if (zoomAnimation != null) zoomAnimation.stop();
        zoomAnimation = null;
        wheelZoom.stop();
        wheelZooming = false;
    }

    /**
     * 10% a wheel notch, in proportion for a touchpad's smaller steps (one
     * flood of tiny events used to zoom 10% each), toward the pointer.
     */
    private void wheelZoomBy(ScrollEvent e) {
        double perNotch = e.getMultiplierY() > 0 ? e.getMultiplierY() : 40;
        double notches = Math.max(-3, Math.min(3, e.getDeltaY() / perNotch));
        if (!wheelZooming) {
            stopZoomAnimation();
            wheelTarget = zoom;
            wheelLastFrame = 0;
            wheelZooming = true;
            wheelZoom.start();
        }
        wheelTarget = clampZoom(wheelTarget * Math.pow(1.1, notches));
        wheelX = e.getX();
        wheelY = e.getY();
    }

    private void stepWheelZoom(long now) {
        double dt = wheelLastFrame == 0 ? 1.0 / 120 : (now - wheelLastFrame) / 1e9;
        wheelLastFrame = now;
        double next = zoom + (wheelTarget - zoom) * (1 - Math.exp(-dt / 0.06));
        if (Math.abs(wheelTarget - next) < zoom * 0.002) next = wheelTarget;
        // Same pivot math as toggleZoomAt: keep the pointer's point fixed as the scale goes zoom → next.
        Bounds lb = imageGroup.getLayoutBounds();
        double cx = imageGroup.getLayoutX() + lb.getMinX() + lb.getWidth() / 2;
        double cy = imageGroup.getLayoutY() + lb.getMinY() + lb.getHeight() / 2;
        double ratio = next / zoom;
        double tx = imageGroup.getTranslateX(), ty = imageGroup.getTranslateY();
        imageGroup.setTranslateX(wheelX - cx - ratio * (wheelX - cx - tx));
        imageGroup.setTranslateY(wheelY - cy - ratio * (wheelY - cy - ty));
        setZoom(next);
        if (next == wheelTarget) {
            wheelZoom.stop();
            wheelZooming = false;
        }
    }

    private static double clampZoom(double z) {
        return Math.max(0.2, Math.min(MAX_ZOOM, z));
    }

    public BooleanProperty grayscaleProperty() {
        return grayscale;
    }

    public BooleanProperty blurProperty() {
        return blur;
    }

    /** 0 when the grid is off, else the number of cells per side. */
    public IntegerProperty gridDivisionsProperty() {
        return gridDivisions;
    }

    /** Off → 3×3 → 4×4 → off. */
    public void cycleGrid() {
        gridDivisions.set(next(GRID_STEPS, gridDivisions.get()));
    }

    /** Off → 2 tones (a notan) → 3 → 4 → off. 5 is reachable from the panel. */
    public void cycleValues() {
        valueLevels.set(next(VALUE_STEPS, valueLevels.get()));
    }

    private static int next(int[] steps, int current) {
        int index = 0;
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == current) index = i;
        }
        return steps[(index + 1) % steps.length];
    }

    /**
     * Flip H, flip V, rotate left/right, grayscale, blur, adjustments, grid,
     * fit, actual size: icon-only, with the shortcut in each tooltip, and
     * toolbar dividers between the groups.
     */
    public List<Node> toolButtons() {
        // Not a plain toggle: a click steps the grid size, and the button shows "on" for any size.
        ToggleButton gridButton = AppButton.withIcon(AppButton.toggle(null, AppButton.Variant.GHOST), "grid");
        gridButton.getStyleClass().add("btn-icon");
        gridButton.setTooltip(shortcutTooltip(Shortcuts.Action.GRID, "Grid (3×3, then 4×4)"));
        gridButton.setSelected(gridDivisions.get() > 0);
        gridButton.setOnAction(e -> {
            cycleGrid();
            gridButton.setSelected(gridDivisions.get() > 0);
        });
        gridDivisions.addListener((o, a, n) -> gridButton.setSelected(n.intValue() > 0));

        // Lit while the panel is open or any adjustment is in effect, so a forgotten filter is visible.
        ToggleButton adjustButton = AppButton.withIcon(AppButton.toggle(null, AppButton.Variant.GHOST), "sliders");
        adjustButton.getStyleClass().addAll("btn-icon", com.gesturtle.ui.common.UiSounds.QUIET);
        adjustButton.setTooltip(shortcutTooltip(Shortcuts.Action.ADJUST, "Brightness, contrast, value study, invert"));
        Runnable syncAdjust = () -> adjustButton.setSelected(adjustPanelShown.get() || adjusted());
        adjustButton.setOnAction(e -> {
            adjustPanelShown.set(!adjustPanelShown.get());
            syncAdjust.run();
        });
        adjustPanelShown.addListener((o, a, b) -> syncAdjust.run());
        for (var p : List.of(brightness, contrast, saturation, invert, valueLevels)) p.addListener((o, a, b) -> syncAdjust.run());

        // Grouped by what they change: orientation | tone | guides | size.
        return List.of(
                toggleIcon("flip-horizontal", Shortcuts.Action.FLIP_H, null, flipH),
                toggleIcon("flip-vertical", Shortcuts.Action.FLIP_V, null, flipV),
                actionIcon("rotate-left", Shortcuts.Action.ROTATE_LEFT, () -> rotate(-ROTATE_STEP)),
                actionIcon("rotate-right", Shortcuts.Action.ROTATE_RIGHT, () -> rotate(ROTATE_STEP)),
                Toolbar.divider(),
                toggleIcon("contrast", Shortcuts.Action.GRAYSCALE, null, grayscale),
                toggleIcon("blur", Shortcuts.Action.BLUR, "Blur, to squint at big shapes", blur),
                adjustButton,
                Toolbar.divider(),
                gridButton,
                Toolbar.divider(),
                actionIcon("maximize", Shortcuts.Action.FIT, this::fit),
                actionIcon("actual-size", Shortcuts.Action.ACTUAL_SIZE, this::actualSize));
    }

    /** Runs a viewer shortcut; returns whether the action was one of the viewer's. */
    public boolean handle(Shortcuts.Action action) {
        if (action == null) return false;
        switch (action) {
            case GRAYSCALE -> grayscale.set(!grayscale.get());
            case BLUR -> blur.set(!blur.get());
            case VALUES -> cycleValues();
            case INVERT -> invert.set(!invert.get());
            case ADJUST -> adjustPanelShown.set(!adjustPanelShown.get());
            case GRID -> cycleGrid();
            case FLIP_H -> flipHorizontal();
            case FLIP_V -> flipVertical();
            case ROTATE_LEFT -> rotate(-ROTATE_STEP);
            case ROTATE_RIGHT -> rotate(ROTATE_STEP);
            case ZOOM_IN -> animateZoom(clampZoom(zoomTarget() * 1.2), imageGroup.getTranslateX(), imageGroup.getTranslateY());
            case ZOOM_OUT -> animateZoom(clampZoom(zoomTarget() * 0.8), imageGroup.getTranslateX(), imageGroup.getTranslateY());
            case FIT -> fit();
            case ACTUAL_SIZE -> actualSize();
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Closes the adjustments panel if it's open; returns whether it was (so Esc closes it first). */
    public boolean closePanel() {
        if (!adjustPanelShown.get()) return false;
        adjustPanelShown.set(false);
        return true;
    }

    private boolean adjusted() {
        return brightness.get() != 0 || contrast.get() != 0 || saturation.get() != 0 || pixelMode();
    }

    private boolean pixelMode() {
        return invert.get() || valueLevels.get() > 0;
    }

    /**
     * A floating card over the image's corner; its own mouse events never
     * reach the pan/zoom handlers below. The shadow is a separate layer
     * behind the card: an effect on the card itself would render its text
     * through an offscreen buffer, which drops LCD text smoothing.
     */
    private StackPane buildAdjustPanel() {
        Label title = new Label("Adjust");
        title.getStyleClass().add("adjust-title");

        GridPane sliders = new GridPane();
        sliders.setHgap(12);
        sliders.setVgap(8);
        sliders.addRow(0, panelLabel("Brightness"), slider(brightness));
        sliders.addRow(1, panelLabel("Contrast"), slider(contrast));
        sliders.addRow(2, panelLabel("Saturation"), slider(saturation));

        SegmentedControl<Integer> values = new SegmentedControl<Integer>()
                .option(0, "Off").option(2, "2").option(3, "3").option(4, "4").option(5, "5");
        values.setValue(valueLevels.get());
        values.valueProperty().addListener((o, a, v) -> {
            if (v != null) valueLevels.set(v);
        });
        valueLevels.addListener((o, a, v) -> values.setValue(v.intValue()));
        Label valuesHint = new Label("Gray tones. Brightness moves where they split.");
        valuesHint.getStyleClass().add("adjust-hint");
        valuesHint.setWrapText(true);

        CheckBox invertBox = new CheckBox("Invert colors");
        invertBox.selectedProperty().bindBidirectional(invert);

        AppButton reset = AppButton.ghost("Reset", this::resetAdjustments).small();
        AppButton close = AppButton.icon("close", "Close", () -> adjustPanelShown.set(false));
        close.getStyleClass().add(com.gesturtle.ui.common.UiSounds.QUIET);
        HBox header = new HBox(8, title, new Spacer(), reset, close);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(12, header, sliders, panelLabel("Value study"), values, valuesHint, invertBox);
        panel.getStyleClass().add("adjust-panel");
        panel.setPadding(new Insets(14, 16, 16, 16));
        panel.setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        panel.setPrefWidth(320);
        valuesHint.setMaxWidth(288);
        Region shadow = new Region();
        shadow.getStyleClass().add("adjust-panel-shadow");
        StackPane layered = new StackPane(shadow, panel);
        layered.setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        layered.addEventHandler(MouseEvent.ANY, Event::consume);
        layered.addEventHandler(ScrollEvent.ANY, Event::consume);
        return layered;
    }

    private static Label panelLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("adjust-label");
        label.setMinWidth(USE_PREF_SIZE);
        return label;
    }

    /** -1 to 1, centered; double-click puts it back to 0. */
    private static Slider slider(DoubleProperty value) {
        Slider slider = new Slider(-1, 1, value.get());
        slider.setPrefWidth(190);
        slider.valueProperty().bindBidirectional(value);
        slider.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) value.set(0);
        });
        return slider;
    }

    /** A tooltip that reads the current binding each time it shows, so rebinding a key updates it. */
    private static Tooltip shortcutTooltip(Shortcuts.Action action, String text) {
        Tooltip tooltip = new Tooltip();
        Runnable sync = () -> {
            String keys = Shortcuts.describe(action);
            String label = text != null ? text : action.label;
            tooltip.setText("—".equals(keys) ? label : label + " (" + keys.split(" / ")[0] + ")");
        };
        sync.run();
        tooltip.setOnShowing(e -> sync.run());
        return tooltip;
    }

    /** An icon toggle bound both ways to its state, so the key shortcut also lights the button. */
    private static ToggleButton toggleIcon(String icon, Shortcuts.Action action, String text, BooleanProperty state) {
        ToggleButton button = AppButton.withIcon(AppButton.toggle(null, AppButton.Variant.GHOST), icon);
        button.getStyleClass().add("btn-icon");
        button.setTooltip(shortcutTooltip(action, text));
        button.selectedProperty().bindBidirectional(state);
        return button;
    }

    private static AppButton actionIcon(String icon, Shortcuts.Action action, Runnable onAction) {
        AppButton button = AppButton.icon(icon, action.label, onAction);
        button.setTooltip(shortcutTooltip(action, null));
        return button;
    }

    private void applyFlip() {
        imageView.setScaleX(flipH.get() ? -1 : 1);
        imageView.setScaleY(flipV.get() ? -1 : 1);
    }

    private void setZoom(double newZoom) {
        zoom = clampZoom(newZoom);
        applyScale();
    }

    /** The group's on-screen scale: the zoom, times whatever shrink keeps a rotated picture's corners in view. */
    private double scale() {
        Bounds b = imageView.getLayoutBounds();
        double w = b.getWidth(), h = b.getHeight();
        if (w <= 0 || h <= 0 || rotation.get() % 180 == 0) return zoom;
        double rad = Math.toRadians(rotation.get());
        double cos = Math.abs(Math.cos(rad)), sin = Math.abs(Math.sin(rad));
        double room = Math.min(imageView.getFitWidth() / (w * cos + h * sin), imageView.getFitHeight() / (w * sin + h * cos));
        return zoom * Math.min(1.0, room);
    }

    private void applyScale() {
        double s = scale();
        imageGroup.setScaleX(s);
        imageGroup.setScaleY(s);
        drawGrid(); // keep lines the same on-screen thickness at any zoom
    }

    /**
     * Blur first, then color. Brightness/contrast/saturation ride on the
     * ColorAdjust only when the picture isn't pixel-processed (which bakes
     * them in itself); grayscale is applied either way.
     */
    private void applyEffect() {
        Effect effect = null;
        if (blur.get()) {
            // Scaled to the fitted image so "squint" looks the same on a small or large window; 63 is GaussianBlur's max.
            Bounds b = imageView.getLayoutBounds();
            effect = new GaussianBlur(Math.max(6, Math.min(63, Math.min(b.getWidth(), b.getHeight()) / 40)));
        }
        boolean live = !pixelMode();
        double sat = grayscale.get() ? -1 : live ? saturation.get() : 0;
        double bright = live ? brightness.get() : 0;
        double cont = live ? contrast.get() : 0;
        if (sat != 0 || bright != 0 || cont != 0) {
            ColorAdjust adjust = new ColorAdjust(0, sat, bright, cont);
            adjust.setInput(effect);
            effect = adjust;
        }
        imageView.setEffect(effect);
        // Keep the filtered result as a bitmap so panning moves it rather than re-running the
        // blur every frame. The default hint reuses the cache only for translation, so zooming
        // still re-renders at the new scale and stays sharp.
        imageView.setCache(effect != null);
    }

    /** Swaps the original and the processed copy as invert / value study turn on and off. */
    private void refreshDisplayed() {
        applyEffect();
        if (source == null || source.getProgress() < 1) return;
        imageView.setImage(displayed());
    }

    private Image displayed() {
        if (!pixelMode() || source == null || source.isError()) return source;
        return process(source, valueLevels.get(), invert.get(), brightness.get(), contrast.get(), saturation.get());
    }

    /**
     * The pixel pass: brightness and contrast (and saturation, for color
     * output), then either posterize luminance into `levels` even gray bands
     * or keep color, then invert. Downsamples to PROCESS_MAX_DIM by reading
     * one source row at a time, so a 50-megapixel original never needs a
     * full-size int array.
     */
    static Image process(Image src, int levels, boolean invert, double brightness, double contrast, double saturation) {
        int sw = (int) src.getWidth(), sh = (int) src.getHeight();
        PixelReader reader = src.getPixelReader();
        if (reader == null || sw <= 0 || sh <= 0) return src;
        double scale = Math.min(1.0, (double) PROCESS_MAX_DIM / Math.max(sw, sh));
        int tw = Math.max(1, (int) Math.round(sw * scale)), th = Math.max(1, (int) Math.round(sh * scale));
        int[] row = new int[sw];
        int[] out = new int[tw * th];
        double gain = 1 + contrast;
        double lift = brightness * 0.5;
        for (int y = 0; y < th; y++) {
            int sy = Math.min(sh - 1, (int) (y / scale));
            reader.getPixels(0, sy, sw, 1, PixelFormat.getIntArgbInstance(), row, 0, sw);
            for (int x = 0; x < tw; x++) {
                int argb = row[Math.min(sw - 1, (int) (x / scale))];
                double r = ((argb >> 16) & 0xff) / 255.0, g = ((argb >> 8) & 0xff) / 255.0, b = (argb & 0xff) / 255.0;
                r = (r - 0.5) * gain + 0.5 + lift;
                g = (g - 0.5) * gain + 0.5 + lift;
                b = (b - 0.5) * gain + 0.5 + lift;
                double lum = 0.299 * r + 0.587 * g + 0.114 * b;
                if (levels > 1) {
                    int band = (int) Math.floor(clamp(lum) * levels);
                    double tone = (double) Math.min(levels - 1, band) / (levels - 1);
                    r = g = b = tone;
                } else {
                    r = lum + (r - lum) * (1 + saturation);
                    g = lum + (g - lum) * (1 + saturation);
                    b = lum + (b - lum) * (1 + saturation);
                }
                if (invert) {
                    r = 1 - clamp(r);
                    g = 1 - clamp(g);
                    b = 1 - clamp(b);
                }
                out[y * tw + x] = (argb & 0xff000000) | (channel(r) << 16) | (channel(g) << 8) | channel(b);
            }
        }
        WritableImage result = new WritableImage(tw, th);
        result.getPixelWriter().setPixels(0, 0, tw, th, PixelFormat.getIntArgbInstance(), out, 0, tw);
        return result;
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static int channel(double v) {
        return (int) Math.round(clamp(v) * 255);
    }

    /** Each line is a dark stroke under a light one, so it reads on both light and dark parts of a picture. */
    private void drawGrid() {
        gridLayer.getChildren().clear();
        int n = gridDivisions.get();
        Bounds b = imageView.getLayoutBounds();
        if (n <= 0 || b.getWidth() <= 0 || b.getHeight() <= 0) return;
        gridLayer.setLayoutX(b.getMinX());
        gridLayer.setLayoutY(b.getMinY());
        double w = b.getWidth(), h = b.getHeight();
        for (int i = 1; i < n; i++) {
            addGridLine(w * i / n, 0, w * i / n, h);
            addGridLine(0, h * i / n, w, h * i / n);
        }
    }

    private void addGridLine(double x1, double y1, double x2, double y2) {
        Line shadow = new Line(x1, y1, x2, y2);
        shadow.setStroke(Color.rgb(0, 0, 0, 0.45));
        double s = scale();
        shadow.setStrokeWidth(3 / s);
        Line line = new Line(x1, y1, x2, y2);
        line.setStroke(Color.rgb(255, 255, 255, 0.85));
        line.setStrokeWidth(1 / s);
        gridLayer.getChildren().addAll(shadow, line);
    }
}
