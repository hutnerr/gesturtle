package com.gesturtle.meta;

import com.gesturtle.library.HashService;

import java.nio.file.Path;

/**
 * Where each kind of meta/ file lives. One file per image, tag, playlist,
 * view, and preset, so edits to different things on two machines are edits
 * to different files, which git merges without asking. Images go in
 * two-character folders like the originals, so no folder gets huge.
 */
public final class MetaFiles {
    public static final String IMAGE = "image";
    public static final String TAG = "tag";
    public static final String PLAYLIST = "playlist";
    public static final String VIEW = "view";
    public static final String PRESET = "preset";
    public static final String SETTINGS = "settings";

    private MetaFiles() {
    }

    /** The file for one thing, relative to meta/; key is an image's hash or a uid ("" for settings). */
    public static String path(String kind, String key) {
        return switch (kind) {
            case IMAGE -> "images/" + key.substring(0, 2) + "/" + key + ".json";
            case TAG -> "tags/" + key + ".json";
            case PLAYLIST -> "playlists/" + key + ".json";
            case VIEW -> "views/" + key + ".json";
            case PRESET -> "presets/" + key + ".json";
            case SETTINGS -> "settings.json";
            default -> throw new IllegalArgumentException("Unknown meta kind: " + kind);
        };
    }

    /** The kind and key a meta/-relative path (with / separators) stands for, or null for a file Gesturtle didn't write. */
    public static Entry parse(String path) {
        if (path.equals("settings.json")) return new Entry(SETTINGS, "");
        if (!path.endsWith(".json")) return null;
        String[] parts = path.substring(0, path.length() - ".json".length()).split("/");
        if (parts.length == 3 && parts[0].equals("images") && HashService.isValidHash(parts[2]) && parts[2].startsWith(parts[1])) {
            return new Entry(IMAGE, parts[2]);
        }
        if (parts.length != 2 || !isKey(parts[1])) return null;
        return switch (parts[0]) {
            case "tags" -> new Entry(TAG, parts[1]);
            case "playlists" -> new Entry(PLAYLIST, parts[1]);
            case "views" -> new Entry(VIEW, parts[1]);
            case "presets" -> new Entry(PRESET, parts[1]);
            default -> null;
        };
    }

    /** Hashes and uids are lowercase hex; anything else (a stray .orig, a conflict copy) is skipped. */
    private static boolean isKey(String s) {
        return s.length() >= 2 && s.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }

    public static String relative(Path metaDir, Path file) {
        return metaDir.relativize(file).toString().replace('\\', '/');
    }

    public record Entry(String kind, String key) {
    }
}
