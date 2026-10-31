package com.gesturtle.db.dao;

import com.gesturtle.filter.Filter;
import com.gesturtle.filter.FilterCodec;
import com.gesturtle.model.SavedView;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Saved Gallery queries. Named SavedViewDao because ViewDao already means
 * practice sessions/view events.
 */
public class SavedViewDao {
    private final Connection connection;

    public SavedViewDao(Connection connection) {
        this.connection = connection;
    }

    public int create(String name, Filter filter) {
        if (nameExists(name, null)) {
            throw new IllegalStateException("A view named \"" + name + "\" already exists");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO saved_view(name, filter_json, created_at) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, FilterCodec.encode(filter));
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save view " + name, e);
        }
    }

    public void updateFilter(int viewId, Filter filter) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE saved_view SET filter_json = ? WHERE id = ?")) {
            ps.setString(1, FilterCodec.encode(filter));
            ps.setInt(2, viewId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void rename(int viewId, String name) {
        if (nameExists(name, viewId)) {
            throw new IllegalStateException("A view named \"" + name + "\" already exists");
        }
        try (PreparedStatement ps = connection.prepareStatement("UPDATE saved_view SET name = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setInt(2, viewId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Practice segments that used this view keep their row; their view_id becomes NULL (see V003). */
    public void delete(int viewId) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM saved_view WHERE id = ?")) {
            ps.setInt(1, viewId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<SavedView> get(int viewId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM saved_view WHERE id = ?")) {
            ps.setInt(1, viewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<SavedView> listAll() {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM saved_view ORDER BY name COLLATE NOCASE");
             ResultSet rs = ps.executeQuery()) {
            List<SavedView> views = new ArrayList<>();
            while (rs.next()) views.add(mapRow(rs));
            return views;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private SavedView mapRow(ResultSet rs) throws SQLException {
        return new SavedView(rs.getInt("id"), rs.getString("name"),
                FilterCodec.decode(rs.getString("filter_json")), rs.getLong("created_at"));
    }

    private boolean nameExists(String name, Integer excludingId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM saved_view WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (excludingId == null || rs.getInt("id") != excludingId) return true;
                }
                return false;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
