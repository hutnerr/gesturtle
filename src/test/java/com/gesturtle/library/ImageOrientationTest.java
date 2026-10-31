package com.gesturtle.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageOrientationTest {

    /** A 32x16 JPEG, left half red and right half blue, stored with EXIF orientation 6 ("turn clockwise"). */
    private static Path phonePortrait(Path dir) throws IOException {
        BufferedImage img = new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 16; y++) img.setRGB(x, y, x < 16 ? 0xFF0000 : 0x0000FF);
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", jpeg);
        byte[] plain = jpeg.toByteArray();
        byte[] exif = {
                (byte) 0xFF, (byte) 0xE1, 0, 34,
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 0x2A, 0, 0, 0, 8,
                0, 1,
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0,
                0, 0, 0, 0};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2); // SOI
        out.write(exif);
        out.write(plain, 2, plain.length - 2);
        Path file = dir.resolve("portrait.jpg");
        Files.write(file, out.toByteArray());
        return file;
    }

    @Test
    void readsTheOrientationAndReportsTheSizeAsShown(@TempDir Path dir) throws IOException {
        Path file = phonePortrait(dir);
        assertEquals(6, ImageOrientation.read(file));
        assertArrayEquals(new int[]{16, 32}, ImportService.readDimensions(file));
    }

    @Test
    void turnsThePixelsClockwise(@TempDir Path dir) throws IOException {
        BufferedImage turned = ImageOrientation.apply(ImageIO.read(phonePortrait(dir).toFile()), 6);
        assertEquals(16, turned.getWidth());
        assertEquals(32, turned.getHeight());
        // The left (red) half ends up on top, the right (blue) half below.
        assertTrue(isMostly(turned.getRGB(8, 4), 0xFF0000));
        assertTrue(isMostly(turned.getRGB(8, 28), 0x0000FF));
    }

    @Test
    void otherFormatsAndPlainJpegsAreUpright(@TempDir Path dir) throws IOException {
        Path png = dir.resolve("a.png");
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        Path jpg = dir.resolve("b.jpg");
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "jpg", jpg.toFile());
        assertEquals(ImageOrientation.NORMAL, ImageOrientation.read(png));
        assertEquals(ImageOrientation.NORMAL, ImageOrientation.read(jpg));
        assertArrayEquals(new int[]{4, 2}, ImportService.readDimensions(jpg));
    }

    /** JPEG is lossy: close enough to the colour. */
    private static boolean isMostly(int rgb, int expected) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((rgb >> shift) & 0xFF) - ((expected >> shift) & 0xFF)) > 60) return false;
        }
        return true;
    }
}
