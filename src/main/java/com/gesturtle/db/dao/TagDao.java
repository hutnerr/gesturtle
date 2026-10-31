package com.gesturtle.db.dao;

import com.gesturtle.model.Tag;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class TagDao {
    private final Connection connection;

    public TagDao(Connection connection) {
        this.connection = connection;
    }

    public int create(String name, Integer parentId) {
        if (siblingExists(name, parentId, null)) {
            throw new IllegalStateException("A tag named \"" + name + "\" already exists at this level");
        }
        String sql = "INSERT INTO tag(name, parent_id, created_at) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            if (parentId != null) ps.setInt(2, parentId); else ps.setNull(2, java.sql.Types.INTEGER);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create tag " + name, e);
        }
    }

    public void rename(int tagId, String newName) {
        Tag tag = get(tagId).orElseThrow();
        if (siblingExists(newName, tag.parentId(), tagId)) {
            throw new IllegalStateException("A tag named \"" + newName + "\" already exists at this level");
        }
        try (PreparedStatement ps = connection.prepareStatement("UPDATE tag SET name = ? WHERE id = ?")) {
            ps.setString(1, newName);
            ps.setInt(2, tagId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void reparent(int tagId, Integer newParentId) {
        if (newParentId != null) {
            if (newParentId == tagId || isDescendant(tagId, newParentId)) {
                throw new IllegalStateException("Cannot move a tag under its own descendant");
            }
        }
        Tag tag = get(tagId).orElseThrow();
        if (siblingExists(tag.name(), newParentId, tagId)) {
            throw new IllegalStateException("A tag named \"" + tag.name() + "\" already exists at this level");
        }
        try (PreparedStatement ps = connection.prepareStatement("UPDATE tag SET parent_id = ? WHERE id = ?")) {
            if (newParentId != null) ps.setInt(1, newParentId); else ps.setNull(1, java.sql.Types.INTEGER);
            ps.setInt(2, tagId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** A group-only tag organizes its children and is never applied to an image itself. */
    public void setGroupOnly(int tagId, boolean groupOnly) {
        setFlag("group_only", tagId, groupOnly);
    }

    /** With exclusive children, an image carries at most one of this tag's direct children. */
    public void setExclusiveChildren(int tagId, boolean exclusive) {
        setFlag("exclusive_children", tagId, exclusive);
    }

    /** One of TagColor's names, or null for no color. */
    public void setColor(int tagId, String color) {
        setText("color", tagId, color);
    }

    /** Blank clears it. */
    public void setDescription(int tagId, String description) {
        setText("description", tagId, description == null || description.isBlank() ? null : description.strip());
    }

    private void setText(String column, int tagId, String value) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE tag SET " + column + " = ? WHERE id = ?")) {
            ps.setString(1, value);
            ps.setInt(2, tagId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void setFlag(String column, int tagId, boolean on) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE tag SET " + column + " = ? WHERE id = ?")) {
            ps.setInt(1, on ? 1 : 0);
            ps.setInt(2, tagId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Deletes a tag; its children are re-parented to the deleted tag's own
     * parent. A child whose name is already taken there is merged into the
     * tag of that name. All or nothing.
     */
    public void deleteReassignChildren(int tagId) {
        Tag tag = get(tagId).orElseThrow();
        inTransaction(() -> {
            // Out of the way first: a child named like it moves up to where it sits now.
            execute("UPDATE tag SET name = ? WHERE id = ?", "(being deleted " + tagId + " " + System.nanoTime() + ")", tagId);
            for (Tag child : listChildren(tagId)) moveOrMergeChild(child, tag.parentId());
            execute("DELETE FROM tag WHERE id = ?", tagId);
        });
    }

    /**
     * Deletes a tag and every tag under it, in one statement (so the parent
     * links are only checked once all of them are gone). Images just lose
     * those tags.
     */
    public void deleteWithDescendants(int tagId) {
        inTransaction(() -> execute("""
                WITH RECURSIVE sub(id) AS (
                    SELECT ?
                    UNION ALL
                    SELECT t.id FROM tag t JOIN sub ON t.parent_id = sub.id
                )
                DELETE FROM tag WHERE id IN (SELECT id FROM sub)""", tagId));
    }

    /** How many tags sit anywhere under this one. */
    public int countDescendants(int tagId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                WITH RECURSIVE sub(id) AS (
                    SELECT id FROM tag WHERE parent_id = ?
                    UNION ALL
                    SELECT t.id FROM tag t JOIN sub ON t.parent_id = sub.id
                )
                SELECT COUNT(*) FROM sub""")) {
            ps.setInt(1, tagId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Moves all images and children from source onto target, then deletes
     * source. A child whose name target already has is merged into that
     * child, and saved views and presets that filtered by source filter by
     * target instead. All or nothing.
     */
    public void merge(int sourceId, int targetId) {
        if (sourceId == targetId) return;
        if (isDescendant(sourceId, targetId)) {
            throw new IllegalStateException("A tag can't be merged into one of its own children");
        }
        inTransaction(() -> mergeInto(sourceId, targetId));
    }

    private void mergeInto(int sourceId, int targetId) {
        execute("INSERT OR IGNORE INTO image_tag(image_id, tag_id) SELECT image_id, ? FROM image_tag WHERE tag_id = ?",
                targetId, sourceId);
        for (Tag child : listChildren(sourceId)) moveOrMergeChild(child, targetId);
        replaceInFilters("saved_view", sourceId, targetId);
        replaceInFilters("segment", sourceId, targetId);
        execute("DELETE FROM tag WHERE id = ?", sourceId);
    }

    private void moveOrMergeChild(Tag child, Integer newParentId) {
        Integer twin = childNamed(newParentId, child.name());
        if (twin != null) {
            mergeInto(child.id(), twin);
        } else {
            execute("UPDATE tag SET parent_id = ? WHERE id = ?", newParentId, child.id());
        }
    }

    private void replaceInFilters(String table, int fromTagId, int toTagId) {
        java.util.Map<Integer, String> rewritten = new java.util.HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, filter_json FROM " + table + " WHERE filter_json IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String json = rs.getString(2);
                String updated = com.gesturtle.filter.FilterCodec.encode(com.gesturtle.filter.FilterCodec.replaceTag(
                        com.gesturtle.filter.FilterCodec.decode(json), fromTagId, toTagId));
                if (!updated.equals(json)) rewritten.put(rs.getInt(1), updated);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        for (var e : rewritten.entrySet()) {
            execute("UPDATE " + table + " SET filter_json = ? WHERE id = ?", e.getValue(), e.getKey());
        }
    }

    private Integer childNamed(Integer parentId, String name) {
        String sql = parentId == null
                ? "SELECT id FROM tag WHERE parent_id IS NULL AND name = ?"
                : "SELECT id FROM tag WHERE parent_id = ? AND name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (parentId == null) {
                ps.setString(1, name);
            } else {
                ps.setInt(1, parentId);
                ps.setString(2, name);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void execute(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Runs work as one transaction, or as part of the caller's if one is already open. */
    private void inTransaction(Runnable work) {
        try {
            if (!connection.getAutoCommit()) {
                work.run();
                return;
            }
            connection.setAutoCommit(false);
            try {
                work.run();
                connection.commit();
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<Tag> get(int tagId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM tag WHERE id = ?")) {
            ps.setInt(1, tagId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Tag> listAll() {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM tag ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            List<Tag> tags = new ArrayList<>();
            while (rs.next()) tags.add(mapRow(rs));
            return tags;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Tag> listChildren(Integer parentId) {
        String sql = parentId == null
                ? "SELECT * FROM tag WHERE parent_id IS NULL ORDER BY name"
                : "SELECT * FROM tag WHERE parent_id = ? ORDER BY name";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (parentId != null) ps.setInt(1, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Tag> tags = new ArrayList<>();
                while (rs.next()) tags.add(mapRow(rs));
                return tags;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public String fullPath(int tagId) {
        List<String> parts = new ArrayList<>();
        Optional<Tag> current = get(tagId);
        while (current.isPresent()) {
            parts.add(0, current.get().name());
            Integer parentId = current.get().parentId();
            current = parentId == null ? Optional.empty() : get(parentId);
        }
        return String.join(" / ", parts);
    }

    public boolean isDescendant(int ancestorId, int candidateId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                WITH RECURSIVE sub(id) AS (
                    SELECT ?
                    UNION ALL
                    SELECT t.id FROM tag t JOIN sub ON t.parent_id = sub.id
                )
                SELECT 1 FROM sub WHERE id = ?""")) {
            ps.setInt(1, ancestorId);
            ps.setInt(2, candidateId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countDirect(int tagId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT COUNT(*) FROM image_tag it JOIN image i ON i.id = it.image_id
                WHERE it.tag_id = ? AND i.deleted_at IS NULL""")) {
            ps.setInt(1, tagId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countIncludingDescendants(int tagId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                WITH RECURSIVE sub(id) AS (
                    SELECT ?
                    UNION ALL
                    SELECT t.id FROM tag t JOIN sub ON t.parent_id = sub.id
                )
                SELECT COUNT(DISTINCT it.image_id) FROM image_tag it
                JOIN image i ON i.id = it.image_id
                WHERE it.tag_id IN (SELECT id FROM sub) AND i.deleted_at IS NULL""")) {
            ps.setInt(1, tagId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean siblingExists(String name, Integer parentId, Integer excludingId) {
        String sql = parentId == null
                ? "SELECT id FROM tag WHERE parent_id IS NULL AND name = ?"
                : "SELECT id FROM tag WHERE parent_id = ? AND name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (parentId == null) {
                ps.setString(1, name);
            } else {
                ps.setInt(1, parentId);
                ps.setString(2, name);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    if (excludingId == null || id != excludingId) return true;
                }
                return false;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Tag mapRow(ResultSet rs) throws SQLException {
        Object parent = rs.getObject("parent_id");
        return new Tag(rs.getInt("id"), rs.getString("name"),
                parent == null ? null : ((Number) parent).intValue(), rs.getLong("created_at"),
                rs.getInt("group_only") != 0, rs.getInt("exclusive_children") != 0,
                rs.getString("color"), rs.getString("description"));
    }
}
