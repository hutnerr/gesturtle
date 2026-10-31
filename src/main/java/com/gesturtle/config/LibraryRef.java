package com.gesturtle.config;

import java.nio.file.Path;

/**
 * Resolves the fixed subpaths inside a chosen library root.
 */
public class LibraryRef {
    private final Path root;

    public LibraryRef(Path root) {
        this.root = root;
    }

    public static LibraryRef of(String path) {
        return new LibraryRef(Path.of(path));
    }

    public Path root() {
        return root;
    }

    public Path dbFile() {
        return root.resolve("gesturtle.db");
    }

    /** Held while the library is open; named so the gesturtle.db* gitignore rule covers it. */
    public Path lockFile() {
        return root.resolve("gesturtle.db.lock");
    }

    public Path imagesDir() {
        return root.resolve("images");
    }

    public Path thumbsDir() {
        return root.resolve("thumbs");
    }

    public Path exportsDir() {
        return root.resolve("exports");
    }

    /** The JSON files git tracks: tags, image details, playlists, views, presets (see meta.MetaStore). */
    public Path metaDir() {
        return root.resolve("meta");
    }

    /** Content-addressed path for an original: images/ab/cd/<hash>.<ext> */
    public Path imagePath(String hash, String ext) {
        return fanout(imagesDir(), hash).resolve(hash + "." + ext);
    }

    /** Content-addressed path for a cached thumbnail: thumbs/ab/cd/<hash>.jpg */
    public Path thumbPath(String hash) {
        return fanout(thumbsDir(), hash).resolve(hash + ".jpg");
    }

    private static Path fanout(Path base, String hash) {
        return base.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4));
    }

    /** Git doesn't keep empty folders, so a fresh clone may be missing these; they're recreated on every open. */
    public void ensureLayout() throws java.io.IOException {
        java.nio.file.Files.createDirectories(root);
        java.nio.file.Files.createDirectories(imagesDir());
        java.nio.file.Files.createDirectories(thumbsDir());
        java.nio.file.Files.createDirectories(exportsDir());
        java.nio.file.Files.createDirectories(metaDir());
        dropStaleThumbs();
        writeIfMissing(".gitignore", GITIGNORE);
        writeIfMissing(".gitattributes", GITATTRIBUTES);
        // A .gitignore the user edited is kept, but the database must still stay out of git.
        appendIfMissing(".gitignore", "/gesturtle.db*", "# Written by Gesturtle: the local database, rebuilt from meta/.\n/gesturtle.db*\n");
    }

    /**
     * So the library folder can be a git repo as is: git tracks the pictures
     * and meta/. The database is a local copy built from meta/ plus this
     * machine's practice history, thumbnails are rebuilt from the originals,
     * and exports are copies of the library. Paths are anchored to this
     * folder, so they also work when the library is a subfolder of a bigger repo.
     */
    private static final String GITIGNORE = """
            # Written by Gesturtle. Everything else in this folder is the library.
            /gesturtle.db*
            /thumbs/
            /exports/
            *.part
            """;

    /**
     * Pictures are never diffed, merged, or line-ending converted (core.autocrlf
     * would corrupt them on Windows). meta/ keeps LF line endings everywhere, so
     * a file Gesturtle rewrites unchanged stays byte-for-byte the same.
     */
    private static final String GITATTRIBUTES = """
            # Written by Gesturtle.
            /images/** binary
            /meta/** text eol=lf
            """;

    /** Bump whenever ThumbnailCache changes how thumbnails are made (size, scaling, quality). */
    private static final String THUMBS_VERSION = "2";

    /**
     * Thumbnails made by an older version are deleted, so each one is rebuilt
     * the new way the next time it's shown. A thumbs/.version file records
     * which version made them.
     */
    private void dropStaleThumbs() throws java.io.IOException {
        java.nio.file.Path marker = thumbsDir().resolve(".version");
        if (java.nio.file.Files.isRegularFile(marker)
                && java.nio.file.Files.readString(marker).strip().equals(THUMBS_VERSION)) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(thumbsDir())) {
            for (java.nio.file.Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!p.equals(thumbsDir())) java.nio.file.Files.deleteIfExists(p);
            }
        }
        java.nio.file.Files.writeString(marker, THUMBS_VERSION);
    }

    private void appendIfMissing(String name, String line, String addition) throws java.io.IOException {
        java.nio.file.Path file = root.resolve(name);
        String current = java.nio.file.Files.readString(file);
        if (current.lines().anyMatch(l -> l.strip().equals(line))) return;
        java.nio.file.Files.writeString(file, current + (current.isEmpty() || current.endsWith("\n") ? "" : "\n") + addition);
    }

    /** Leaves an existing file alone, so the user's own rules (e.g. Git LFS) win. */
    private void writeIfMissing(String name, String content) throws java.io.IOException {
        java.nio.file.Path file = root.resolve(name);
        if (!java.nio.file.Files.exists(file)) java.nio.file.Files.writeString(file, content);
    }
}
