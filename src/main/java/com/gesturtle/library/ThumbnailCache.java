package com.gesturtle.library;

import com.gesturtle.config.LibraryRef;
import com.gesturtle.model.ImageRecord;
import javafx.scene.image.Image;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Generates and serves cached thumbnails. Disk cache lives under
 * <library>/thumbs (disposable, regenerable); a small in-memory LRU on top
 * avoids re-decoding the same thumbnail during fast gallery scrolling.
 *
 * thumbs/ is left out of git (see LibraryRef), so a library cloned onto
 * another machine has none: a missing thumbnail is rebuilt in the
 * background the first time it's asked for.
 */
public class ThumbnailCache {
    /** Big enough to stay sharp on a 200% display at the largest tile size. */
    private static final int THUMB_MAX_DIM = 512;
    private static final float JPEG_QUALITY = 0.9f;
    private static final int MEMORY_CACHE_CAPACITY = 500;
    /** What JavaFX's Image can decode itself; WebP needs ImageIO. */
    private static final Set<String> JAVAFX_FORMATS = Set.of("png", "jpg", "jpeg", "gif", "bmp");

    private final ExecutorService generator = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "thumbnail-generator");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> generating = ConcurrentHashMap.newKeySet();

    private final Map<String, Image> memoryCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > MEMORY_CACHE_CAPACITY;
        }
    };

    /** Generates the on-disk thumbnail for a newly imported image, if not already present. */
    public void ensureThumbnail(LibraryRef lib, Path originalFile, String hash) throws IOException {
        Path thumbPath = lib.thumbPath(hash);
        if (Files.isRegularFile(thumbPath)) return;
        Files.createDirectories(thumbPath.getParent());

        BufferedImage source = ImageIO.read(originalFile.toFile());
        if (source == null) {
            throw new IOException("Could not decode image for thumbnailing: " + originalFile);
        }
        source = ImageOrientation.apply(source, ImageOrientation.read(originalFile));
        double scale = Math.min(1.0, (double) THUMB_MAX_DIM / Math.max(source.getWidth(), source.getHeight()));
        int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage scaled = downscale(source, w, h);

        // Written aside and moved into place, so a reader never sees half a file.
        Path partial = thumbPath.resolveSibling(thumbPath.getFileName() + ".part");
        writeJpeg(scaled, partial);
        Files.move(partial, thumbPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Halves the image in bilinear steps until one last step reaches the
     * target. A single bilinear draw from a 4000px original only samples a
     * few source pixels per thumbnail pixel, which aliases badly (jaggies,
     * moire); each halving step averages every pixel. Drawn onto white, as
     * JPEG has no alpha.
     */
    static BufferedImage downscale(BufferedImage source, int w, int h) {
        BufferedImage current = source;
        int cw = source.getWidth();
        int ch = source.getHeight();
        do {
            cw = Math.max(w, cw / 2);
            ch = Math.max(h, ch / 2);
            if (cw / 2 < w || ch / 2 < h) {
                cw = w;
                ch = h;
            }
            BufferedImage next = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(current, 0, 0, cw, ch, java.awt.Color.WHITE, null);
            g.dispose();
            current = next;
        } while (cw != w || ch != h);
        return current;
    }

    /** ImageIO's default JPEG quality (0.75) leaves visible blocks when the thumbnail is shown near 1:1. */
    private static void writeJpeg(BufferedImage image, Path file) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /**
     * The image's thumbnail. If it isn't on disk yet, one is generated in
     * the background, and meanwhile the original is shown scaled down where
     * JavaFX can decode it (null for WebP until the thumbnail exists).
     */
    public synchronized Image load(LibraryRef lib, ImageRecord record) {
        String hash = record.hash();
        Image cached = memoryCache.get(hash);
        if (cached != null) return cached;
        Path thumbPath = lib.thumbPath(hash);
        if (Files.isRegularFile(thumbPath)) {
            Image image = new Image(thumbPath.toUri().toString(), THUMB_MAX_DIM, THUMB_MAX_DIM, true, true, true);
            memoryCache.put(hash, image);
            return image;
        }
        Path original = lib.imagePath(hash, record.ext());
        if (!Files.isRegularFile(original)) return null;
        if (generating.add(hash)) {
            generator.submit(() -> {
                try {
                    ensureThumbnail(lib, original, hash);
                } catch (IOException | RuntimeException ignored) {
                    // Left missing; the original stands in, and the next load tries again.
                } finally {
                    generating.remove(hash);
                }
            });
        }
        if (!JAVAFX_FORMATS.contains(record.ext().toLowerCase(Locale.ROOT))) return null;
        // JavaFX would show it sideways; better a moment's blank tile until the thumbnail is ready.
        if (ImageOrientation.read(original) != ImageOrientation.NORMAL) return null;
        Image image = new Image(original.toUri().toString(), THUMB_MAX_DIM, THUMB_MAX_DIM, true, true, true);
        memoryCache.put(hash, image);
        return image;
    }

    public void evict(String hash) {
        synchronized (this) {
            memoryCache.remove(hash);
        }
    }
}
