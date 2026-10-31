package com.gesturtle.library;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThumbnailCacheTest {
    @Test
    void downscaleReachesTheExactTargetSize() {
        BufferedImage scaled = ThumbnailCache.downscale(new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB), 512, 384);
        assertEquals(512, scaled.getWidth());
        assertEquals(384, scaled.getHeight());
    }

    @Test
    void downscaleKeepsASmallImageAtItsOwnSize() {
        BufferedImage scaled = ThumbnailCache.downscale(new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB), 300, 200);
        assertEquals(300, scaled.getWidth());
        assertEquals(200, scaled.getHeight());
        assertEquals(BufferedImage.TYPE_INT_RGB, scaled.getType());
    }
}
