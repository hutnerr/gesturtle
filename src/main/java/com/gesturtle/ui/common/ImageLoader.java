package com.gesturtle.ui.common;

import com.gesturtle.library.ImageOrientation;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Loads an image file for display. JavaFX decodes png/jpg/gif/bmp itself
 * (in the background); anything else (WebP) has no JavaFX decoder, so it
 * is decoded through ImageIO (the TwelveMonkeys plugin) on the I/O pool and
 * copied into a WritableImage.
 *
 * JPEGs are checked first, on the I/O pool: one with an EXIF orientation
 * (most phone photos) is decoded through ImageIO and turned the right way
 * up, as is a CMYK one, which JavaFX can't decode at all.
 *
 * The callback may run later than the call returns, so callers that reuse
 * one ImageView (practice, detail view, table cells) must check the result
 * is still for the item they're showing.
 */
public final class ImageLoader {
    private static final Set<String> JAVAFX_FORMATS = Set.of("png", "jpg", "jpeg", "gif", "bmp");

    private ImageLoader() {
    }

    /** width/height 0 means full size; otherwise the image is fitted within them, preserving its ratio. */
    public static void load(Path file, double width, double height, Consumer<Image> onLoaded) {
        String ext = extensionOf(file);
        if (ext.equals("jpg") || ext.equals("jpeg")) {
            AsyncTask.runIo(() -> loadJpeg(file, width, height), onLoaded, err -> onLoaded.accept(null));
            return;
        }
        if (JAVAFX_FORMATS.contains(ext)) {
            onLoaded.accept(new Image(file.toUri().toString(), width, height, true, true, true));
            return;
        }
        AsyncTask.runIo(() -> decode(file, width, height), onLoaded, err -> onLoaded.accept(null));
    }

    /** Runs on the I/O pool. JavaFX's own decoder when it can, ImageIO when the picture needs turning or is CMYK. */
    private static Image loadJpeg(Path file, double width, double height) {
        int orientation = ImageOrientation.read(file);
        if (orientation == ImageOrientation.NORMAL) {
            Image image = new Image(file.toUri().toString(), width, height, true, true, false);
            if (!image.isError()) return image;
        }
        return decode(file, width, height, orientation);
    }

    private static Image decode(Path file, double maxWidth, double maxHeight) {
        return decode(file, maxWidth, maxHeight, ImageOrientation.NORMAL);
    }

    private static Image decode(Path file, double maxWidth, double maxHeight, int orientation) {
        BufferedImage source;
        try {
            source = ImageIO.read(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (source == null) throw new IllegalStateException("No decoder for " + file);
        source = ImageOrientation.apply(source, orientation);
        double scale = 1.0;
        if (maxWidth > 0 && maxHeight > 0) {
            scale = Math.min(1.0, Math.min(maxWidth / source.getWidth(), maxHeight / source.getHeight()));
        }
        int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage argb = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();

        int[] pixels = argb.getRGB(0, 0, w, h, null, 0, w);
        WritableImage image = new WritableImage(w, h);
        image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
        return image;
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
