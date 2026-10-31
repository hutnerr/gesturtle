package com.gesturtle.library;

import com.gesturtle.config.LibraryRef;
import com.gesturtle.db.dao.ImageDao;
import com.gesturtle.db.dao.PlaylistDao;
import com.gesturtle.model.DuplicateAction;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.ImportCandidate;
import com.gesturtle.model.ImportStatus;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Drives import staging and the final batch commit.
 * Formats: png/jpg/jpeg/gif/bmp, plus webp via the TwelveMonkeys ImageIO plugin.
 */
public class ImportService {
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");

    private final LibraryRef library;
    private final Connection connection;
    private final HashService hashService;
    private final ThumbnailCache thumbnailCache;

    public ImportService(LibraryRef library, Connection connection, HashService hashService,
                          ThumbnailCache thumbnailCache) {
        this.library = library;
        this.connection = connection;
        this.hashService = hashService;
        this.thumbnailCache = thumbnailCache;
    }

    public static boolean isSupported(Path file) {
        return SUPPORTED_EXTENSIONS.contains(extensionOf(file));
    }

    /** For an extension that came from outside (an archive, a meta/ file) rather than from a file on disk. */
    public static boolean isSupportedExtension(String ext) {
        return ext != null && SUPPORTED_EXTENSIONS.contains(ext);
    }

    /** Pure probe result; computed off the FX thread, applied to the candidate on it. */
    public record ProbeOutcome(ImportStatus status, String reason, String hash, int width, int height,
                                long byteSize) {
    }

    /** Runs on the DB executor thread (needs imageDao); never touches the candidate's FX properties. */
    public ProbeOutcome probe(Path file, ImageDao imageDao) {
        String ext = extensionOf(file);
        if (!SUPPORTED_EXTENSIONS.contains(ext)) {
            return new ProbeOutcome(ImportStatus.UNREADABLE, "Unsupported format: ." + ext, null, 0, 0, 0);
        }
        try {
            int[] dims = readDimensions(file);
            long size = Files.size(file);
            String hash = hashService.hashFile(file);
            if (imageDao.existsByHash(hash)) {
                return new ProbeOutcome(ImportStatus.ALREADY_IN_LIBRARY, null, hash, dims[0], dims[1], size);
            }
            return new ProbeOutcome(ImportStatus.NEW, null, hash, dims[0], dims[1], size);
        } catch (IOException e) {
            return new ProbeOutcome(ImportStatus.UNREADABLE, "Could not read file: " + e.getMessage(), null, 0, 0, 0);
        }
    }

    /** Marks later duplicates of the same hash within one staging batch. */
    public void markBatchDuplicates(List<ImportCandidate> candidates) {
        Set<String> seen = new HashSet<>();
        for (ImportCandidate c : candidates) {
            if (c.getStatus() != ImportStatus.NEW || c.getHash() == null) continue;
            if (!seen.add(c.getHash())) {
                c.setStatus(ImportStatus.DUPLICATE_IN_BATCH, null);
            }
        }
    }

    /**
     * updated/overwritten count already-present images, per each row's
     * DuplicateAction. failures maps each candidate that couldn't be imported
     * to why, so the staging table can keep it for a retry.
     */
    public record ImportResult(int imported, int updated, int overwritten, int skipped,
                               Map<ImportCandidate, String> failures, boolean cancelled) {
        public int failed() {
            return failures.size();
        }
    }

