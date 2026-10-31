package com.gesturtle.library;

import com.gesturtle.config.LibraryRef;
import com.gesturtle.db.Database;
import com.gesturtle.db.dao.ImageDao;
import com.gesturtle.db.dao.PlaylistDao;
import com.gesturtle.db.dao.PresetDao;
import com.gesturtle.db.dao.SavedViewDao;
import com.gesturtle.db.dao.SettingDao;
import com.gesturtle.db.dao.TagDao;
import com.gesturtle.db.dao.ViewDao;
import com.gesturtle.meta.MetaStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opens one library root and owns everything needed to work with it: the DB
 * connection, DAOs, the meta/ files git tracks, and the import/thumbnail
 * services. Passed around the UI as a single context object rather than
 * wiring each dependency separately.
 *
 * Opening reads meta/ into the database, which takes a moment the first
 * time on a machine (or after a big git pull), so do it off the FX thread.
 */
public class LibraryService implements AutoCloseable {
    private final LibraryRef ref;
    private final Database database;
    private final ImageDao imageDao;
    private final TagDao tagDao;
    private final PlaylistDao playlistDao;
    private final ViewDao viewDao;
    private final PresetDao presetDao;
    private final SettingDao settingDao;
    private final SavedViewDao savedViewDao;
    private final MetaStore metaStore;
    private final MetaStore.SyncResult openedWith;
    private final ThumbnailCache thumbnailCache;
    private final ImportService importService;
    private final LibraryLock lock;
    private volatile boolean closed;

    public LibraryService(String libraryRootPath) {
        this.ref = LibraryRef.of(libraryRootPath);
        try {
            ref.ensureLayout();
        } catch (IOException e) {
            throw new RuntimeException("Could not create library layout at " + libraryRootPath, e);
        }
        this.lock = LibraryLock.acquire(ref.lockFile());
        Database opened = null;
        try {
            opened = new Database(ref.dbFile());
            this.metaStore = new MetaStore(opened.connection(), ref.metaDir());
            this.openedWith = metaStore.open();
        } catch (RuntimeException e) {
            if (opened != null) opened.close();
            lock.close();
            throw e;
        }
        this.database = opened;
        this.imageDao = new ImageDao(database.connection());
        this.tagDao = new TagDao(database.connection());
        this.playlistDao = new PlaylistDao(database.connection());
        this.viewDao = new ViewDao(database.connection());
        this.presetDao = new PresetDao(database.connection());
        this.settingDao = new SettingDao(database.connection());
        this.savedViewDao = new SavedViewDao(database.connection());
        this.thumbnailCache = new ThumbnailCache();
        this.importService = new ImportService(ref, database.connection(), new HashService(), thumbnailCache);
    }

    /** What reading meta/ found when the library opened (e.g. files git left conflicted). */
    public MetaStore.SyncResult openedWith() {
        return openedWith;
    }

    /** Writes changed meta/ files; call after each piece of DB work. */
    public void flushMeta() {
        if (!closed) metaStore.flush();
    }

    /** Whether meta/ changed outside Gesturtle (a git pull) since it was last read. */
    public boolean metaChangedOnDisk() {
        return metaStore.changedOnDisk();
    }

    /** Reads meta/ changes made outside Gesturtle into the database. */
    public MetaStore.SyncResult syncMeta() {
        return metaStore.sync();
    }

    /** See {@link Database#replacedOnDisk()}. */
    public boolean databaseReplacedOnDisk() {
        return database.replacedOnDisk();
    }

    /** Reads the rows an action is about to change, so it can be undone; see UndoCapture. */
    public UndoCapture captureForUndo(UndoCapture.Table... tables) {
        return UndoCapture.before(database.connection(), tables);
    }

    public interface Work<T, E extends Exception> {
        T run() throws E;
    }

    /**
     * Runs work as one transaction: everything it changes is saved together
     * or, if it throws, not at all. DAO methods that open their own
     * transaction join this one. DB thread only.
     */
    public <T, E extends Exception> T inTransaction(Work<T, E> work) throws E {
        java.sql.Connection connection = database.connection();
        try {
            connection.setAutoCommit(false);
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (java.sql.SQLException e) {
            rollbackQuietly(connection);
            throw new RuntimeException(e);
        } catch (Exception | Error e) {
            rollbackQuietly(connection);
            throw e;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (java.sql.SQLException ignored) {
            }
        }
    }

    private static void rollbackQuietly(java.sql.Connection connection) {
        try {
            connection.rollback();
        } catch (java.sql.SQLException ignored) {
        }
    }

    public LibraryRef ref() {
        return ref;
    }

    public ImageDao images() {
        return imageDao;
    }

    public TagDao tags() {
        return tagDao;
    }

    public PlaylistDao playlists() {
        return playlistDao;
    }

    public ViewDao views() {
        return viewDao;
    }

    public PresetDao presets() {
        return presetDao;
    }

    public SettingDao settings() {
        return settingDao;
    }

    /** Saved Gallery queries; not to be confused with views(), the practice-session log. */
    public SavedViewDao savedViews() {
        return savedViewDao;
    }

    public ThumbnailCache thumbnails() {
        return thumbnailCache;
    }

    public ImportService importService() {
        return importService;
    }

    public Path root() {
        return ref.root();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            metaStore.flush();
        } finally {
            database.close();
            lock.close();
        }
    }

    /**
     * Permanently removes everything Gesturtle keeps in a library folder: the
     * database (and its SQLite side files), meta/, originals, and
     * thumbnails. The folder itself and exports/ are left: the folder was
     * the user's choice and may hold other things, and exports/ is where
     * library backups land. Close the library first.
     */
    public static void deleteLibraryFiles(LibraryRef ref) throws IOException {
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal", ".lock"}) {
            Files.deleteIfExists(ref.dbFile().resolveSibling(ref.dbFile().getFileName() + suffix));
        }
        for (Path dir : new Path[]{ref.metaDir(), ref.imagesDir(), ref.thumbsDir()}) {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            // Deepest first, so each directory is empty by the time it's deleted.
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
