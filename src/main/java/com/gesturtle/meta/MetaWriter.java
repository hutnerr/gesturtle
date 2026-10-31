package com.gesturtle.meta;

import com.gesturtle.filter.FilterCodec;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Turns one image, tag, playlist, view, or preset into the JSON its meta/
 * file holds, read fresh from the database. Other things are named by hash
 * or uid, never by the database's own ids, which differ between machines.
 * Keys are always in the same order and lists are sorted (except where the
 * order means something, like a playlist's), so an unchanged thing writes
 * the same bytes and a change shows up as a small diff. False flags and
 * empty fields are left out.
 *
 * Practice stats (seen counts, last seen, preset use) are deliberately not
 * written: they stay in the local database and never reach git.
 */
final class MetaWriter {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /**
     * Written as the first key of every file. Bump it when a change to the
     * format would be misread by an older Gesturtle: an older copy (on another
     * machine sharing the library through git) then leaves those files alone
     * rather than guessing. A file without one is version 1.
     */
    static final int FORMAT_VERSION = 1;
    static final String VERSION_KEY = "schemaVersion";

    private final Connection connection;

    MetaWriter(Connection connection) {
        this.connection = connection;
    }

    /** The file's text, or null when the thing no longer exists (its file should go). */
    String write(String kind, String key) throws SQLException {
        JsonObject json = switch (kind) {
            case MetaFiles.IMAGE -> image(key);
            case MetaFiles.TAG -> tag(key);
            case MetaFiles.PLAYLIST -> playlist(key);
            case MetaFiles.VIEW -> view(key);
            case MetaFiles.PRESET -> preset(key);
            case MetaFiles.SETTINGS -> settings();
            default -> null;
        };
        if (json == null) return null;
        JsonObject versioned = new JsonObject();
        versioned.addProperty(VERSION_KEY, FORMAT_VERSION);
        json.entrySet().forEach(e -> versioned.add(e.getKey(), e.getValue()));
        return GSON.toJson(versioned) + "\n";
    }

    private JsonObject image(String hash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM image WHERE hash = ?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                JsonObject o = new JsonObject();
                o.addProperty("ext", rs.getString("ext"));
                o.addProperty("name", rs.getString("original_name"));
                o.addProperty("width", rs.getInt("width"));
                o.addProperty("height", rs.getInt("height"));
                o.addProperty("bytes", rs.getLong("byte_size"));
                o.addProperty("imported", rs.getLong("imported_at"));
                o.addProperty("source", rs.getString("source_path"));
                if (rs.getInt("favorite") == 1) o.addProperty("favorite", true);
                o.addProperty("credit", rs.getString("credit"));
                o.addProperty("notes", rs.getString("notes"));
                if (rs.getObject("deleted_at") != null) o.addProperty("deleted", rs.getLong("deleted_at"));
                o.add("tags", strings("""
                        SELECT t.uid FROM image_tag it JOIN tag t ON t.id = it.tag_id
                        WHERE it.image_id = ? ORDER BY t.uid""", rs.getInt("id")));
                return o;
            }
        }
    }

    private JsonObject tag(String uid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT t.*, p.uid AS parent_uid FROM tag t LEFT JOIN tag p ON p.id = t.parent_id WHERE t.uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                JsonObject o = new JsonObject();
                o.addProperty("name", rs.getString("name"));
                o.addProperty("parent", rs.getString("parent_uid"));
                o.addProperty("created", rs.getLong("created_at"));
                if (rs.getInt("group_only") != 0) o.addProperty("groupOnly", true);
                if (rs.getInt("exclusive_children") != 0) o.addProperty("exclusiveChildren", true);
                o.addProperty("color", rs.getString("color"));
                o.addProperty("description", rs.getString("description"));
                return o;
            }
        }
    }

    private JsonObject playlist(String uid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT p.*, c.hash AS cover_hash FROM playlist p LEFT JOIN image c ON c.id = p.cover_image_id WHERE p.uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                JsonObject o = new JsonObject();
                o.addProperty("name", rs.getString("name"));
                o.addProperty("created", rs.getLong("created_at"));
                o.addProperty("cover", rs.getString("cover_hash"));
                o.add("images", strings("""
                        SELECT i.hash FROM playlist_item pi JOIN image i ON i.id = pi.image_id
                        WHERE pi.playlist_id = ? ORDER BY pi.position, i.hash""", rs.getInt("id")));
                return o;
            }
        }
    }

    private JsonObject view(String uid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM saved_view WHERE uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                JsonObject o = new JsonObject();
                o.addProperty("name", rs.getString("name"));
                o.addProperty("created", rs.getLong("created_at"));
                o.add("filter", filter(rs.getString("filter_json")));
                return o;
            }
        }
    }

    private JsonObject preset(String uid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM preset WHERE uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                JsonObject o = new JsonObject();
                o.addProperty("name", rs.getString("name"));
                o.addProperty("created", rs.getLong("created_at"));
                if (rs.getInt("pinned") == 1) o.addProperty("pinned", true);
                o.add("segments", segments(rs.getInt("id")));
                return o;
            }
        }
    }

    private JsonArray segments(int presetId) throws SQLException {
        JsonArray array = new JsonArray();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT s.*, p.uid AS playlist_uid, v.uid AS view_uid FROM segment s
                LEFT JOIN playlist p ON p.id = s.playlist_id
                LEFT JOIN saved_view v ON v.id = s.view_id
                WHERE s.preset_id = ? ORDER BY s.position""")) {
            ps.setInt(1, presetId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject s = new JsonObject();
                    s.addProperty("source", rs.getString("source_kind"));
                    if (rs.getString("filter_json") != null) s.add("filter", filter(rs.getString("filter_json")));
                    s.addProperty("playlist", rs.getString("playlist_uid"));
                    s.addProperty("view", rs.getString("view_uid"));
                    s.addProperty("count", (Number) rs.getObject("item_count"));
                    s.addProperty("order", rs.getString("order_mode"));
                    s.addProperty("seconds", rs.getInt("seconds_per_image"));
                    s.addProperty("rampTo", (Number) rs.getObject("ramp_to_seconds"));
                    s.addProperty("tick", rs.getString("tick_behavior"));
                    s.addProperty("break", rs.getInt("break_seconds"));
                    s.addProperty("memoryShow", (Number) rs.getObject("memory_show_seconds"));
                    s.addProperty("memoryHide", (Number) rs.getObject("memory_hide_seconds"));
                    array.add(s);
                }
            }
        }
        return array;
    }

    private JsonObject settings() throws SQLException {
        JsonObject o = new JsonObject();
        String builtIns = setting(MetaStore.BUILT_INS_KEY);
        if (builtIns != null) o.addProperty("builtInViews", builtIns);
        String defaultView = setting(MetaStore.DEFAULT_VIEW_KEY);
        if (defaultView != null && !defaultView.isBlank()) {
            int id = Integer.parseInt(defaultView);
            // Built-in views have fixed negative ids, the same everywhere.
            o.addProperty("defaultView", id < 0 ? String.valueOf(id) : uidOf("saved_view", id));
        }
        return o;
    }

    /** A stored filter with each tag named by uid, so it means the same tags on every machine. */
    private JsonObject filter(String filterJson) {
        // A tag deleted since leaves the predicate without one; read back, it matches nothing, as it does now.
        return FilterCodec.toJson(FilterCodec.decode(filterJson), (p, o) -> {
            try {
                o.addProperty("tag", uidOf("tag", p.tagId()));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private String uidOf(String table, int id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT uid FROM " + table + " WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String setting(String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM setting WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private JsonArray strings(String sql, int id) throws SQLException {
        JsonArray array = new JsonArray();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getString(1) != null) array.add(rs.getString(1));
                }
            }
        }
        return array;
    }
}
