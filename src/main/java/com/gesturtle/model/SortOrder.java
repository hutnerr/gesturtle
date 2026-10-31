package com.gesturtle.model;

/**
 * How the Gallery orders its results. Each one is a complete ORDER BY on
 * "image i", ending in a tiebreak, so equal keys keep a stable order.
 */
public enum SortOrder {
    NEWEST("Newest first", "i.imported_at DESC, i.id DESC"),
    OLDEST("Oldest first", "i.imported_at ASC, i.id ASC"),
    NAME("Name A–Z", "i.original_name COLLATE NOCASE ASC, i.id ASC"),
    NAME_DESC("Name Z–A", "i.original_name COLLATE NOCASE DESC, i.id DESC"),
    RECENTLY_PRACTICED("Recently practiced", "i.last_seen_at IS NULL, i.last_seen_at DESC, i.id ASC"),
    LEAST_RECENTLY_PRACTICED("Least recently practiced", "i.last_seen_at IS NOT NULL, i.last_seen_at ASC, i.id ASC"),
    MOST_PRACTICED("Most practiced", "i.seen_count DESC, i.imported_at DESC"),
    LEAST_PRACTICED("Least practiced", "i.seen_count ASC, i.imported_at DESC"),
    LARGEST("Largest file", "i.byte_size DESC, i.id ASC"),
    RANDOM("Random", "RANDOM()");

    public final String label;
    public final String sql;

    SortOrder(String label, String sql) {
        this.label = label;
        this.sql = sql;
    }

    /** Falls back to NEWEST for null or a name a newer build saved. */
    public static SortOrder parse(String name) {
        if (name == null) return NEWEST;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return NEWEST;
        }
    }
}
