package com.gesturtle.ui.components;

import javafx.beans.value.ObservableValue;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Window;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The turtle-shell mark, drawn as pixel art. The bundled image is the art at
 * 1x (one image pixel per art pixel, see packaging/MakeIcons.java).
 *
 * Letting the ImageView scale it doesn't stay crisp: Prism still filters the
 * texture with setSmooth(false) in some paths, and at 125% display scaling a
 * mark sized at 2 logical px per art pixel lands on 2.5 physical px, so the
 * art pixels come out alternately 2 and 3 px wide. Instead, the size asked
 * for is a target that gets snapped to a whole number of physical pixels per
 * art pixel for the window's screen, the image is upscaled to exactly that
 * many physical pixels here, and fitWidth maps it 1:1 onto the screen. It
 * re-snaps when the window moves to a screen with a different scale.
 */
public class Logo extends ImageView {
    private static final Image MARK = load("/logo/mark.png");
    /** Upscaled copies of MARK by whole-number factor; FX thread only. */
    private static final Map<Integer, Image> SCALED = new HashMap<>();

    private final double pixelSize;

    /** @param pixelSize roughly how many logical px each art pixel should cover */
    public Logo(double pixelSize) {
        super(MARK);
        this.pixelSize = pixelSize;
        getStyleClass().add("logo");
        setSmooth(false);
        setPreserveRatio(true);
        ObservableValue<Number> renderScale = sceneProperty()
                .flatMap(Scene::windowProperty)
                .flatMap(Window::renderScaleXProperty)
                .orElse(1.0);
        renderScale.subscribe(scale -> snap(scale.doubleValue()));
    }

    private void snap(double renderScale) {
        int factor = (int) Math.max(1, Math.round(pixelSize * renderScale));
        Image image = SCALED.computeIfAbsent(factor, Logo::upscale);
        setImage(image);
        setFitWidth(image.getWidth() / renderScale);
    }

    private static Image upscale(int factor) {
        int w = (int) MARK.getWidth(), h = (int) MARK.getHeight();
        PixelReader in = MARK.getPixelReader();
        WritableImage out = new WritableImage(w * factor, h * factor);
        for (int y = 0; y < h * factor; y++) {
            for (int x = 0; x < w * factor; x++) {
                out.getPixelWriter().setArgb(x, y, in.getArgb(x / factor, y / factor));
            }
        }
        return out;
    }

    /** Window/taskbar icons at every size the OS might ask for; it picks the closest. */
    public static List<Image> windowIcons() {
        return Stream.of(32, 64, 128, 256).map(size -> load("/logo/icon-" + size + ".png")).toList();
    }

    private static Image load(String path) {
        return new Image(Objects.requireNonNull(Logo.class.getResource(path), path).toExternalForm());
    }
}
