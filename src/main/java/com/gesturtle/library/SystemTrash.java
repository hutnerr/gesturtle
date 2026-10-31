package com.gesturtle.library;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Moves files to the operating system's trash, so a deleted picture can be
 * restored from the Recycle Bin or Trash like any other file. Windows and
 * macOS go through java.awt.Desktop; AWT doesn't support trashing on Linux,
 * so there the freedesktop.org trash spec is followed directly, which every
 * Linux file manager reads.
 */
public final class SystemTrash {
    /** What the platform calls it, for dialogs. */
    public static final String NAME = System.getProperty("os.name", "").toLowerCase().startsWith("windows")
            ? "Recycle Bin" : "Trash";

    private static final DateTimeFormatter DELETION_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private SystemTrash() {
    }

    public static void moveToTrash(Path file) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            if (!Desktop.getDesktop().moveToTrash(file.toFile())) {
                throw new IOException("Could not move " + file.getFileName() + " to the " + NAME);
            }
            return;
        }
        freedesktopTrash(file.toAbsolutePath());
    }

    /**
     * Into $XDG_DATA_HOME/Trash: the file under files/, and a .trashinfo under
     * info/ recording where it came from, which is what "Restore" uses. The
     * info file is created first and exclusively, which claims the name.
     */
    private static void freedesktopTrash(Path file) throws IOException {
        String dataHome = System.getenv("XDG_DATA_HOME");
        Path trash = (dataHome == null || dataHome.isBlank()
                ? Path.of(System.getProperty("user.home"), ".local", "share") : Path.of(dataHome)).resolve("Trash");
        Path filesDir = Files.createDirectories(trash.resolve("files"));
        Path infoDir = Files.createDirectories(trash.resolve("info"));
        String info = "[Trash Info]\nPath=" + file.toUri().getRawPath()
                + "\nDeletionDate=" + LocalDateTime.now().format(DELETION_DATE) + "\n";

        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; ; n++) {
            String trashedName = n == 1 ? name : stem + "." + n + ext;
            Path infoFile = infoDir.resolve(trashedName + ".trashinfo");
            try {
                Files.writeString(infoFile, info, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException taken) {
                continue;
            }
            try {
                Files.move(file, filesDir.resolve(trashedName));
            } catch (IOException e) {
                Files.deleteIfExists(infoFile);
                throw e;
            }
            return;
        }
    }
}
