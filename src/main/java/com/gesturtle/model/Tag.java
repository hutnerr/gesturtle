package com.gesturtle.model;

/**
 * A tag. groupOnly tags organize others and are never applied to an image
 * themselves; exclusiveChildren means an image may carry at most one of the
 * tag's direct children (e.g. "orientation › front / back / side"). color is
 * one of {@link TagColor}'s names, or null; description is free text, or null.
 */
public record Tag(int id, String name, Integer parentId, long createdAt, boolean groupOnly, boolean exclusiveChildren,
                  String color, String description) {
    public Tag(int id, String name, Integer parentId, long createdAt, boolean groupOnly, boolean exclusiveChildren) {
        this(id, name, parentId, createdAt, groupOnly, exclusiveChildren, null, null);
    }
}
