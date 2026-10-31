package com.gesturtle.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes one action undoable by what it changed, not by restoring a backup.
 * The rows the action can touch are read before it and again after it;
 * undoing puts back the rows it removed, removes the rows it added, and
 * returns changed rows to how they were. Anything else changed in the
 * meantime (a favorite, a tag on another image) is left alone.
 *
 * Rows are matched by key: the table's id where it has one, otherwise the
 * whole row (image_tag, playlist_item are just their keys). The database
 * triggers see the restored rows like any other change, so meta/ follows.
 */
public final class UndoCapture {
    /**
     * One table to watch: rows matching where (with params), keyed by
     * keyColumns. Tables are listed parents first (tag before image_tag),
     * which is the order restored rows go back in.
     */
    public record Table(String name, List<String> keyColumns, String where, List<Object> params) {
        public static Table all(String name, String... keyColumns) {
            return new Table(name, List.of(keyColumns), "1=1", List.of());
        }

        public static Table where(String name, List<String> keyColumns, String where, Object... params) {
            return new Table(name, keyColumns, where, List.of(params));
        }
    }

    /** A row: its column values by name, in table order. */
    private record Snapshot(List<String> columns, Map<List<Object>, List<Object>> rowsByKey) {
    }

    private final Connection connection;
    private final List<Table> tables;
    private final List<Snapshot> before = new ArrayList<>();

    private UndoCapture(Connection connection, List<Table> tables) {
        this.connection = connection;
        this.tables = tables;
    }

    /** Reads the watched rows; call just before the action. */
    static UndoCapture before(Connection connection, Table... tables) {
        UndoCapture capture = new UndoCapture(connection, List.of(tables));
        for (Table t : tables) capture.before.add(capture.read(t));
        return capture;
    }

    /**
     * Reads the watched rows again, just after the action, and returns the
     * work that undoes it (to run on the DB thread), or null if it changed
     * nothing.
     */
    public Runnable after() {
        List<Diff> diffs = new ArrayList<>();
        boolean any = false;
        for (int i = 0; i < tables.size(); i++) {
            Diff diff = Diff.between(tables.get(i), before.get(i), read(tables.get(i)));
            any |= !diff.isEmpty();
            diffs.add(diff);
        }
        return any ? () -> undo(diffs) : null;
    }

    private record Diff(Table table, List<String> columns, List<List<Object>> removed, List<List<Object>> added,
                        List<List<Object>> changedBack) {
        static Diff between(Table table, Snapshot before, Snapshot after) {
            List<List<Object>> removed = new ArrayList<>();
            List<List<Object>> added = new ArrayList<>();
            List<List<Object>> changedBack = new ArrayList<>();
            for (var e : before.rowsByKey().entrySet()) {
                List<Object> now = after.rowsByKey().get(e.getKey());
                if (now == null) removed.add(e.getValue());
                else if (!now.equals(e.getValue())) changedBack.add(e.getValue());
            }
            for (var e : after.rowsByKey().entrySet()) {
                if (!before.rowsByKey().containsKey(e.getKey())) added.add(e.getValue());
            }
            return new Diff(table, before.columns(), removed, added, changedBack);
        }

        boolean isEmpty() {
            return removed.isEmpty() && added.isEmpty() && changedBack.isEmpty();
        }
    }

    /** One transaction: added rows out (children first), removed rows back and changed rows reverted (parents first). */
    private void undo(List<Diff> diffs) {
        try {
            connection.setAutoCommit(false);
            try {
                try (Statement st = connection.createStatement()) {
                    // A restored child can name a parent restored after it; checked once everything is back.
                    st.execute("PRAGMA defer_foreign_keys = ON");
                }
                for (int i = diffs.size() - 1; i >= 0; i--) {
                    Diff d = diffs.get(i);
                    for (List<Object> row : d.added()) delete(d, row);
                }
                for (Diff d : diffs) {
                    for (List<Object> row : d.removed()) insert(d, row);
                    for (List<Object> row : d.changedBack()) update(d, row);
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw new IllegalStateException("It can't be undone: the library changed since.", e);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Snapshot read(Table t) {
        Map<List<Object>, List<Object>> rows = new LinkedHashMap<>();
        List<String> columns = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM " + t.name() + " WHERE " + t.where())) {
            for (int i = 0; i < t.params().size(); i++) ps.setObject(i + 1, t.params().get(i));
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                for (int c = 1; c <= md.getColumnCount(); c++) columns.add(md.getColumnName(c));
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(columns.size());
                    for (int c = 1; c <= columns.size(); c++) row.add(rs.getObject(c));
                    rows.put(keyOf(t, columns, row), row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return new Snapshot(columns, rows);
    }

    private static List<Object> keyOf(Table t, List<String> columns, List<Object> row) {
        if (t.keyColumns().isEmpty()) return row;
        List<Object> key = new ArrayList<>();
        for (String k : t.keyColumns()) key.add(row.get(columns.indexOf(k)));
        return key;
    }

    private void insert(Diff d, List<Object> row) throws SQLException {
        String sql = "INSERT INTO " + d.table().name() + "(" + String.join(", ", d.columns()) + ") VALUES ("
                + String.join(", ", d.columns().stream().map(c -> "?").toList()) + ")";
        run(sql, row);
    }

    private void update(Diff d, List<Object> row) throws SQLException {
        List<String> keys = keyColumns(d);
        List<Object> params = new ArrayList<>(row);
        for (String k : keys) params.add(row.get(d.columns().indexOf(k)));
        String sql = "UPDATE " + d.table().name() + " SET "
                + String.join(", ", d.columns().stream().map(c -> c + " = ?").toList())
                + " WHERE " + String.join(" AND ", keys.stream().map(k -> k + " IS ?").toList());
        run(sql, params);
    }

    private void delete(Diff d, List<Object> row) throws SQLException {
        List<String> keys = keyColumns(d);
        List<Object> params = new ArrayList<>();
        for (String k : keys) params.add(row.get(d.columns().indexOf(k)));
        run("DELETE FROM " + d.table().name() + " WHERE "
                + String.join(" AND ", keys.stream().map(k -> k + " IS ?").toList()), params);
    }

    private static List<String> keyColumns(Diff d) {
        return d.table().keyColumns().isEmpty() ? d.columns() : d.table().keyColumns();
    }

    private void run(String sql, List<Object> params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            ps.executeUpdate();
        }
    }
}
