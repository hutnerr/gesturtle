package com.gesturtle.db.dao;

import com.gesturtle.model.SessionSummary;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Tracks practice_session rows; per-image view_event rows are written by ImageDao.recordView. */
public class ViewDao {
    private final Connection connection;

    public ViewDao(Connection connection) {
        this.connection = connection;
    }

    public int startSession(Integer presetId, long startedAt) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO practice_session(preset_id, started_at, images_shown) VALUES (?, ?, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            if (presetId != null) ps.setInt(1, presetId); else ps.setNull(1, java.sql.Types.INTEGER);
            ps.setLong(2, startedAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void endSession(int sessionId, long endedAt, int imagesShown) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE practice_session SET ended_at = ?, images_shown = ? WHERE id = ?")) {
            ps.setLong(1, endedAt);
            ps.setInt(2, imagesShown);
            ps.setInt(3, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Every session that showed at least one image, newest first. Image counts
     * and end times come from view_event, so a session cut short by closing the
     * app (never marked ended) still counts up to its last image.
     */
    public List<SessionSummary> listSessions() {
        String sql = """
                SELECT s.id, p.name AS preset_name, s.started_at,
                       COALESCE(s.ended_at, MAX(v.seen_at), s.started_at) AS ended_at,
                       COUNT(DISTINCT v.image_id) AS images_shown
                FROM practice_session s
                JOIN view_event v ON v.session_id = s.id
                LEFT JOIN preset p ON p.id = s.preset_id
                GROUP BY s.id
                ORDER BY s.started_at DESC""";
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            List<SessionSummary> sessions = new ArrayList<>();
            while (rs.next()) {
                sessions.add(new SessionSummary(rs.getInt("id"), rs.getString("preset_name"),
                        rs.getLong("started_at"), rs.getLong("ended_at"), rs.getInt("images_shown")));
            }
            return sessions;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Practice time spent on images carrying a tag (directly), counted once per tag an image has. */
    public record TagTime(int tagId, long millis, int views) {
    }

    /** Time per tag across view_event rows since the cutoff (0 for all time), most time first. */
    public List<TagTime> timeByTag(long sinceMillis) {
        String sql = """
                SELECT it.tag_id, SUM(v.shown_ms) AS millis, COUNT(*) AS views
                FROM view_event v
                JOIN image_tag it ON it.image_id = v.image_id
                WHERE v.seen_at >= ?
                GROUP BY it.tag_id
                ORDER BY millis DESC""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sinceMillis);
            try (ResultSet rs = ps.executeQuery()) {
                List<TagTime> rows = new ArrayList<>();
                while (rs.next()) rows.add(new TagTime(rs.getInt("tag_id"), rs.getLong("millis"), rs.getInt("views")));
                return rows;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** One image shown once, for the CSV export. sessionId and presetName may be null. */
    public record ViewRow(Integer sessionId, Long sessionStartedAt, String presetName, long seenAt, long shownMs,
                          int imageId, String imageName) {
    }

    /** Every viewing, oldest first. */
    public List<ViewRow> listViewHistory() {
        String sql = """
                SELECT v.session_id, s.started_at, p.name AS preset_name, v.seen_at, v.shown_ms,
                       v.image_id, i.original_name
                FROM view_event v
                JOIN image i ON i.id = v.image_id
                LEFT JOIN practice_session s ON s.id = v.session_id
                LEFT JOIN preset p ON p.id = s.preset_id
                ORDER BY v.seen_at ASC""";
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            List<ViewRow> rows = new ArrayList<>();
            while (rs.next()) {
                Object session = rs.getObject("session_id");
                Object started = rs.getObject("started_at");
                rows.add(new ViewRow(session == null ? null : ((Number) session).intValue(),
                        started == null ? null : ((Number) started).longValue(), rs.getString("preset_name"),
                        rs.getLong("seen_at"), rs.getLong("shown_ms"), rs.getInt("image_id"), rs.getString("original_name")));
            }
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
