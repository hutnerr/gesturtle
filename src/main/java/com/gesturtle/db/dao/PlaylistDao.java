package com.gesturtle.db.dao;

import com.gesturtle.model.Playlist;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class PlaylistDao {
    private final Connection connection;

    public PlaylistDao(Connection connection) {
        this.connection = connection;
    }

    public int create(String name) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO playlist(name, created_at) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create playlist " + name, e);
        }
    }

    public void rename(int playlistId, String name) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE playlist SET name = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setInt(2, playlistId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void delete(int playlistId) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM playlist WHERE id = ?")) {
            ps.setInt(1, playlistId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** With each playlist's cover: the chosen one while it's still in the playlist and not recycled, else the first image. */
    public List<Playlist> listAll() {
        String sql = """
                SELECT p.*,
                       (SELECT pi.image_id FROM playlist_item pi JOIN image i ON i.id = pi.image_id
                        WHERE pi.playlist_id = p.id AND pi.image_id = p.cover_image_id AND i.deleted_at IS NULL) AS chosen_cover,
                       (SELECT pi.image_id FROM playlist_item pi JOIN image i ON i.id = pi.image_id
                        WHERE pi.playlist_id = p.id AND i.deleted_at IS NULL ORDER BY pi.position LIMIT 1) AS first_image
                FROM playlist p ORDER BY p.name""";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Playlist> playlists = new ArrayList<>();
            while (rs.next()) {
                Object chosen = rs.getObject("chosen_cover");
                Object first = rs.getObject("first_image");
                Integer cover = null;
                if (chosen != null) cover = ((Number) chosen).intValue();
                else if (first != null) cover = ((Number) first).intValue();
                playlists.add(new Playlist(rs.getInt("id"), rs.getString("name"), rs.getLong("created_at"),
                        cover, chosen != null));
            }
            return playlists;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Null goes back to the first image. */
    public void setCover(int playlistId, Integer imageId) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE playlist SET cover_image_id = ? WHERE id = ?")) {
            if (imageId != null) ps.setInt(1, imageId); else ps.setNull(1, java.sql.Types.INTEGER);
            ps.setInt(2, playlistId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void addItem(int playlistId, int imageId) {
        try (PreparedStatement count = connection.prepareStatement(
                "SELECT COALESCE(MAX(position), -1) FROM playlist_item WHERE playlist_id = ?")) {
            count.setInt(1, playlistId);
            int nextPosition;
            try (ResultSet rs = count.executeQuery()) {
                rs.next();
                nextPosition = rs.getInt(1) + 1;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO playlist_item(playlist_id, image_id, position) VALUES (?, ?, ?)")) {
                ps.setInt(1, playlistId);
                ps.setInt(2, imageId);
                ps.setInt(3, nextPosition);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void removeItem(int playlistId, int imageId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM playlist_item WHERE playlist_id = ? AND image_id = ?")) {
            ps.setInt(1, playlistId);
            ps.setInt(2, imageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Integer> listImageIds(int playlistId, boolean random) {
        String sql = "SELECT image_id FROM playlist_item WHERE playlist_id = ? ORDER BY "
                + (random ? "RANDOM()" : "position ASC");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, playlistId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Integer> ids = new ArrayList<>();
                while (rs.next()) ids.add(rs.getInt("image_id"));
                return ids;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
