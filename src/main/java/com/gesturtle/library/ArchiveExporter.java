package com.gesturtle.library;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gesturtle.db.dao.TagDao;
import com.gesturtle.filter.Filter;
import com.gesturtle.filter.FilterCodec;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.OrderMode;
import com.gesturtle.model.Playlist;
import com.gesturtle.model.Preset;
import com.gesturtle.model.SavedView;
import com.gesturtle.model.Segment;
import com.gesturtle.model.SessionSpec;
import com.gesturtle.model.SourceKind;
import com.gesturtle.model.Tag;
import com.gesturtle.model.ViewEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a portable .zip archive of a library: manifest.json
 * plus the original image files, named by hash. Tags, playlists, and preset
 * segments are serialized by full path/name rather than numeric id, so the
 * archive can be re-imported into a different library without id
 * collisions: tags are matched by full path, and name-colliding presets
 * and playlists are suffixed. Deleted images are excluded, since
 * the archive is a snapshot of the active library, not of what was deleted.
 */
public class ArchiveExporter {
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new Gson();

    public record Options(boolean includeHistory, boolean includeThumbnails) {
    }

    public static void export(LibraryService library, Path zipPath, Options options) throws IOException {
        JsonObject manifest = buildManifest(library, options);
        List<ImageRecord> images = library.images().query(new Filter.All(), OrderMode.SEQUENTIAL, null);

        Files.createDirectories(zipPath.getParent());
        // Written as .part and renamed once complete, so a failed export (disk full, say) never leaves a
        // broken archive under the name the user picked.
        Path temp = zipPath.resolveSibling(zipPath.getFileName() + ".part");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
            writeBytesEntry(zip, "manifest.json", GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));

