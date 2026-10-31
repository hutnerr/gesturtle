package com.gesturtle.model;

/**
 * coverImageId is the image the user picked to stand for the playlist, or,
 * when they haven't picked one (or it was removed), the playlist's first
 * image; null for an empty playlist. explicitCover says which.
 */
public record Playlist(int id, String name, long createdAt, Integer coverImageId, boolean explicitCover) {
    public Playlist(int id, String name, long createdAt) {
        this(id, name, createdAt, null, false);
    }
}
