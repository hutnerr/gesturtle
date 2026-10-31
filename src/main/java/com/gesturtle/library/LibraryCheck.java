package com.gesturtle.library;

import com.gesturtle.model.ImageRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Missing-file detection and relinking. Originals are named by their
 * content hash, so relinking doesn't care what a file is called or where it
 * sits: any file under the chosen folder whose bytes hash to a missing
 * image's hash is copied back into place. Only files whose size matches a
 * missing image are hashed, so pointing at a big folder stays quick.
 */
public final class LibraryCheck {
    private LibraryCheck() {
    }

    public record RelinkResult(int restored, int stillMissing, int filesScanned) {
    }

    public static List<ImageRecord> findMissing(LibraryService library) {
        return library.images().listMissingFiles(library.ref());
    }

    /** Runs on the DB thread (it reads the DAO); file walking and hashing happen inline. */
    public static RelinkResult relinkFrom(LibraryService library, List<ImageRecord> missing, Path folder) throws IOException {
        Map<String, ImageRecord> byHash = new HashMap<>();
        Set<Long> sizes = new HashSet<>();
        for (ImageRecord r : missing) {
            byHash.put(r.hash(), r);
            sizes.add(r.byteSize());
        }
        HashService hashService = new HashService();
        List<Path> candidates = FileScan.files(folder, true);
        int restored = 0;
        for (Path file : candidates) {
            if (byHash.isEmpty()) break;
            try {
                if (!sizes.contains(Files.size(file))) continue;
                ImageRecord match = byHash.remove(hashService.hashFile(file));
                if (match == null) continue;
                Path target = library.ref().imagePath(match.hash(), match.ext());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                try {
                    library.thumbnails().ensureThumbnail(library.ref(), target, match.hash());
                } catch (IOException ignored) {
                    // The thumbnail rebuilds the next time it's shown.
                }
                restored++;
            } catch (IOException ignored) {
                // Unreadable file: skip it and keep looking.
            }
        }
        return new RelinkResult(restored, byHash.size(), candidates.size());
    }
}
