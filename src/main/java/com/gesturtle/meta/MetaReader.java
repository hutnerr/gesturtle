package com.gesturtle.meta;

import com.gesturtle.filter.Filter;
import com.gesturtle.filter.FilterCodec;
import com.gesturtle.library.ImportService;
import com.gesturtle.model.OrderMode;
import com.gesturtle.model.SourceKind;
import com.gesturtle.model.TickBehavior;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies meta/ files to the database: changed files are written over the
 * matching rows (found by hash or uid), and things whose file is gone are
 * deleted. Runs inside MetaStore's transaction.
 *
 * Two machines can each make a tag with the same name and parent. Rather
 * than keep two "hands" tags, they're merged into the one with the smaller
 * uid, which every machine picks the same way, so they all end up with one
 * tag. Views and playlists, whose names must be unique too, get " (2)"
 * instead, since their contents can't be merged sensibly. Anything the
 * database ends up holding differently from the files is reported in
 * {@link #rewrite()}, for MetaStore to write back out.
 */
final class MetaReader {
    private final Connection connection;
    /** A tag's uid → the uid it was merged into. */
    private final Map<String, String> mergedTags = new HashMap<>();
    private final Set<MetaFiles.Entry> rewrite = new HashSet<>();
    private final Set<MetaFiles.Entry> failed = new HashSet<>();

    MetaReader(Connection connection) {
        this.connection = connection;
    }

    /** Files that parsed as JSON but couldn't be applied (a hand edit gone wrong); their things are left as they were. */
    Set<MetaFiles.Entry> failed() {
        return failed;
    }

    /** Things whose file no longer matches the database: merged-away tags, renamed views, and what pointed at them. */
    Set<MetaFiles.Entry> rewrite() {
        return rewrite;
    }

    /**
     * changed holds parsed files by kind, then key. Order matters: tags before
     * the images that carry them, images before playlists, views and
     * playlists before the presets that use them; removals go the other way.
     */
    void apply(Map<String, Map<String, JsonObject>> changed, Map<String, Set<String>> removed) throws SQLException {
        applyTags(changed.getOrDefault(MetaFiles.TAG, Map.of()));
        for (var e : changed.getOrDefault(MetaFiles.IMAGE, Map.of()).entrySet()) {
            isolated(MetaFiles.IMAGE, e.getKey(), () -> applyImage(e.getKey(), e.getValue()));
        }
        for (var e : changed.getOrDefault(MetaFiles.VIEW, Map.of()).entrySet()) {
            isolated(MetaFiles.VIEW, e.getKey(), () -> applyView(e.getKey(), e.getValue()));
        }
        for (var e : changed.getOrDefault(MetaFiles.PLAYLIST, Map.of()).entrySet()) {
            isolated(MetaFiles.PLAYLIST, e.getKey(), () -> applyPlaylist(e.getKey(), e.getValue()));
        }
        for (var e : changed.getOrDefault(MetaFiles.PRESET, Map.of()).entrySet()) {
            isolated(MetaFiles.PRESET, e.getKey(), () -> applyPreset(e.getKey(), e.getValue()));
        }
        JsonObject settings = changed.getOrDefault(MetaFiles.SETTINGS, Map.of()).get("");
        if (settings != null) applySettings(settings);

        for (String uid : removed.getOrDefault(MetaFiles.PRESET, Set.of())) update("DELETE FROM preset WHERE uid = ?", uid);
        for (String uid : removed.getOrDefault(MetaFiles.PLAYLIST, Set.of())) {
            Integer id = idOf("playlist", uid);
            if (id == null) continue;
            update("UPDATE segment SET playlist_id = NULL WHERE playlist_id = ?", id);
            update("DELETE FROM playlist WHERE id = ?", id);
        }
        for (String uid : removed.getOrDefault(MetaFiles.VIEW, Set.of())) update("DELETE FROM saved_view WHERE uid = ?", uid);
        for (String hash : removed.getOrDefault(MetaFiles.IMAGE, Set.of())) update("DELETE FROM image WHERE hash = ?", hash);
        for (String uid : removed.getOrDefault(MetaFiles.TAG, Set.of())) removeTag(uid);
    }

    private interface FileWork {
        void run() throws SQLException;
    }

    /**
     * Applies one file inside a savepoint. Valid JSON with the wrong shape
     * (a filter missing a field, say) undoes just that file's changes, so one
     * bad file can't stop the library opening.
     */
    private void isolated(String kind, String key, FileWork work) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("SAVEPOINT meta_file");
        }
        try {
            work.run();
        } catch (RuntimeException e) {
            try (Statement st = connection.createStatement()) {
                st.execute("ROLLBACK TO meta_file");
            }
            MetaFiles.Entry entry = new MetaFiles.Entry(kind, key);
            failed.add(entry);
            // Not written back either: the database's older copy would overwrite the file.
            rewrite.remove(entry);
        } finally {
            try (Statement st = connection.createStatement()) {
                st.execute("RELEASE meta_file");
            }
        }
    }

    // ---------- Tags ----------

    private record TagFile(String uid, JsonObject json) {
        String name() {
            return str(json, "name");
        }

        String parent() {
            return str(json, "parent");
        }
    }

    private void applyTags(Map<String, JsonObject> files) throws SQLException {
        if (files.isEmpty()) return;
        // Changed tags step aside under their uid first, so names can swap or move without
        // tripping over each other on the way.
        for (String uid : files.keySet()) {
            update("UPDATE tag SET name = uid, parent_id = NULL WHERE uid = ?", uid);
        }
        // Parents before children, so each child's parent exists when it's placed.
        List<TagFile> ordered = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (var e : files.entrySet()) order(new TagFile(e.getKey(), e.getValue()), files, placed, new HashSet<>(), ordered);
        for (TagFile tag : ordered) placeTag(tag);
    }

    private void order(TagFile tag, Map<String, JsonObject> files, Set<String> placed, Set<String> visiting,
                       List<TagFile> out) {
        if (placed.contains(tag.uid()) || !visiting.add(tag.uid())) return;
        String parent = tag.parent();
        if (parent != null && files.containsKey(parent)) order(new TagFile(parent, files.get(parent)), files, placed, visiting, out);
        placed.add(tag.uid());
        out.add(tag);
    }

    private void placeTag(TagFile tag) throws SQLException {
        String name = tag.name() == null || tag.name().isBlank() ? tag.uid() : tag.name();
        Integer parentId = tag.parent() == null ? null : idOf("tag", resolveTag(tag.parent()));
        Integer id = idOf("tag", tag.uid());
        if (parentId != null && id != null && isDescendant(id, parentId)) parentId = null;

        Integer twin = sibling(name, parentId, id);
        if (twin != null) {
            String twinUid = uidOf("tag", twin);
            if (twinUid.compareTo(tag.uid()) < 0) {
                // The one already here wins. This tag's things move onto it and its file goes.
                if (id != null) mergeTag(id, twin);
                mergedTags.put(tag.uid(), twinUid);
                rewrite.add(new MetaFiles.Entry(MetaFiles.TAG, tag.uid()));
                rewrite.add(new MetaFiles.Entry(MetaFiles.TAG, twinUid));
                return;
            }
            // This tag wins: the one already here merges into it.
            if (id == null) id = insertTag(tag.uid(), name);
            mergeTag(twin, id);
            mergedTags.put(twinUid, tag.uid());
            rewrite.add(new MetaFiles.Entry(MetaFiles.TAG, twinUid));
        }
        if (id == null) id = insertTag(tag.uid(), name);
        JsonObject j = tag.json();
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE tag SET name = ?, parent_id = ?, created_at = ?, group_only = ?, exclusive_children = ?,
                               color = ?, description = ? WHERE id = ?""")) {
            ps.setString(1, name);
            setInt(ps, 2, parentId);
            ps.setLong(3, lng(j, "created", 0));
            ps.setInt(4, bool(j, "groupOnly") ? 1 : 0);
            ps.setInt(5, bool(j, "exclusiveChildren") ? 1 : 0);
            ps.setString(6, str(j, "color"));
            ps.setString(7, str(j, "description"));
            ps.setInt(8, id);
            ps.executeUpdate();
        }
    }

    private int insertTag(String uid, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO tag(uid, name, created_at) VALUES (?, ?, 0)")) {
            ps.setString(1, uid);
            // Placed at the top level under its uid until placeTag moves and names it.
            ps.setString(2, uid);
            ps.executeUpdate();
        }
        return idOf("tag", uid);
    }

    /** A tag with this name and parent other than self, or null. */
    private Integer sibling(String name, Integer parentId, Integer self) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM tag WHERE name = ? AND parent_id IS ? AND id IS NOT ?")) {
            ps.setString(1, name);
            setInt(ps, 2, parentId);
            setInt(ps, 3, self);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    /**
     * Moves everything from one tag onto another and deletes it: its images,
     * its children (merging any that clash by name, all the way down), and
     * the views and presets that filter on it.
     */
    private void mergeTag(int from, int into) throws SQLException {
        if (from == into) return;
        markImagesWithTag(from);
        update("INSERT OR IGNORE INTO image_tag(image_id, tag_id) SELECT image_id, ? FROM image_tag WHERE tag_id = ?", into, from);
        update("DELETE FROM image_tag WHERE tag_id = ?", from);
        for (int child : ints("SELECT id FROM tag WHERE parent_id = ?", from)) {
            Integer clash = sibling(nameOf(child), into, child);
            if (clash != null) {
                mergeTag(child, clash);
            } else {
                update("UPDATE tag SET parent_id = ? WHERE id = ?", into, child);
            }
        }
        retargetFilters(from, into);
        update("DELETE FROM tag WHERE id = ?", from);
    }

    /** A tag file was removed: its children move up to its parent (merging on a clash), then it goes. */
    private void removeTag(String uid) throws SQLException {
        Integer id = idOf("tag", uid);
        if (id == null) return;
        Integer parent = nullableInt("SELECT parent_id FROM tag WHERE id = ?", id);
        for (int child : ints("SELECT id FROM tag WHERE parent_id = ?", id)) {
            Integer clash = sibling(nameOf(child), parent, child);
            if (clash != null) {
                mergeTag(child, clash);
            } else {
                update("UPDATE tag SET parent_id = ? WHERE id = ?", parent, child);
            }
        }
        update("DELETE FROM tag WHERE id = ?", id);
    }

    private void markImagesWithTag(int tagId) throws SQLException {
        for (String hash : strings("SELECT i.hash FROM image_tag it JOIN image i ON i.id = it.image_id WHERE it.tag_id = ?", tagId)) {
            rewrite.add(new MetaFiles.Entry(MetaFiles.IMAGE, hash));
        }
    }

    /** Points saved views' and presets' tag conditions at the merged tag. */
    private void retargetFilters(int from, int into) throws SQLException {
        for (var row : rows("SELECT id, uid, filter_json FROM saved_view")) {
            String json = retarget((String) row[2], from, into);
            if (json != null) {
                update("UPDATE saved_view SET filter_json = ? WHERE id = ?", json, row[0]);
                rewrite.add(new MetaFiles.Entry(MetaFiles.VIEW, (String) row[1]));
            }
        }
        for (var row : rows("""
                SELECT s.id, p.uid, s.filter_json FROM segment s JOIN preset p ON p.id = s.preset_id
                WHERE s.filter_json IS NOT NULL""")) {
            String json = retarget((String) row[2], from, into);
            if (json != null) {
                update("UPDATE segment SET filter_json = ? WHERE id = ?", json, row[0]);
                rewrite.add(new MetaFiles.Entry(MetaFiles.PRESET, (String) row[1]));
            }
        }
    }

    /** The filter with tag from swapped for into, or null if it doesn't use from. */
    private static String retarget(String filterJson, int from, int into) {
        boolean[] used = {false};
        JsonObject o = FilterCodec.toJson(FilterCodec.decode(filterJson), (p, out) -> {
            if (p.tagId() == from) used[0] = true;
            out.addProperty("tagId", p.tagId() == from ? into : p.tagId());
        });
        if (!used[0]) return null;
        return FilterCodec.encode(FilterCodec.fromJson(o, t -> t.get("tagId").getAsInt()));
    }

    /** A tag uid as it stands after any merge. */
    private String resolveTag(String uid) {
        String seen = uid;
        for (int i = 0; i < 64 && mergedTags.containsKey(seen); i++) seen = mergedTags.get(seen);
        return seen;
    }

    private Integer tagId(String uid) throws SQLException {
        return uid == null ? null : idOf("tag", resolveTag(uid));
    }

    // ---------- Images ----------

    private void applyImage(String hash, JsonObject j) throws SQLException {
        Integer id = idByHash(hash);
        if (id == null) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO image(hash, ext, imported_at, favorite, seen_count) VALUES (?, ?, 0, 0, 0)")) {
                ps.setString(1, hash);
                ps.setString(2, ext(j));
                ps.executeUpdate();
            }
            id = idByHash(hash);
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE image SET ext = ?, original_name = ?, width = ?, height = ?, byte_size = ?, imported_at = ?,
                                 source_path = ?, favorite = ?, credit = ?, notes = ?, deleted_at = ? WHERE id = ?""")) {
            ps.setString(1, ext(j));
            ps.setString(2, str(j, "name"));
            ps.setInt(3, (int) lng(j, "width", 0));
            ps.setInt(4, (int) lng(j, "height", 0));
            ps.setLong(5, lng(j, "bytes", 0));
            ps.setLong(6, lng(j, "imported", 0));
            ps.setString(7, str(j, "source"));
            ps.setInt(8, bool(j, "favorite") ? 1 : 0);
            ps.setString(9, str(j, "credit"));
            ps.setString(10, str(j, "notes"));
            long deleted = lng(j, "deleted", 0);
            if (deleted != 0) ps.setLong(11, deleted);
            else ps.setNull(11, Types.INTEGER);
            ps.setInt(12, id);
            ps.executeUpdate();
        }
        update("DELETE FROM image_tag WHERE image_id = ?", id);
        for (String uid : stringList(j, "tags")) {
            if (mergedTags.containsKey(uid)) rewrite.add(new MetaFiles.Entry(MetaFiles.IMAGE, hash));
            Integer tagId = tagId(uid);
            // A tag that isn't here (a file that didn't come across, or can't be read) is skipped, not invented.
            if (tagId != null) update("INSERT OR IGNORE INTO image_tag(image_id, tag_id) VALUES (?, ?)", id, tagId);
        }
    }

    /** ext becomes part of a file path, so one from a hand-edited or crafted file that isn't a known format is ignored. */
    private static String ext(JsonObject j) {
        String ext = str(j, "ext");
        return ImportService.isSupportedExtension(ext) ? ext : "jpg";
    }

    // ---------- Views ----------

    private void applyView(String uid, JsonObject j) throws SQLException {
        Integer id = idOf("saved_view", uid);
        String name = uniqueName("saved_view", str(j, "name") == null ? "View" : str(j, "name"), id, MetaFiles.VIEW, uid);
        String filter = filterJson(j.get("filter"));
        if (id == null) {
            update("INSERT INTO saved_view(uid, name, filter_json, created_at) VALUES (?, ?, ?, ?)",
                    uid, name, filter, lng(j, "created", 0));
        } else {
            update("UPDATE saved_view SET name = ?, filter_json = ?, created_at = ? WHERE id = ?",
                    name, filter, lng(j, "created", 0), id);
        }
    }

    // ---------- Playlists ----------

    private void applyPlaylist(String uid, JsonObject j) throws SQLException {
        Integer id = idOf("playlist", uid);
        String name = uniqueName("playlist", str(j, "name") == null ? "Playlist" : str(j, "name"), id, MetaFiles.PLAYLIST, uid);
        if (id == null) {
            update("INSERT INTO playlist(uid, name, created_at) VALUES (?, ?, ?)", uid, name, lng(j, "created", 0));
            id = idOf("playlist", uid);
        } else {
            update("UPDATE playlist SET name = ?, created_at = ? WHERE id = ?", name, lng(j, "created", 0), id);
        }
        update("DELETE FROM playlist_item WHERE playlist_id = ?", id);
        int position = 0;
        for (String hash : stringList(j, "images")) {
            Integer imageId = idByHash(hash);
            if (imageId != null) {
                update("INSERT OR IGNORE INTO playlist_item(playlist_id, image_id, position) VALUES (?, ?, ?)", id, imageId, position++);
            }
        }
        update("UPDATE playlist SET cover_image_id = ? WHERE id = ?", str(j, "cover") == null ? null : idByHash(str(j, "cover")), id);
    }

    // ---------- Presets ----------

    private void applyPreset(String uid, JsonObject j) throws SQLException {
        Integer id = idOf("preset", uid);
        String name = str(j, "name") == null ? "Preset" : str(j, "name");
        if (id == null) {
            update("INSERT INTO preset(uid, name, created_at, use_count, pinned) VALUES (?, ?, ?, 0, ?)",
                    uid, name, lng(j, "created", 0), bool(j, "pinned") ? 1 : 0);
            id = idOf("preset", uid);
        } else {
            update("UPDATE preset SET name = ?, created_at = ?, pinned = ? WHERE id = ?",
                    name, lng(j, "created", 0), bool(j, "pinned") ? 1 : 0, id);
        }
        update("DELETE FROM segment WHERE preset_id = ?", id);
        JsonArray segments = j.has("segments") && j.get("segments").isJsonArray() ? j.getAsJsonArray("segments") : new JsonArray();
        int position = 0;
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO segment(preset_id, position, source_kind, filter_json, playlist_id, view_id, item_count,
                                    order_mode, seconds_per_image, ramp_to_seconds, tick_behavior, break_seconds,
                                    memory_show_seconds, memory_hide_seconds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            for (JsonElement el : segments) {
                if (!el.isJsonObject()) continue;
                JsonObject s = el.getAsJsonObject();
                ps.setInt(1, id);
                ps.setInt(2, position++);
                // Read back with valueOf, so a name this version doesn't know would break the whole preset list.
                ps.setString(3, enumName(SourceKind.class, str(s, "source"), SourceKind.FILTER));
                ps.setString(4, s.has("filter") && s.get("filter").isJsonObject() ? filterJson(s.get("filter")) : null);
                setInt(ps, 5, str(s, "playlist") == null ? null : idOf("playlist", str(s, "playlist")));
                setInt(ps, 6, str(s, "view") == null ? null : idOf("saved_view", str(s, "view")));
                setInt(ps, 7, nullableInt(s, "count"));
                ps.setString(8, enumName(OrderMode.class, str(s, "order"), OrderMode.RANDOM));
                ps.setInt(9, (int) lng(s, "seconds", 0));
                setInt(ps, 10, nullableInt(s, "rampTo"));
                ps.setString(11, enumName(TickBehavior.class, str(s, "tick"), TickBehavior.ADVANCE_IMMEDIATELY));
                ps.setInt(12, (int) lng(s, "break", 0));
                setInt(ps, 13, nullableInt(s, "memoryShow"));
                setInt(ps, 14, nullableInt(s, "memoryHide"));
                ps.executeUpdate();
            }
        }
    }

    // ---------- Settings ----------

    private void applySettings(JsonObject j) throws SQLException {
        String builtIns = str(j, "builtInViews");
        if (builtIns == null) update("DELETE FROM setting WHERE key = ?", MetaStore.BUILT_INS_KEY);
        else setSetting(MetaStore.BUILT_INS_KEY, builtIns);
        String view = str(j, "defaultView");
        String id = null;
        if (view != null) {
            if (view.matches("-\\d{1,9}")) {
                id = view;
            } else {
                Integer local = idOf("saved_view", view);
                if (local != null) id = String.valueOf(local);
            }
        }
        if (id == null) update("DELETE FROM setting WHERE key = ?", MetaStore.DEFAULT_VIEW_KEY);
        else setSetting(MetaStore.DEFAULT_VIEW_KEY, id);
    }

    private void setSetting(String key, String value) throws SQLException {
        update("INSERT INTO setting(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value);
    }

    // ---------- Helpers ----------

    /** A filter as stored in the database, with each tag uid swapped for this machine's id (-1, matching nothing, if missing). */
    private String filterJson(JsonElement element) throws SQLException {
        if (element == null || !element.isJsonObject()) return FilterCodec.encode(new Filter.All());
        Map<String, Integer> ids = new LinkedHashMap<>();
        collectTagUids(element.getAsJsonObject(), ids);
        for (var e : ids.entrySet()) {
            Integer id = tagId(e.getKey());
            e.setValue(id == null ? -1 : id);
        }
        Filter filter = FilterCodec.fromJson(element.getAsJsonObject(),
                o -> o.has("tag") && !o.get("tag").isJsonNull() ? ids.getOrDefault(o.get("tag").getAsString(), -1) : -1);
        return FilterCodec.encode(filter);
    }

    private static void collectTagUids(JsonObject o, Map<String, Integer> out) {
        if ("tag".equals(str(o, "type")) && str(o, "tag") != null) out.put(str(o, "tag"), -1);
        if (o.has("child") && o.get("child").isJsonObject()) collectTagUids(o.getAsJsonObject("child"), out);
        if (o.has("children") && o.get("children").isJsonArray()) {
            for (JsonElement c : o.getAsJsonArray("children")) {
                if (c.isJsonObject()) collectTagUids(c.getAsJsonObject(), out);
            }
        }
    }

    /** name, or "name (2)", "name (3)"… if another row (not self) already has it; a renamed one is written back. */
    private String uniqueName(String table, String name, Integer self, String kind, String uid) throws SQLException {
        String candidate = name;
        for (int n = 2; taken(table, candidate, self); n++) candidate = name + " (" + n + ")";
        if (!candidate.equals(name)) rewrite.add(new MetaFiles.Entry(kind, uid));
        return candidate;
    }

    private boolean taken(String table, String name, Integer self) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE name = ? AND id IS NOT ?")) {
            ps.setString(1, name);
            setInt(ps, 2, self);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean isDescendant(int ancestor, int candidate) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                WITH RECURSIVE sub(id) AS (SELECT ? UNION ALL SELECT t.id FROM tag t JOIN sub ON t.parent_id = sub.id)
                SELECT 1 FROM sub WHERE id = ?""")) {
            ps.setInt(1, ancestor);
            ps.setInt(2, candidate);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Integer idOf(String table, String uid) throws SQLException {
        return nullableInt("SELECT id FROM " + table + " WHERE uid = ?", uid);
    }

    private String uidOf(String table, int id) throws SQLException {
        List<String> found = strings("SELECT uid FROM " + table + " WHERE id = ?", id);
        return found.isEmpty() ? null : found.get(0);
    }

    private Integer idByHash(String hash) throws SQLException {
        return nullableInt("SELECT id FROM image WHERE hash = ?", hash);
    }

    private String nameOf(int tagId) throws SQLException {
        return strings("SELECT name FROM tag WHERE id = ?", tagId).get(0);
    }

    private void update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        }
    }

    private Integer nullableInt(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getObject(1) != null ? rs.getInt(1) : null;
            }
        }
    }

    private List<Integer> ints(String sql, Object... params) throws SQLException {
        List<Integer> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        }
        return out;
    }

    private List<String> strings(String sql, Object... params) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    private List<Object[]> rows(String sql) throws SQLException {
        List<Object[]> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Object[] row = new Object[columns];
                for (int i = 0; i < columns; i++) row[i] = rs.getObject(i + 1);
                out.add(row);
            }
        }
        return out;
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] == null) ps.setNull(i + 1, Types.NULL);
            else ps.setObject(i + 1, params[i]);
        }
    }

    private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value != null) ps.setInt(index, value); else ps.setNull(index, Types.INTEGER);
    }

    static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static long lng(JsonObject o, String key, long fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Integer nullableInt(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> String enumName(Class<E> type, String name, E fallback) {
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(name)) return name;
        }
        return fallback.name();
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    private static List<String> stringList(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(key)) {
                if (e.isJsonPrimitive()) out.add(e.getAsString());
            }
        }
        return out;
    }
}
