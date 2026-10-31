package com.gesturtle.library;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A JPEG's EXIF orientation: phones save the sensor's pixels as they came
 * and record which way up to show them, and neither JavaFX nor ImageIO
 * applies it, so a portrait phone photo would show sideways and report its
 * width and height swapped. Reads just the tag (no metadata library), and
 * turns decoded pixels the right way up.
 */
public final class ImageOrientation {
    /** Orientation 1: stored the right way up. */
    public static final int NORMAL = 1;

    private ImageOrientation() {
    }

    /** The EXIF orientation (1-8) of a JPEG, or NORMAL for other formats and anything unreadable. */
    public static int read(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".jpg") && !name.endsWith(".jpeg")) return NORMAL;
        try (InputStream in = Files.newInputStream(file)) {
            return readJpeg(new DataInputStream(new java.io.BufferedInputStream(in)));
        } catch (IOException | RuntimeException e) {
            return NORMAL;
        }
    }

    /** Orientations 5-8 turn the image a quarter, so its shown width is its stored height. */
    public static boolean swapsDimensions(int orientation) {
        return orientation >= 5 && orientation <= 8;
    }

    /** The pixels turned (and mirrored, for 2, 4, 5 and 7) the right way up. */
    public static BufferedImage apply(BufferedImage source, int orientation) {
        if (orientation <= NORMAL || orientation > 8) return source;
        int w = source.getWidth();
        int h = source.getHeight();
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { }
        }
        boolean swap = swapsDimensions(orientation);
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, type);
        Graphics2D g = out.createGraphics();
        g.drawImage(source, t, null);
        g.dispose();
        return out;
    }

    /** Walks the JPEG's segments to APP1 "Exif", then its first IFD to tag 0x0112. */
    private static int readJpeg(DataInputStream in) throws IOException {
        if (in.readUnsignedShort() != 0xFFD8) return NORMAL;
        while (true) {
            int marker = in.readUnsignedShort();
            if ((marker & 0xFF00) != 0xFF00) return NORMAL;
            // Start of scan: the image data begins, and EXIF always comes before it.
            if (marker == 0xFFDA || marker == 0xFFD9) return NORMAL;
            int length = in.readUnsignedShort() - 2;
            if (length < 0) return NORMAL;
            if (marker == 0xFFE1 && length >= 14) {
                byte[] data = new byte[length];
                in.readFully(data);
                if (data[0] == 'E' && data[1] == 'x' && data[2] == 'i' && data[3] == 'f' && data[4] == 0 && data[5] == 0) {
                    return readTiff(data, 6);
                }
            } else {
                skipFully(in, length);
            }
        }
    }

    private static int readTiff(byte[] d, int start) {
        boolean little = d[start] == 'I' && d[start + 1] == 'I';
        if (!little && !(d[start] == 'M' && d[start + 1] == 'M')) return NORMAL;
        int ifd = start + int32(d, start + 4, little);
        if (ifd < start || ifd + 2 > d.length) return NORMAL;
        int count = int16(d, ifd, little);
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > d.length) return NORMAL;
            if (int16(d, entry, little) == 0x0112) {
                int value = int16(d, entry + 8, little);
                return value >= 1 && value <= 8 ? value : NORMAL;
            }
        }
        return NORMAL;
    }

    private static int int16(byte[] d, int at, boolean little) {
        int a = d[at] & 0xFF;
        int b = d[at + 1] & 0xFF;
        return little ? (b << 8) | a : (a << 8) | b;
    }

    private static int int32(byte[] d, int at, boolean little) {
        int a = int16(d, at, little);
        int b = int16(d, at + 2, little);
        return little ? (b << 16) | a : (a << 16) | b;
    }

    private static void skipFully(DataInputStream in, int n) throws IOException {
        while (n > 0) {
            int skipped = in.skipBytes(n);
            if (skipped <= 0) throw new EOFException();
            n -= skipped;
        }
    }
}
