package com.gesturtle.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GuardedConnectionTest {

    /**
     * The FX thread writing while the DB thread has a transaction open: the
     * write must wait and stay, not join the transaction and vanish with
     * its rollback.
     */
    @Test
    void anotherThreadsWriteWaitsForAnOpenTransactionInsteadOfJoiningIt(@TempDir Path dir) throws Exception {
        try (Database db = new Database(dir.resolve("gesturtle.db"))) {
            Connection c = db.connection();
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO setting(key, value) VALUES ('import', 'half done')");
            }

            CompletableFuture<Void> otherThread = CompletableFuture.runAsync(() -> {
                try (Statement st = c.createStatement()) {
                    st.execute("INSERT INTO setting(key, value) VALUES ('favorite', 'kept')");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.sleep(200);
            assertFalse(otherThread.isDone(), "it waits while the transaction is open");

            c.rollback();
            c.setAutoCommit(true);
            otherThread.get(5, TimeUnit.SECONDS);

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT group_concat(key) FROM setting WHERE key IN ('import', 'favorite')")) {
                rs.next();
                assertEquals("favorite", rs.getString(1));
            }
        }
    }
}
