package com.gesturtle.model;

/** A saved, named SessionSpec; its segments live in the `segment` table. */
public record Preset(int id, String name, long createdAt, Long lastUsedAt, int useCount, boolean pinned) {
}
