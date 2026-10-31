package com.gesturtle.library;

import com.gesturtle.model.ImageRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deleting an image sends its original to the system trash and hides its row
 * (deleted_at), keeping tags and history. Nothing else is needed to undo it:
 * restoring the file from the trash puts it back at its content-addressed
 * path, and {@link #reconcile} brings the row back the next time the library
 * opens. Re-importing the same picture restores it too.
 */
public final class ImageTrash {
    private ImageTrash() {
    }

    /** Trashes the image's original (if it's still there) and hides the image. */
    public static void delete(LibraryService library, ImageRecord record) {
        Path original = library.ref().imagePath(record.hash(), record.ext());
        try {
            if (Files.isRegularFile(original)) SystemTrash.moveToTrash(original);
            Files.deleteIfExists(library.ref().thumbPath(record.hash()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        library.images().softDelete(record.id(), System.currentTimeMillis());
        library.thumbnails().evict(record.hash());
    }

    /**
     * Run on open, on the DB thread: un-hides deleted images whose file is
     * back (restored from the system trash). Returns how many came back.
     */
    public static int reconcile(LibraryService library) {
        int restored = 0;
        for (ImageRecord record : library.images().listDeleted()) {
            if (Files.isRegularFile(library.ref().imagePath(record.hash(), record.ext()))) {
                library.images().restore(record.id());
                restored++;
            }
        }
        return restored;
    }
}
