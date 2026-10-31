package com.gesturtle.library;

import com.gesturtle.filter.Filter;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.OrderMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Once per library on each machine: JPEGs imported before EXIF orientation
 * was honored have their width and height as stored (sideways for most
 * phone portraits) and a sideways thumbnail. Each one with an orientation
 * gets its size measured again and its thumbnail rebuilt.
 *
 * Split into three steps so the file reading runs on the I/O pool, not the
 * DB thread. Setting the measured size, rather than swapping, makes it safe
 * when another machine sharing the library through git already fixed it.
 */
public final class OrientationFix {
    /** Local setting: set once this machine's library has been checked. */
    private static final String DONE_KEY = "exif_orientation_checked";

    public record Fix(ImageRecord record, int width, int height) {
    }

    private OrientationFix() {
    }

    /** DB thread: the JPEGs to check, or none if this library was checked already. */
    public static List<ImageRecord> pending(LibraryService library) {
        if (library.settings().get(DONE_KEY).isPresent()) return List.of();
        return library.images().query(new Filter.FormatPredicate("jpg"), OrderMode.SEQUENTIAL, null);
    }

    /** I/O pool: the images whose EXIF says to turn them, with their size as shown. */
    public static List<Fix> measure(LibraryService library, List<ImageRecord> jpegs) {
        List<Fix> fixes = new ArrayList<>();
        for (ImageRecord record : jpegs) {
            Path file = library.ref().imagePath(record.hash(), record.ext());
            if (!Files.isRegularFile(file) || ImageOrientation.read(file) == ImageOrientation.NORMAL) continue;
            try {
                int[] size = ImportService.readDimensions(file);
                fixes.add(new Fix(record, size[0], size[1]));
            } catch (IOException ignored) {
                // Unreadable: left as it is.
            }
        }
        return fixes;
    }

    /** DB thread: saves the sizes, drops the old thumbnails (rebuilt when next shown), and marks the library checked. */
    public static void apply(LibraryService library, List<Fix> fixes) {
        for (Fix fix : fixes) {
            ImageRecord r = fix.record();
            if (r.width() != fix.width() || r.height() != fix.height()) {
                library.images().setDimensions(r.id(), fix.width(), fix.height());
            }
            try {
                Files.deleteIfExists(library.ref().thumbPath(r.hash()));
            } catch (IOException ignored) {
            }
            library.thumbnails().evict(r.hash());
        }
        library.settings().set(DONE_KEY, "1");
    }
}
