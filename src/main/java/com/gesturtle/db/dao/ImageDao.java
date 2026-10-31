package com.gesturtle.db.dao;

import com.gesturtle.filter.Filter;
import com.gesturtle.filter.FilterCompiler;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.OrderMode;
import com.gesturtle.model.SortOrder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ImageDao {
    private final Connection connection;

    public ImageDao(Connection connection) {
        this.connection = connection;
    }

    public int insert(String hash, String ext, String originalName, int width, int height,
                       long byteSize, long importedAt, String sourcePath) {
        String sql = """
                INSERT INTO image(hash, ext, original_name, width, height, byte_size, imported_at, source_path, favorite, seen_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0)""";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, hash);
            ps.setString(2, ext);
            ps.setString(3, originalName);
            ps.setInt(4, width);
            ps.setInt(5, height);
            ps.setLong(6, byteSize);
            ps.setLong(7, importedAt);
            ps.setString(8, sourcePath);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert image " + hash, e);
        }
    }

    /**
     * True if any image row (including a soft-deleted one) has this hash.
     * Deliberately not filtered by deleted_at: a hash still occupies the
     * UNIQUE(hash) slot while deleted, so re-importing the same
     * file must be classified as ALREADY_IN_LIBRARY (and restored), never NEW.
     */
    public boolean existsByHash(String hash) {
        String sql = "SELECT 1 FROM image WHERE hash = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<ImageRecord> findByHash(String hash) {
        String sql = "SELECT * FROM image WHERE hash = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRowWithTags(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<ImageRecord> findById(int id) {
        String sql = "SELECT * FROM image WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRowWithTags(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Adds tags in iteration order. A tag under an exclusive-children parent
     * first takes the place of any sibling the image already has, so when two
     * siblings are passed, the later one wins.
     */
    public void addTags(int imageId, Collection<Integer> tagIds) {
        try (PreparedStatement dropSiblings = connection.prepareStatement("""
                DELETE FROM image_tag WHERE image_id = ? AND tag_id IN (
                    SELECT s.id FROM tag t
                    JOIN tag p ON p.id = t.parent_id AND p.exclusive_children = 1
                    JOIN tag s ON s.parent_id = p.id AND s.id <> t.id
                    WHERE t.id = ?)""");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT OR IGNORE INTO image_tag(image_id, tag_id) VALUES (?, ?)")) {
            for (int tagId : tagIds) {
                dropSiblings.setInt(1, imageId);
                dropSiblings.setInt(2, tagId);
                dropSiblings.executeUpdate();
                insert.setInt(1, imageId);
                insert.setInt(2, tagId);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Makes tagIds this image's exact tag set. Runs inside the caller's transaction, if any. */
    public void replaceTags(int imageId, Collection<Integer> tagIds) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM image_tag WHERE image_id = ?")) {
            ps.setInt(1, imageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        addTags(imageId, tagIds);
    }

    /** Points an existing image at a newly imported copy of the same bytes (duplicate "Overwrite"). */
    public void updateSource(int imageId, String originalName, String sourcePath) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE image SET original_name = ?, source_path = ? WHERE id = ?")) {
            ps.setString(1, originalName);
            ps.setString(2, sourcePath);
            ps.setInt(3, imageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void setDimensions(int imageId, int width, int height) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE image SET width = ?, height = ? WHERE id = ?")) {
            ps.setInt(1, width);
            ps.setInt(2, height);
            ps.setInt(3, imageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Saves the user-entered credit and notes; blank text is stored as NULL so "is empty" filters stay simple. */
    public void setDetails(int imageId, String credit, String notes) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE image SET credit = ?, notes = ? WHERE id = ?")) {
            ps.setString(1, blankToNull(credit));
            ps.setString(2, blankToNull(notes));
            ps.setInt(3, imageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    public void removeTag(int imageId, int tagId) {
        String sql = "DELETE FROM image_tag WHERE image_id = ? AND tag_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, imageId);
            ps.setInt(2, tagId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void setFavorite(int imageId, boolean favorite) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE image SET favorite = ? WHERE id = ?")) {
            ps.setInt(1, favorite ? 1 : 0);
            ps.setInt(2, imageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<ImageRecord> query(Filter filter, OrderMode orderMode, Integer limit) {
        return query(filter, orderMode == OrderMode.RANDOM ? "RANDOM()" : "i.imported_at ASC", limit);
    }

    /** The Gallery's query: same filter, in the user's chosen order. */
    public List<ImageRecord> query(Filter filter, SortOrder sort, Integer limit) {
        return query(filter, sort.sql, limit);
    }

    private List<ImageRecord> query(Filter filter, String orderBy, Integer limit) {
        FilterCompiler.Compiled compiled = FilterCompiler.compile(filter);
        StringBuilder sql = new StringBuilder("SELECT * FROM image i WHERE ").append(compiled.whereSql());
        sql.append(" ORDER BY ").append(orderBy);
        if (limit != null) {
            sql.append(" LIMIT ").append(limit);
        }
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            bindParams(ps, compiled.params());
            try (ResultSet rs = ps.executeQuery()) {
                return mapRowsWithTags(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Filter query failed: " + sql, e);
        }
    }

    public int count(Filter filter) {
        FilterCompiler.Compiled compiled = FilterCompiler.compile(filter);
        String sql = "SELECT COUNT(*) FROM image i WHERE " + compiled.whereSql();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindParams(ps, compiled.params());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Distinct images with last_seen_at at or after the cutoff; powers the home screen's weekly stat. */
    public int countSeenSince(long cutoffMillis) {
        String sql = "SELECT COUNT(*) FROM image WHERE last_seen_at >= ? AND deleted_at IS NULL";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, cutoffMillis);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void softDelete(int imageId, long deletedAt) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE image SET deleted_at = ? WHERE id = ?")) {
            ps.setLong(1, deletedAt);
            ps.setInt(2, imageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void restore(int imageId) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE image SET deleted_at = NULL WHERE id = ?")) {
            ps.setInt(1, imageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<ImageRecord> listDeleted() {
        String sql = "SELECT * FROM image WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return mapRowsWithTags(rs);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Carries over the denormalized last-seen/seen-count scalars without a full view_event replay. */
    public void setSeenStats(int imageId, Long lastSeenAt, int seenCount) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE image SET last_seen_at = ?, seen_count = ? WHERE id = ?")) {
            if (lastSeenAt != null) ps.setLong(1, lastSeenAt); else ps.setNull(1, java.sql.Types.INTEGER);
            ps.setInt(2, seenCount);
            ps.setInt(3, imageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** The raw per-viewing log for one image, oldest first (archive export). */
    public List<com.gesturtle.model.ViewEvent> listViewEvents(int imageId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT seen_at, shown_ms FROM view_event WHERE image_id = ? ORDER BY seen_at ASC")) {
            ps.setInt(1, imageId);
            try (ResultSet rs = ps.executeQuery()) {
                List<com.gesturtle.model.ViewEvent> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(new com.gesturtle.model.ViewEvent(rs.getLong("seen_at"), rs.getLong("shown_ms")));
                }
                return events;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Inserts a single view_event row without touching image.last_seen_at/seen_count (archive import replay). */
    public void insertRawViewEvent(int imageId, long seenAt, long shownMs) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO view_event(image_id, session_id, seen_at, shown_ms) VALUES (?, NULL, ?, ?)")) {
            ps.setInt(1, imageId);
            ps.setLong(2, seenAt);
            ps.setLong(3, shownMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void recordView(int imageId, Integer sessionId, long seenAt, long shownMs) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO view_event(image_id, session_id, seen_at, shown_ms) VALUES (?, ?, ?, ?)")) {
            ps.setInt(1, imageId);
            if (sessionId != null) ps.setInt(2, sessionId); else ps.setNull(2, java.sql.Types.INTEGER);
            ps.setLong(3, seenAt);
            ps.setLong(4, shownMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE image SET last_seen_at = ?, seen_count = seen_count + 1 WHERE id = ?")) {
            ps.setLong(1, seenAt);
            ps.setInt(2, imageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Every active image whose original isn't on disk under the library (a moved drive, a bad sync). */
    public List<ImageRecord> listMissingFiles(com.gesturtle.config.LibraryRef ref) {
        List<ImageRecord> missing = new ArrayList<>();
        for (ImageRecord record : query(new Filter.All(), OrderMode.SEQUENTIAL, null)) {
            if (!java.nio.file.Files.isRegularFile(ref.imagePath(record.hash(), record.ext()))) missing.add(record);
        }
        return missing;
    }

    private void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    private ImageRecord mapRowWithTags(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        return mapRow(rs, loadTagIds(List.of(id)).getOrDefault(id, List.of()));
    }

    private List<ImageRecord> mapRowsWithTags(ResultSet rs) throws SQLException {
        List<ImageRecord> rows = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        List<Object[]> buffered = new ArrayList<>();
        while (rs.next()) {
            int id = rs.getInt("id");
            ids.add(id);
            buffered.add(new Object[]{
                    id, rs.getString("hash"), rs.getString("ext"), rs.getString("original_name"),
                    rs.getInt("width"), rs.getInt("height"), rs.getLong("byte_size"), rs.getLong("imported_at"),
                    rs.getString("source_path"), rs.getInt("favorite") == 1,
                    rs.getObject("last_seen_at") == null ? null : rs.getLong("last_seen_at"),
                    rs.getInt("seen_count"), rs.getObject("deleted_at") == null ? null : rs.getLong("deleted_at"),
                    rs.getString("credit"), rs.getString("notes")
            });
        }
        Map<Integer, List<Integer>> tagsByImage = loadTagIds(ids);
        for (Object[] r : buffered) {
            int id = (int) r[0];
            rows.add(new ImageRecord((int) r[0], (String) r[1], (String) r[2], (String) r[3],
                    (int) r[4], (int) r[5], (long) r[6], (long) r[7], (String) r[8], (boolean) r[9],
                    (Long) r[10], (int) r[11], (Long) r[12], tagsByImage.getOrDefault(id, List.of()),
                    (String) r[13], (String) r[14]));
        }
        return rows;
    }

    private ImageRecord mapRow(ResultSet rs, List<Integer> tagIds) throws SQLException {
        return new ImageRecord(
                rs.getInt("id"), rs.getString("hash"), rs.getString("ext"), rs.getString("original_name"),
                rs.getInt("width"), rs.getInt("height"), rs.getLong("byte_size"), rs.getLong("imported_at"),
                rs.getString("source_path"), rs.getInt("favorite") == 1,
                rs.getObject("last_seen_at") == null ? null : rs.getLong("last_seen_at"),
                rs.getInt("seen_count"), rs.getObject("deleted_at") == null ? null : rs.getLong("deleted_at"),
                tagIds, rs.getString("credit"), rs.getString("notes"));
    }

    private Map<Integer, List<Integer>> loadTagIds(List<Integer> imageIds) {
        Map<Integer, List<Integer>> result = new HashMap<>();
        if (imageIds.isEmpty()) return result;
        String placeholders = String.join(",", imageIds.stream().map(x -> "?").toList());
        String sql = "SELECT image_id, tag_id FROM image_tag WHERE image_id IN (" + placeholders + ")";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < imageIds.size(); i++) {
                ps.setInt(i + 1, imageIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.computeIfAbsent(rs.getInt("image_id"), k -> new ArrayList<>()).add(rs.getInt("tag_id"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }
}
