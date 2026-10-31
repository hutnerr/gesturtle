package com.gesturtle.library;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists the files in a folder the user picked. Unlike Files.walk, a folder
 * that can't be read is skipped rather than failing the whole scan: a drive
 * root on Windows holds "System Volume Information", and a user profile
 * holds old junctions like "My Music" that deny listing, and either would
 * otherwise stop an import or a missing-file search outright.
 */
public final class FileScan {
    private FileScan() {
    }

    /** Regular files under dir (in subfolders too when recursive), sorted by path. */
    public static List<Path> files(Path dir, boolean recursive) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(dir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), recursive ? Integer.MAX_VALUE : 1,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()) files.add(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                        // The folder itself is unreadable: that's worth telling the user.
                        if (file.equals(dir)) throw e;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path d, IOException e) {
                        // A folder that failed partway through listing keeps what was read.
                        return FileVisitResult.CONTINUE;
                    }
                });
        files.sort(null);
        return files;
    }
}