            for (ImageRecord image : images) {
                Path original = library.ref().imagePath(image.hash(), image.ext());
                if (Files.isRegularFile(original)) {
                    writeFileEntry(zip, "images/" + image.hash() + "." + image.ext(), original);
                }
                if (options.includeThumbnails()) {
                    Path thumb = library.ref().thumbPath(image.hash());
                    if (Files.isRegularFile(thumb)) {
                        writeFileEntry(zip, "thumbs/" + image.hash() + ".jpg", thumb);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, zipPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private static JsonObject buildManifest(LibraryService library, Options options) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("exportedAt", System.currentTimeMillis());

        TagDao tagDao = library.tags();
        JsonArray tagPaths = new JsonArray();
        for (Tag tag : tagDao.listAll()) {
            tagPaths.add(tagDao.fullPath(tag.id()));
        }
        root.add("tags", tagPaths);
        JsonArray tagOptions = new JsonArray();
        for (Tag tag : tagDao.listAll()) {
            if (!tag.groupOnly() && !tag.exclusiveChildren() && tag.color() == null && tag.description() == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("path", tagDao.fullPath(tag.id()));
            o.addProperty("groupOnly", tag.groupOnly());
            o.addProperty("exclusiveChildren", tag.exclusiveChildren());
            if (tag.color() != null) o.addProperty("color", tag.color());
            if (tag.description() != null) o.addProperty("description", tag.description());
            tagOptions.add(o);
        }
        root.add("tagOptions", tagOptions);

        JsonArray imagesJson = new JsonArray();
        for (ImageRecord image : library.images().query(new Filter.All(), OrderMode.SEQUENTIAL, null)) {
            imagesJson.add(encodeImage(image, tagDao, library, options));
        }
        root.add("images", imagesJson);

        JsonArray playlistsJson = new JsonArray();
        for (Playlist playlist : library.playlists().listAll()) {
            playlistsJson.add(encodePlaylist(playlist, library));
        }
        root.add("playlists", playlistsJson);

        JsonArray viewsJson = new JsonArray();
        for (SavedView view : library.savedViews().listAll()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", view.name());
            o.add("filter", encodePortableFilter(view.filter(), tagDao));
            viewsJson.add(o);
        }
        root.add("views", viewsJson);

        JsonArray presetsJson = new JsonArray();
        for (Preset preset : library.presets().listAll()) {
            presetsJson.add(encodePreset(preset, library, tagDao));
        }
        root.add("presets", presetsJson);

        return root;
    }

    private static JsonObject encodeImage(ImageRecord image, TagDao tagDao, LibraryService library, Options options) {
        JsonObject o = new JsonObject();
        o.addProperty("hash", image.hash());
        o.addProperty("ext", image.ext());
        if (image.originalName() != null) o.addProperty("originalName", image.originalName());
        o.addProperty("width", image.width());
        o.addProperty("height", image.height());
        o.addProperty("byteSize", image.byteSize());
        o.addProperty("importedAt", image.importedAt());
        o.addProperty("favorite", image.favorite());
        if (image.credit() != null) o.addProperty("credit", image.credit());
        if (image.notes() != null) o.addProperty("notes", image.notes());

        JsonArray tagPaths = new JsonArray();
        for (int tagId : image.tagIds()) {
            tagPaths.add(tagDao.fullPath(tagId));
        }
        o.add("tagPaths", tagPaths);

        if (options.includeHistory()) {
            JsonArray events = new JsonArray();
            for (ViewEvent event : library.images().listViewEvents(image.id())) {
                JsonObject e = new JsonObject();
                e.addProperty("seenAt", event.seenAt());
                e.addProperty("shownMs", event.shownMs());
                events.add(e);
            }
            o.add("viewEvents", events);
        } else {
            if (image.lastSeenAt() != null) o.addProperty("lastSeenAt", image.lastSeenAt());
            o.addProperty("seenCount", image.seenCount());
        }
        return o;
    }

    private static JsonObject encodePlaylist(Playlist playlist, LibraryService library) {
        JsonObject o = new JsonObject();
        o.addProperty("name", playlist.name());
        JsonArray hashes = new JsonArray();
        for (int imageId : library.playlists().listImageIds(playlist.id(), false)) {
            library.images().findById(imageId).ifPresent(img -> hashes.add(img.hash()));
        }
        o.add("imageHashes", hashes);
        if (playlist.explicitCover() && playlist.coverImageId() != null) {
            library.images().findById(playlist.coverImageId()).ifPresent(img -> o.addProperty("coverHash", img.hash()));
        }
        return o;
    }

    private static JsonObject encodePreset(Preset preset, LibraryService library, TagDao tagDao) {
        JsonObject o = new JsonObject();
        o.addProperty("name", preset.name());
        o.addProperty("pinned", preset.pinned());
        JsonArray segments = new JsonArray();
        SessionSpec spec = library.presets().loadSpec(preset.id());
        for (Segment segment : spec.segments()) {
            segments.add(encodeSegment(segment, library, tagDao));
        }
        o.add("segments", segments);
        return o;
    }

    private static JsonObject encodeSegment(Segment segment, LibraryService library, TagDao tagDao) {
        JsonObject o = new JsonObject();
        o.addProperty("sourceKind", segment.sourceKind().name());
        if (segment.sourceKind() == SourceKind.FILTER) {
            o.add("filter", encodePortableFilter(segment.filter(), tagDao));
        } else if (segment.sourceKind() == SourceKind.VIEW) {
            if (segment.viewId() != null) {
                library.savedViews().get(segment.viewId()).ifPresent(v -> o.addProperty("viewName", v.name()));
            }
        } else {
            library.playlists().listAll().stream()
                    .filter(p -> p.id() == segment.playlistId())
                    .findFirst()
                    .ifPresent(p -> o.addProperty("playlistName", p.name()));
        }
        if (segment.itemCount() != null) o.addProperty("itemCount", segment.itemCount());
        o.addProperty("orderMode", segment.orderMode().name());
        o.addProperty("secondsPerImage", segment.secondsPerImage());
        o.addProperty("tickBehavior", segment.tickBehavior().name());
        o.addProperty("breakSeconds", segment.breakSeconds());
        if (segment.memoryShowSeconds() != null) o.addProperty("memoryShowSeconds", segment.memoryShowSeconds());
        if (segment.memoryHideSeconds() != null) o.addProperty("memoryHideSeconds", segment.memoryHideSeconds());
        if (segment.rampToSeconds() != null) o.addProperty("rampToSeconds", segment.rampToSeconds());
        return o;
    }

    /** Same shape as FilterCodec, but a TagPredicate carries the tag's full path instead of its local numeric id. */
    private static JsonObject encodePortableFilter(Filter filter, TagDao tagDao) {
        return FilterCodec.toJson(filter, (p, o) -> o.addProperty("tagPath", tagDao.fullPath(p.tagId())));
    }

    private static void writeBytesEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static void writeFileEntry(ZipOutputStream zip, String name, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file, zip);
        zip.closeEntry();
    }
}