    /**
     * Commits a batch in one transaction. Copies/moves are performed as the
     * transaction proceeds; on any failure, or if cancelled mid-batch, every
     * file copied so far is removed and the DB transaction is rolled back, so
     * a cancelled or failed import leaves no trace.
     */
    public ImportResult importBatch(List<ImportCandidate> candidates, Set<Integer> batchTagIds, boolean move,
                                     ImageDao imageDao, java.util.concurrent.atomic.AtomicBoolean cancelFlag,
                                     java.util.function.IntConsumer onProgress) {
        List<Path> copiedFiles = new ArrayList<>();
        // Moved sources are only deleted once the batch commits: until then a cancel or a failure later in
        // the batch removes the library copies, and the source must still be there.
        List<Path> movedSources = new ArrayList<>();
        int imported = 0, updated = 0, overwritten = 0, skipped = 0;
        // Identity keys: two staged copies of the same file are still two rows.
        Map<ImportCandidate, String> failures = new IdentityHashMap<>();
        try {
            connection.setAutoCommit(false);
            for (int i = 0; i < candidates.size(); i++) {
                if (cancelFlag != null && cancelFlag.get()) {
                    rollback(copiedFiles);
                    return new ImportResult(0, 0, 0, 0, Map.of(), true);
                }
                ImportCandidate candidate = candidates.get(i);
                onProgress.accept(i);
                ImportStatus status = candidate.getStatus();
                if (status == ImportStatus.UNREADABLE || status == ImportStatus.DUPLICATE_IN_BATCH
                        || !candidate.isIncluded()) {
                    skipped++;
                    continue;
                }
                // Probed as new, but the same picture arrived since (an earlier batch, a git pull while staging).
                // Inserting it again would fail the whole batch, and its rollback would delete the existing file.
                boolean arrivedSince = status == ImportStatus.NEW && imageDao.existsByHash(candidate.getHash());
                if (status == ImportStatus.ALREADY_IN_LIBRARY || arrivedSince) {
                    DuplicateAction action = arrivedSince ? DuplicateAction.UPDATE : candidate.getDuplicateAction();
                    if (action == DuplicateAction.SKIP) {
                        skipped++;
                        continue;
                    }
                    ImageRecord existing = imageDao.findByHash(candidate.getHash()).orElse(null);
                    if (existing == null) {
                        skipped++;
                        continue;
                    }
                    try {
                        if (existing.isDeleted()) {
                            restoreDeleted(existing, candidate.sourcePath(), imageDao, copiedFiles);
                        }
                        String credit = existing.credit() == null ? candidate.credit() : existing.credit();
                        String notes = withNote(existing.notes(), candidate.notes());
                        if (!java.util.Objects.equals(credit, existing.credit()) || !java.util.Objects.equals(notes, existing.notes())) {
                            imageDao.setDetails(existing.id(), credit, notes);
                        }
                        if (action == DuplicateAction.OVERWRITE) {
                            imageDao.replaceTags(existing.id(), allTags(batchTagIds, candidate));
                            imageDao.updateSource(existing.id(), candidate.fileName(),
                                    candidate.sourcePath().toAbsolutePath().toString());
                            overwritten++;
                        } else {
                            imageDao.addTags(existing.id(), allTags(batchTagIds, candidate));
                            updated++;
                        }
                        addToPlaylists(existing.id(), candidate);
                        if (candidate.isFavorite()) imageDao.setFavorite(existing.id(), true);
                    } catch (IOException e) {
                        failures.put(candidate, describe(e));
                    }
                    continue;
                }
                try {
                    Path target = library.imagePath(candidate.getHash(), extensionOf(candidate.sourcePath()));
                    Files.createDirectories(target.getParent());
                    // A file already there (left by an earlier crash, or named by a meta/ file this version
                    // couldn't read) isn't this batch's to delete on rollback; it's reused if it's intact.
                    boolean existed = Files.isRegularFile(target);
                    if (!existed || !hashService.hashFile(target).equals(candidate.getHash())) {
                        Files.copy(candidate.sourcePath(), target, StandardCopyOption.REPLACE_EXISTING);
                        if (!existed) copiedFiles.add(target);
                        String verifyHash = hashService.hashFile(target);
                        if (!verifyHash.equals(candidate.getHash())) {
                            throw new IOException("Hash mismatch after copy for " + candidate.fileName());
                        }
                    }

                    thumbnailCache.ensureThumbnail(library, target, candidate.getHash());

                    int imageId = imageDao.insert(candidate.getHash(), extensionOf(candidate.sourcePath()),
                            candidate.fileName(), candidate.getWidth(), candidate.getHeight(),
                            candidate.getByteSize(), System.currentTimeMillis(),
                            candidate.sourcePath().toAbsolutePath().toString());
                    imageDao.addTags(imageId, allTags(batchTagIds, candidate));
                    addToPlaylists(imageId, candidate);
                    if (candidate.isFavorite()) imageDao.setFavorite(imageId, true);
                    if (candidate.credit() != null || candidate.notes() != null) {
                        imageDao.setDetails(imageId, candidate.credit(), candidate.notes());
                    }

                    if (move) movedSources.add(candidate.sourcePath());
                    imported++;
                } catch (IOException e) {
                    failures.put(candidate, describe(e));
                }
            }
            connection.commit();
            for (Path source : movedSources) {
                try {
                    Files.deleteIfExists(source);
                } catch (IOException ignored) {
                    // It's safely in the library; the original just stays where it was.
                }
            }
        } catch (SQLException | RuntimeException e) {
            rollback(copiedFiles);
            throw new RuntimeException("Import batch failed", e);
        } catch (Error e) {
            // E.g. out of memory decoding a huge image. Rolled back here too: the finally's
            // setAutoCommit(true) would otherwise commit the half-done batch.
            rollback(copiedFiles);
            throw e;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
        return new ImportResult(imported, updated, overwritten, skipped, failures, false);
    }

    /** Re-imports a deleted image in place: copies the file back in (its old one is in the system trash) and clears deleted_at. */
    private void restoreDeleted(ImageRecord existing, Path source, ImageDao imageDao, List<Path> copiedFiles)
            throws IOException {
        Path target = library.imagePath(existing.hash(), existing.ext());
        if (!Files.isRegularFile(target)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            // Removed again on rollback, or the next open's reconcile would bring back an image whose import was cancelled.
            copiedFiles.add(target);
        }
        imageDao.restore(existing.id());
    }

    private void rollback(List<Path> copiedFiles) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
        for (Path p : copiedFiles) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
            }
        }
    }

    /** java.nio's messages are often just a path; say what went wrong instead. */
    private static String describe(IOException e) {
        if (e instanceof java.nio.file.NoSuchFileException) return "the file was moved or deleted";
        if (e instanceof java.nio.file.AccessDeniedException) return "permission denied";
        if (e instanceof java.nio.file.FileSystemException fse && fse.getReason() != null) return fse.getReason();
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Batch tags, then the file's own; addTags lets the later win, so a file's own tag beats a batch sibling of an exclusive parent. */
    private static Set<Integer> allTags(Set<Integer> batchTagIds, ImportCandidate candidate) {
        Set<Integer> all = new LinkedHashSet<>(batchTagIds);
        all.addAll(candidate.tagOverrides());
        return all;
    }

    /** On the same connection, so a failed batch rolls these back with everything else. */
    private void addToPlaylists(int imageId, ImportCandidate candidate) {
        if (candidate.playlistIds().isEmpty()) return;
        PlaylistDao playlists = new PlaylistDao(connection);
        for (int playlistId : candidate.playlistIds()) playlists.addItem(playlistId, imageId);
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Width and height as shown: a JPEG whose EXIF turns it a quarter reports them swapped from its pixels. */
    public static int[] readDimensions(Path file) throws IOException {
        int[] stored = readStoredDimensions(file);
        return ImageOrientation.swapsDimensions(ImageOrientation.read(file)) ? new int[]{stored[1], stored[0]} : stored;
    }

    private static int[] readStoredDimensions(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("No reader for " + file);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        }
    }

    /** Notes with a line added, unless it's already in them (re-importing the same link adds nothing). */
    static String withNote(String notes, String line) {
        if (line == null || line.isBlank()) return notes;
        if (notes == null || notes.isBlank()) return line;
        if (notes.contains(line)) return notes;
        return notes + "\n" + line;
    }
}
