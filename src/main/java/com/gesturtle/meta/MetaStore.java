package com.gesturtle.meta;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a library's meta/ folder and its database in step. meta/ is what git
 * tracks: a small JSON file per image, tag, playlist, view, and preset, plus
 * settings.json for the few settings every machine should share. gesturtle.db
 * is a local copy built from those files (so filters and search stay fast)
 * that also holds what's this machine's own: practice history, seen counts,
 * and the rest of the settings. It's left out of git, so practicing never
 * changes anything git sees.
 *
 * Database → files: triggers note each changed thing in meta_dirty, and
 * {@link #flush()} rewrites those files. Files → database: {@link #sync()}
 * re-reads files whose size or modified time differ from meta_file's record
 * (after a git pull, say) and drops things whose file is gone.
 *
 * Like the database, not thread-safe: call it from the DB thread.
 */
public class MetaStore {
    /** Shared settings, kept in meta/settings.json; every other setting stays on this machine. */
    static final String BUILT_INS_KEY = "builtin_views";
    static final String DEFAULT_VIEW_KEY = "gallery_default_view";
    /** Local setting: set once this database's contents have been written out to meta/. */
    private static final String EXPORTED_KEY = "meta_exported";

    private final Connection connection;
    private final Path metaDir;
    private final MetaWriter writer;

    public MetaStore(Connection connection, Path metaDir) {
        this.connection = connection;
        this.metaDir = metaDir;
        this.writer = new MetaWriter(connection);
        try {
            installTriggers();
        } catch (SQLException e) {
            throw new RuntimeException("Could not set up change tracking for meta/", e);
        }
    }

    /**
     * What reading meta/ found: how many files it applied, any it couldn't
     * read, and any written by a newer Gesturtle than this one (meta/-relative
     * paths). Files in either list are left unapplied.
     */
    public record SyncResult(int applied, List<String> unreadable, List<String> tooNew) {
        public boolean changed() {
            return applied > 0;
        }
    }

    /**
     * Brings both sides together when the library opens. The first time a
     * database meets meta/ (a new library, or a fresh clone with no database
     * yet), whatever is in meta/ is read in, then
     * everything in the database is written out, so neither side loses
     * anything. After that, it's leftover changes out, then the files' changes in.
     */
    public SyncResult open() {
        if (setting(EXPORTED_KEY) == null) {
            SyncResult result = sync();
            markAllDirty();
            flush();
            setSetting(EXPORTED_KEY, "1");
            return result;
        }
        flush();
        return sync();
    }

    /** Whether any meta/ file was added, changed, or removed since it was last read or written. */
    public boolean changedOnDisk() {
        try {
            Map<String, long[]> known = knownFiles();
            Map<String, long[]> onDisk = filesOnDisk();
            if (known.size() != onDisk.size()) return true;
            for (var e : onDisk.entrySet()) {
                if (!Arrays.equals(e.getValue(), known.get(e.getKey()))) return true;
            }
            return false;
        } catch (IOException | SQLException e) {
            return false;
        }
    }

    /** Reads changed meta/ files into the database and drops what was removed. See the class comment. */
    public SyncResult sync() {
        try {
            Map<String, long[]> known = knownFiles();
            Map<String, long[]> onDisk = filesOnDisk();
            Map<String, Map<String, JsonObject>> changed = new HashMap<>();
            Map<String, Set<String>> removed = new HashMap<>();
            Map<String, long[]> applied = new LinkedHashMap<>();
            List<String> unreadable = new ArrayList<>();
            List<String> tooNew = new ArrayList<>();

            for (var e : onDisk.entrySet()) {
                if (Arrays.equals(e.getValue(), known.get(e.getKey()))) continue;
                MetaFiles.Entry entry = MetaFiles.parse(e.getKey());
                try {
                    JsonObject json = JsonParser.parseString(Files.readString(metaDir.resolve(e.getKey()), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    if (json.has(MetaWriter.VERSION_KEY) && json.get(MetaWriter.VERSION_KEY).getAsInt() > MetaWriter.FORMAT_VERSION) {
                        tooNew.add(e.getKey());
                        continue;
                    }
                    changed.computeIfAbsent(entry.kind(), k -> new LinkedHashMap<>()).put(entry.key(), json);
                    applied.put(e.getKey(), e.getValue());
                } catch (RuntimeException | IOException ex) {
                    // Most likely git conflict markers. Leave the thing as it is until the file is fixed.
                    unreadable.add(e.getKey());
                }
            }
            for (String path : known.keySet()) {
                if (onDisk.containsKey(path)) continue;
                MetaFiles.Entry entry = MetaFiles.parse(path);
                if (entry != null) removed.computeIfAbsent(entry.kind(), k -> new HashSet<>()).add(entry.key());
            }
            if (changed.isEmpty() && removed.isEmpty()) return new SyncResult(0, unreadable, tooNew);

            MetaReader reader = new MetaReader(connection);
            inTransaction(() -> {
                // Changes still waiting to be written (a flush that failed, DB work not yet flushed)
                // sit below this rowid and must survive; only what the reader adds is cleared.
                long pending;
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT coalesce(max(rowid), 0) FROM meta_dirty")) {
                    pending = rs.getLong(1);
                }
                reader.apply(changed, removed);
                // The triggers noted everything just read in; it already matches its file, so only
                // what the reader changed on the way (merged tags, renamed views) needs writing back.
                try (PreparedStatement ps = connection.prepareStatement("DELETE FROM meta_dirty WHERE rowid > ?")) {
                    ps.setLong(1, pending);
                    ps.executeUpdate();
                }
                for (MetaFiles.Entry entry : reader.rewrite()) markDirty(entry.kind(), entry.key());
                // Left unrecorded, like a file with conflict markers, so it's read again once fixed.
                for (MetaFiles.Entry entry : reader.failed()) {
                    String path = MetaFiles.path(entry.kind(), entry.key());
                    applied.remove(path);
                    unreadable.add(path);
                }
                for (var e : applied.entrySet()) recordFile(e.getKey(), e.getValue());
                for (String path : known.keySet()) {
                    if (!onDisk.containsKey(path)) forgetFile(path);
                }
            });
            flush();
            int removedCount = removed.values().stream().mapToInt(Set::size).sum();
            return new SyncResult(applied.size() + removedCount, unreadable, tooNew);
        } catch (IOException | SQLException e) {
            throw new RuntimeException("Could not read the library's meta/ folder", e);
        }
    }

    /**
     * Writes the file of everything changed since the last flush (deleting the
     * files of things that are gone). A file that can't be written stays
     * marked and is tried again next time.
     */
    public void flush() {
        List<MetaFiles.Entry> dirty = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT kind, key FROM meta_dirty")) {
            while (rs.next()) dirty.add(new MetaFiles.Entry(rs.getString(1), rs.getString(2)));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        IOException failure = null;
        for (MetaFiles.Entry entry : dirty) {
            try {
                write(entry);
                try (PreparedStatement ps = connection.prepareStatement("DELETE FROM meta_dirty WHERE kind = ? AND key = ?")) {
                    ps.setString(1, entry.kind());
                    ps.setString(2, entry.key());
                    ps.executeUpdate();
                }
            } catch (IOException e) {
                failure = e;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } catch (RuntimeException e) {
                // A row the writer can't make sense of mustn't hold up every other file behind it.
                failure = new IOException("Could not write " + entry.kind() + " " + entry.key(), e);
            }
        }
        if (failure != null) throw new java.io.UncheckedIOException("Could not save to the library's meta/ folder", failure);
    }

    private void write(MetaFiles.Entry entry) throws IOException, SQLException {
        String path = MetaFiles.path(entry.kind(), entry.key());
        Path file = metaDir.resolve(path);
        String text = writer.write(entry.kind(), entry.key());
        if (text == null) {
            Files.deleteIfExists(file);
            forgetFile(path);
            return;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        // A newer Gesturtle wrote this file; writing it in the older format would lose whatever it added.
        if (writtenByNewerVersion(file)) return;
        // Same bytes: leave the file (and its modified time) alone, so git and sync see no change.
        if (!(Files.isRegularFile(file) && Arrays.equals(Files.readAllBytes(file), bytes))) {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".part");
            Files.write(temp, bytes);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        recordFile(path, stamp(file));
    }

    private static boolean writtenByNewerVersion(Path file) {
        if (!Files.isRegularFile(file)) return false;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return json.has(MetaWriter.VERSION_KEY) && json.get(MetaWriter.VERSION_KEY).getAsInt() > MetaWriter.FORMAT_VERSION;
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    private void markAllDirty() {
        inTransaction(() -> {
            try (Statement st = connection.createStatement()) {
                st.execute("INSERT INTO meta_dirty SELECT 'image', hash FROM image");
                st.execute("INSERT INTO meta_dirty SELECT 'tag', uid FROM tag WHERE uid IS NOT NULL");
                st.execute("INSERT INTO meta_dirty SELECT 'playlist', uid FROM playlist WHERE uid IS NOT NULL");
                st.execute("INSERT INTO meta_dirty SELECT 'view', uid FROM saved_view WHERE uid IS NOT NULL");
                st.execute("INSERT INTO meta_dirty SELECT 'preset', uid FROM preset WHERE uid IS NOT NULL");
                st.execute("INSERT INTO meta_dirty VALUES ('settings', '')");
            }
        });
    }

    // ---------- Change tracking ----------

    /**
     * Triggers that note each change in meta_dirty, keyed by hash or uid (so
     * a deleted row still says which file to remove), and give new tags,
     * playlists, views, and presets a uid. Stat columns (seen counts, last
     * seen, preset use) have no trigger: they're this machine's alone.
     */
    private void installTriggers() throws SQLException {
        List<String> triggers = new ArrayList<>();
        for (String table : List.of("tag", "playlist", "saved_view", "preset")) {
            triggers.add("CREATE TRIGGER IF NOT EXISTS meta_" + table + "_uid AFTER INSERT ON " + table
                    + " WHEN NEW.uid IS NULL BEGIN UPDATE " + table
                    + " SET uid = lower(hex(randomblob(16))) WHERE id = NEW.id; END");
        }
        String imageColumns = "ext, original_name, width, height, byte_size, imported_at, source_path, favorite, "
                + "deleted_at, credit, notes";
        triggers.add(onInsert("image", "", mark("image", "NEW.hash")));
        triggers.add("CREATE TRIGGER IF NOT EXISTS meta_image_update AFTER UPDATE OF " + imageColumns
                + " ON image BEGIN " + mark("image", "NEW.hash") + " END");
        triggers.add(onDelete("image", mark("image", "OLD.hash")));
        triggers.add(onInsert("image_tag", "", markVia("image", "hash", "image", "NEW.image_id")));
        triggers.add(onDelete("image_tag", markVia("image", "hash", "image", "OLD.image_id")));

        // Named things: tags, playlists, views, presets. A uid that changes (MetaReader merging two
        // tags) marks the old one too, so its file goes.
        Map<String, String> kinds = Map.of("tag", "tag", "playlist", "playlist", "saved_view", "view", "preset", "preset");
        for (var e : kinds.entrySet()) {
            String table = e.getKey();
            String kind = e.getValue();
            String columns = table.equals("preset") ? " OF uid, name, created_at, pinned" : "";
            triggers.add(onInsert(table, " WHEN NEW.uid IS NOT NULL", mark(kind, "NEW.uid")));
            triggers.add("CREATE TRIGGER IF NOT EXISTS meta_" + table + "_update AFTER UPDATE" + columns + " ON " + table
                    + " WHEN NEW.uid IS NOT NULL BEGIN " + mark(kind, "NEW.uid")
                    + " INSERT INTO meta_dirty SELECT '" + kind + "', OLD.uid WHERE OLD.uid IS NOT NULL AND OLD.uid IS NOT NEW.uid; END");
            triggers.add(onDelete(table, "INSERT INTO meta_dirty SELECT '" + kind + "', OLD.uid WHERE OLD.uid IS NOT NULL;"));
        }
        // A child tag's file names its parent by uid, so it changes when the parent's uid does.
        triggers.add("CREATE TRIGGER IF NOT EXISTS meta_tag_children AFTER UPDATE OF uid ON tag BEGIN "
                + "INSERT INTO meta_dirty SELECT 'tag', uid FROM tag WHERE parent_id = NEW.id AND uid IS NOT NULL; END");
        for (String op : List.of("INSERT", "UPDATE", "DELETE")) {
            String row = op.equals("DELETE") ? "OLD" : "NEW";
            triggers.add("CREATE TRIGGER IF NOT EXISTS meta_playlist_item_" + op.toLowerCase() + " AFTER " + op
                    + " ON playlist_item BEGIN " + markVia("playlist", "uid", "playlist", row + ".playlist_id") + " END");
            triggers.add("CREATE TRIGGER IF NOT EXISTS meta_segment_" + op.toLowerCase() + " AFTER " + op
                    + " ON segment BEGIN " + markVia("preset", "uid", "preset", row + ".preset_id") + " END");
            triggers.add("CREATE TRIGGER IF NOT EXISTS meta_setting_" + op.toLowerCase() + " AFTER " + op
                    + " ON setting WHEN " + row + ".key IN ('" + BUILT_INS_KEY + "', '" + DEFAULT_VIEW_KEY + "') BEGIN "
                    + "INSERT INTO meta_dirty VALUES ('settings', ''); END");
        }
        try (Statement st = connection.createStatement()) {
            for (String sql : triggers) st.execute(sql);
        }
    }

    private static String onInsert(String table, String when, String body) {
        return "CREATE TRIGGER IF NOT EXISTS meta_" + table + "_insert AFTER INSERT ON " + table + when
                + " BEGIN " + body + " END";
    }

    private static String onDelete(String table, String body) {
        return "CREATE TRIGGER IF NOT EXISTS meta_" + table + "_delete AFTER DELETE ON " + table + " BEGIN " + body + " END";
    }

    private static String mark(String kind, String keyExpression) {
        return "INSERT INTO meta_dirty VALUES ('" + kind + "', " + keyExpression + ");";
    }

    /** Marks the row of table with this id, by its keyColumn; nothing if that row is already gone. */
    private static String markVia(String kind, String keyColumn, String table, String idExpression) {
        return "INSERT INTO meta_dirty SELECT '" + kind + "', " + keyColumn + " FROM " + table
                + " WHERE id = " + idExpression + " AND " + keyColumn + " IS NOT NULL;";
    }

    private void markDirty(String kind, String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO meta_dirty VALUES (?, ?)")) {
            ps.setString(1, kind);
            ps.setString(2, key);
            ps.executeUpdate();
        }
    }

    // ---------- File bookkeeping ----------

    /** Every file Gesturtle wrote under meta/, by meta/-relative path, with its size and modified time. */
    private Map<String, long[]> filesOnDisk() throws IOException {
        Map<String, long[]> files = new HashMap<>();
        if (!Files.isDirectory(metaDir)) return files;
        try (var walk = Files.walk(metaDir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String path = MetaFiles.relative(metaDir, p);
                if (MetaFiles.parse(path) != null) files.put(path, stamp(p));
            }
        }
        return files;
    }

    private Map<String, long[]> knownFiles() throws SQLException {
        Map<String, long[]> files = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT path, size, modified FROM meta_file")) {
            while (rs.next()) files.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)});
        }
        return files;
    }

    private static long[] stamp(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        return new long[]{attrs.size(), attrs.lastModifiedTime().toMillis()};
    }

    private void recordFile(String path, long[] stamp) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO meta_file(path, size, modified) VALUES (?, ?, ?) "
                        + "ON CONFLICT(path) DO UPDATE SET size = excluded.size, modified = excluded.modified")) {
            ps.setString(1, path);
            ps.setLong(2, stamp[0]);
            ps.setLong(3, stamp[1]);
            ps.executeUpdate();
        }
    }

    private void forgetFile(String path) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM meta_file WHERE path = ?")) {
            ps.setString(1, path);
            ps.executeUpdate();
        }
    }

    // ---------- Helpers ----------

    private interface SqlWork {
        void run() throws SQLException;
    }

    private void inTransaction(SqlWork work) {
        try {
            connection.setAutoCommit(false);
            try {
                work.run();
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String setting(String key) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM setting WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void setSetting(String key, String value) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO setting(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
