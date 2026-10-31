package com.gesturtle.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies ordered SQL migration scripts from resources/db/migrations.
 * Each script is named V<number>__<description>.sql; applied scripts are
 * recorded in schema_version so re-opening a library is a no-op.
 *
 * Before 1.0, V001-V008 were squashed into today's V001. Databases from the
 * betas are brought up to date with the old scripts (kept in legacy/) and
 * then recorded as V001, which they then match exactly. So that a beta
 * database can always be told apart, numbers 2-8 are never reused: the next
 * migration is V009.
 */
public class Migrations {
    private static final Pattern FILE_PATTERN = Pattern.compile("V(\\d+)__.*\\.sql");
    private static final String[] KNOWN_MIGRATIONS = {"V001__init.sql"};
    private static final String[] LEGACY_MIGRATIONS = {
            "legacy/V002__ramp_timer.sql", "legacy/V003__saved_views.sql", "legacy/V004__image_credit_notes.sql",
            "legacy/V005__tag_options.sql", "legacy/V006__ratings_tag_style_covers.sql",
            "legacy/V007__drop_ratings.sql", "legacy/V008__meta_keys.sql"};
    private static final int LAST_LEGACY_VERSION = 8;

    public static void applyAll(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, applied_at INTEGER)");
        }

        upgradeBetaDatabase(connection);
        int current = currentVersion(connection);
        int newest = versionOf(KNOWN_MIGRATIONS[KNOWN_MIGRATIONS.length - 1]);
        if (current > newest) {
            throw new NewerDatabaseException();
        }

        List<String> pending = new ArrayList<>();
        for (String name : KNOWN_MIGRATIONS) {
            if (versionOf(name) > current) pending.add(name);
        }
        pending.sort(Comparator.naturalOrder());
        for (String name : pending) apply(connection, name, versionOf(name));
    }

    /**
     * A beta database is one that recorded any of 2-8, or recorded 1 before
     * V001 had tag.uid (the old V001). It runs the old scripts it's missing,
     * then keeps only the record of 1.
     */
    private static void upgradeBetaDatabase(Connection connection) throws SQLException {
        int current = currentVersion(connection);
        if (current == 0) return;
        boolean beta = hasLegacyVersion(connection) || !hasColumn(connection, "tag", "uid");
        if (!beta) return;
        for (String name : LEGACY_MIGRATIONS) {
            int version = versionOf(name);
            if (version > current) apply(connection, name, version);
        }
        try (Statement st = connection.createStatement()) {
            st.execute("DELETE FROM schema_version WHERE version BETWEEN 2 AND " + LAST_LEGACY_VERSION);
        }
    }

    private static void apply(Connection connection, String name, int version) throws SQLException {
        String sql = readResource(name);
        connection.setAutoCommit(false);
        try (Statement st = connection.createStatement()) {
            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
            try (var ps = connection.prepareStatement(
                    "INSERT INTO schema_version(version, applied_at) VALUES (?, ?)")) {
                ps.setInt(1, version);
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw new RuntimeException("Migration " + name + " failed", e);
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static int versionOf(String name) {
        Matcher m = FILE_PATTERN.matcher(name.substring(name.lastIndexOf('/') + 1));
        if (!m.matches()) throw new IllegalStateException("Bad migration name: " + name);
        return Integer.parseInt(m.group(1));
    }

    private static boolean hasLegacyVersion(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM schema_version WHERE version BETWEEN 2 AND " + LAST_LEGACY_VERSION)) {
            return rs.next();
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) return true;
            }
            return false;
        }
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String readResource(String name) {
        String path = "/db/migrations/" + name;
        try (InputStream in = Migrations.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing migration resource: " + path);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read migration " + name, e);
        }
    }

    /** The database was last opened by a newer Gesturtle, whose tables this version would misread. */
    public static final class NewerDatabaseException extends RuntimeException {
        NewerDatabaseException() {
            super("This library's database was updated by a newer version of Gesturtle. "
                    + "Update Gesturtle to open it.");
        }
    }
}
