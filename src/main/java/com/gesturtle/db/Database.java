package com.gesturtle.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the single JDBC connection to a library's gesturtle.db.
 * Meant to be used from the single-threaded DB executor (see
 * ui.common.AsyncTask). Some UI code still calls in from the FX thread; the
 * connection is guarded so those calls wait for an open transaction rather
 * than joining it (see GuardedConnection).
 *
 * Uses a rollback journal rather than WAL so the library folder can be
 * copied or synced as is (a backup drive, a sync service): with WAL, recent
 * changes sit in gesturtle.db-wal until a checkpoint, and copying gesturtle.db alone
 * would leave them behind. With a rollback journal, gesturtle.db itself is
 * complete after every transaction. Git doesn't track it (meta/ is what
 * travels; see meta.MetaStore).
 */
public class Database implements AutoCloseable {
    private final Path dbFile;
    private final Connection connection;
    /** The file's identity (device + inode on Unix) when opened; null where the OS doesn't provide one. */
    private final Object fileKey;

    public Database(Path dbFile) {
        this.dbFile = dbFile;
        try {
            // Guarded so a transaction on one thread can't be joined by another (see GuardedConnection).
            this.connection = GuardedConnection.wrap(DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath()));
            try {
                try (Statement st = connection.createStatement()) {
                    st.execute("PRAGMA foreign_keys = ON");
                    // Also converts a library created in WAL mode, which SQLite otherwise remembers in the file.
                    st.execute("PRAGMA journal_mode = DELETE");
                    st.execute("PRAGMA synchronous = FULL");
                }
                Migrations.applyAll(connection);
            } catch (SQLException | RuntimeException e) {
                // Not left open: on Windows an open file can't be moved or deleted until the app quits.
                connection.close();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open library database at " + dbFile, e);
        }
        this.fileKey = currentFileKey();
    }

    /**
     * Whether gesturtle.db on disk is no longer the file this connection has
     * open, e.g. because a git pull or checkout replaced or removed it (the
     * first pull after another machine stopped tracking it). Git writes a new
     * file rather than editing the old one, so the connection would carry on
     * reading and writing the old, now-deleted file, and every change from
     * then on would be lost when the app closes.
     *
     * Windows has no file identity to compare (fileKey is null), so this is
     * always false there, but it doesn't need to be: SQLite opens the file
     * without delete sharing, so git can't remove or replace it while it's
     * open (the pull fails with "unable to unlink" instead).
     */
    public boolean replacedOnDisk() {
        if (fileKey == null) return false;
        return !fileKey.equals(currentFileKey());
    }

    private Object currentFileKey() {
        try {
            return Files.readAttributes(dbFile, BasicFileAttributes.class).fileKey();
        } catch (IOException e) {
            return null;
        }
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
