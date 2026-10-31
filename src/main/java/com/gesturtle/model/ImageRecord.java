package com.gesturtle.model;

import java.util.List;

/** A library image's metadata row. Named ImageRecord to avoid clashing with javafx.scene.image.Image. */
public record ImageRecord(
        int id,
        String hash,
        String ext,
        String originalName,
        int width,
        int height,
        long byteSize,
        long importedAt,
        String sourcePath,
        boolean favorite,
        Long lastSeenAt,
        int seenCount,
        Long deletedAt,
        List<Integer> tagIds,
        /** Free text the user fills in: where the image came from (photographer, site, URL). Null when blank. */
        String credit,
        /** Free text the user fills in: model names, anything else. Null when blank. */
        String notes
) {
    public ImageRecord withFavorite(boolean value) {
        return new ImageRecord(id, hash, ext, originalName, width, height, byteSize, importedAt, sourcePath,
                value, lastSeenAt, seenCount, deletedAt, tagIds, credit, notes);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
