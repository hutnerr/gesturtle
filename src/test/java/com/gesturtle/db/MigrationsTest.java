package com.gesturtle.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Beta databases were built by the pre-squash V001-V008; each must come out
 * as a current V001 database with its data intact.
 */
class MigrationsTest {
    private static final String[] BETA_SCRIPTS = {
            "/db/beta/V001__init.sql",
            "/db/migrations/legacy/V002__ramp_timer.sql", "/db/migrations/legacy/V003__saved_views.sql",
            "/db/migrations/legacy/V004__image_credit_notes.sql", "/db/migrations/legacy/V005__tag_options.sql",
            "/db/migrations/legacy/V006__ratings_tag_style_covers.sql", "/db/migrations/legacy/V007__drop_ratings.sql",
            "/db/migrations/legacy/V008__meta_keys.sql"};

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 7, 8})
    void aBetaDatabaseIsBroughtUpToDateAndKeepsItsData(int betaVersion, @TempDir Path dir) throws Exception {
        Path dbFile = dir.resolve("gesturtle.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at INTEGER)");
            for (int v = 1; v <= betaVersion; v++) {
                for (String sql : read(BETA_SCRIPTS[v - 1]).split(";")) {
                    if (!sql.isBlank()) st.execute(sql.trim());
                }
                st.execute("INSERT INTO schema_version VALUES (" + v + ", 0)");
                // Made early in the beta, so it rides through every later script.
                if (v == 1) st.execute("INSERT INTO tag(name, parent_id, created_at) VALUES ('hands', NULL, 0)");
            }
        }

        try (Database db = new Database(dbFile); Statement st = db.connection().createStatement()) {
            assertEquals(List.of(1), ints(st, "SELECT version FROM schema_version"));
            try (ResultSet rs = st.executeQuery("SELECT name, uid FROM tag")) {
                rs.next();
                assertEquals("hands", rs.getString("name"));
                assertEquals(32, rs.getString("uid").length());
            }
        }
        // And opening it again changes nothing.
        try (Database db = new Database(dbFile); Statement st = db.connection().createStatement()) {
            assertEquals(List.of(1), ints(st, "SELECT version FROM schema_version"));
        }
    }

    @Test
    void aDatabaseFromANewerVersionIsRefused(@TempDir Path dir) throws Exception {
        Path dbFile = dir.resolve("gesturtle.db");
        new Database(dbFile).close();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile); Statement st = c.createStatement()) {
            st.execute("INSERT INTO schema_version VALUES (9, 0)");
        }
        assertThrows(Migrations.NewerDatabaseException.class, () -> new Database(dbFile));
    }

    private static List<Integer> ints(Statement st, String sql) throws Exception {
        List<Integer> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = MigrationsTest.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
