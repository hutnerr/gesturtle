package com.gesturtle.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {
    @Test
    void writesLandInTheDatabaseFileItselfWithNoWalSideFile(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("gesturtle.db");
        try (Database database = new Database(dbFile);
             Statement st = database.connection().createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                rs.next();
                assertEquals("delete", rs.getString(1));
            }
            st.execute("INSERT INTO setting(key, value) VALUES ('k', 'v')");
            // What git would commit while the app is still open: the main file, and nothing beside it.
            assertFalse(Files.exists(tempDir.resolve("gesturtle.db-wal")));
        }
    }

    @Test
    void aLibraryCreatedInWalModeIsConvertedOnOpen(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("gesturtle.db");
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
        }
        try (Database database = new Database(dbFile);
             Statement st = database.connection().createStatement();
             ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
            rs.next();
            assertEquals("delete", rs.getString(1));
        }
    }

    // Windows won't rename over a file that's open, and has no file key to compare.
    @DisabledOnOs(OS.WINDOWS)
    @Test
    void replacingTheFileUnderAnOpenDatabaseIsDetected(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("gesturtle.db");
        try (Database database = new Database(dbFile)) {
            assertFalse(database.replacedOnDisk());
            // Like git checkout: a new file is written and renamed over the old one.
            Path pulled = tempDir.resolve("pulled.db");
            Files.copy(dbFile, pulled);
            Files.move(pulled, dbFile, StandardCopyOption.REPLACE_EXISTING);
            assertTrue(database.replacedOnDisk());
        }
    }
}
