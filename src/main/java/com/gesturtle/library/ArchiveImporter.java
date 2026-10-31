package com.gesturtle.library;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import com.gesturtle.model.TickBehavior;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads an archive written by ArchiveExporter and merges it into an open
 * library. Images already present by hash are skipped but
 * gain the archive's tags and favorite flag; tags are matched by full path
 * and created as needed; name-colliding presets and playlists are suffixed
 * rather than overwritten. Thumbnails are always regenerated locally from
 * the extracted original rather than trusted from the archive, so whether
 * the archive bundled them only affects its file size, never correctness.
 */
public class ArchiveImporter {
    private static final Gson GSON = new Gson();

    public record Summary(int newImages, int alreadyPresentImages, int newTags, int totalPlaylists, int totalPresets) {
    }

    public record Result(int newImages, int taggedExisting, int newTagsCreated, int playlistsImported,
                          int presetsImported) {
    }

    public static Summary dryRun(LibraryService library, Path zipPath) throws IOException {
        JsonObject manifest = readManifest(zipPath);
        JsonArray images = manifest.getAsJsonArray("images");
        int newImages = 0;
        int alreadyPresent = 0;
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            for (JsonElement el : images) {
                JsonObject img = el.getAsJsonObject();
                String hash = img.get("hash").getAsString();
                if (library.images().existsByHash(hash)) alreadyPresent++;
                else if (hasOriginal(zip, hash, img.get("ext").getAsString())) newImages++;
            }
        }

        int newTags = 0;
        for (JsonElement el : manifest.getAsJsonArray("tags")) {
            if (resolveExistingTagPath(library.tags(), el.getAsString()) == null) newTags++;
        }

        return new Summary(newImages, alreadyPresent, newTags,
                manifest.getAsJsonArray("playlists").size(), manifest.getAsJsonArray("presets").size());
    }

    /**
     * One transaction, so an archive that fails partway (a damaged image, a
     * full disk) changes nothing: the rows roll back and the originals copied
     * so far are removed again. A retry then starts clean instead of
     * suffixing a second copy of every playlist and preset.
     */
    public static Result apply(LibraryService library, Path zipPath) throws IOException {
        JsonObject manifest = readManifest(zipPath);
        List<Path> copied = new ArrayList<>();
        try {
            return library.inTransaction(() -> apply(library, zipPath, manifest, copied));
        } catch (IOException | RuntimeException | Error e) {
            for (Path p : copied) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            }
            throw e;
        }
    }

    private static Result apply(LibraryService library, Path zipPath, JsonObject manifest, List<Path> copied)
            throws IOException {
        TagDao tagDao = library.tags();
        Map<String, Integer> tagPathToId = new HashMap<>();
        int[] tagsCreated = {0};

        for (JsonElement el : manifest.getAsJsonArray("tags")) {
            resolveOrCreateTagPath(tagDao, el.getAsString(), tagPathToId, tagsCreated);
        }
        // Older archives predate tag options. Options only ever switch on, so a local choice isn't undone.
        if (manifest.has("tagOptions")) {
            for (JsonElement el : manifest.getAsJsonArray("tagOptions")) {
                JsonObject o = el.getAsJsonObject();
                int id = resolveOrCreateTagPath(tagDao, o.get("path").getAsString(), tagPathToId, tagsCreated);
                if (o.get("groupOnly").getAsBoolean()) tagDao.setGroupOnly(id, true);
                if (o.get("exclusiveChildren").getAsBoolean()) tagDao.setExclusiveChildren(id, true);
                // Color and description fill in what's unset here, like credit and notes do.
                Tag local = tagDao.get(id).orElseThrow();
                if (o.has("color") && local.color() == null) tagDao.setColor(id, o.get("color").getAsString());
                if (o.has("description") && local.description() == null) tagDao.setDescription(id, o.get("description").getAsString());
            }
        }

        Map<String, Integer> hashToImageId = new HashMap<>();
        int newImages = 0;
        int taggedExisting = 0;

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            for (JsonElement el : manifest.getAsJsonArray("images")) {
                JsonObject img = el.getAsJsonObject();
                String hash = img.get("hash").getAsString();
                String ext = img.get("ext").getAsString();

                List<Integer> tagIds = new ArrayList<>();
                for (JsonElement tp : img.getAsJsonArray("tagPaths")) {
                    tagIds.add(resolveOrCreateTagPath(tagDao, tp.getAsString(), tagPathToId, tagsCreated));
                }
                boolean favorite = img.has("favorite") && img.get("favorite").getAsBoolean();
                // Older archives predate credit/notes.
                String credit = img.has("credit") ? img.get("credit").getAsString() : null;
                String notes = img.has("notes") ? img.get("notes").getAsString() : null;

                ImageRecord existing = library.images().findByHash(hash).orElse(null);
                // The exporting library had lost this original (deleted outside Gesturtle, say), so there's
                // no picture to import. Skipped rather than failing the whole archive over one image.
                boolean hasOriginal = hasOriginal(zip, hash, ext);
                if (existing == null && !hasOriginal) continue;
                int imageId;
                if (existing != null) {
                    imageId = existing.id();
                    if (existing.isDeleted() && (hasOriginal || Files.isRegularFile(library.ref().imagePath(hash, ext)))) {
                        copyOriginalIfMissing(library, zip, hash, ext, copied);
                        library.images().restore(imageId);
                    }
                    library.images().addTags(imageId, tagIds);
                    if (favorite && !existing.favorite()) library.images().setFavorite(imageId, true);
                    // Merge like tags and favorite: fill in what's missing, never overwrite what the user typed here.
                    if ((credit != null && existing.credit() == null) || (notes != null && existing.notes() == null)) {
                        library.images().setDetails(imageId,
                                existing.credit() != null ? existing.credit() : credit,
                                existing.notes() != null ? existing.notes() : notes);
                    }
                    taggedExisting++;
                } else {
                    imageId = importNewImage(library, zip, img, hash, ext, tagIds, favorite, copied);
                    if (credit != null || notes != null) library.images().setDetails(imageId, credit, notes);
                    newImages++;
                }
                hashToImageId.put(hash, imageId);
            }
        }

        Map<String, Integer> playlistIdByOriginalName = new HashMap<>();
        List<String> existingPlaylistNames = new ArrayList<>(library.playlists().listAll().stream().map(Playlist::name).toList());
        for (JsonElement el : manifest.getAsJsonArray("playlists")) {
            JsonObject pl = el.getAsJsonObject();
            String originalName = pl.get("name").getAsString();
            String name = uniqueName(originalName, existingPlaylistNames);
            existingPlaylistNames.add(name);
            int playlistId = library.playlists().create(name);
            playlistIdByOriginalName.put(originalName, playlistId);
            for (JsonElement h : pl.getAsJsonArray("imageHashes")) {
                Integer imageId = hashToImageId.computeIfAbsent(h.getAsString(),
                        hash -> library.images().findByHash(hash).map(ImageRecord::id).orElse(null));
                if (imageId != null) library.playlists().addItem(playlistId, imageId);
            }
            if (pl.has("coverHash")) {
                Integer coverId = hashToImageId.get(pl.get("coverHash").getAsString());
                if (coverId != null) library.playlists().setCover(playlistId, coverId);
            }
        }

        // Older archives predate saved views.
        Map<String, Integer> viewIdByOriginalName = new HashMap<>();
        if (manifest.has("views")) {
            List<String> existingViewNames = new ArrayList<>(library.savedViews().listAll().stream().map(SavedView::name).toList());
            for (JsonElement el : manifest.getAsJsonArray("views")) {
                JsonObject viewJson = el.getAsJsonObject();
                String originalName = viewJson.get("name").getAsString();
                String name = uniqueName(originalName, existingViewNames);
                existingViewNames.add(name);
                Filter filter = decodePortableFilter(viewJson.getAsJsonObject("filter"), tagDao, tagPathToId, tagsCreated);
                viewIdByOriginalName.put(originalName, library.savedViews().create(name, filter));
            }
        }

        List<String> existingPresetNames = new ArrayList<>(library.presets().listAll().stream().map(Preset::name).toList());
        int presetsImported = 0;
        for (JsonElement el : manifest.getAsJsonArray("presets")) {
            JsonObject presetJson = el.getAsJsonObject();
            String name = uniqueName(presetJson.get("name").getAsString(), existingPresetNames);
            existingPresetNames.add(name);

            List<Segment> segments = new ArrayList<>();
            for (JsonElement segEl : presetJson.getAsJsonArray("segments")) {
                segments.add(decodeSegment(segEl.getAsJsonObject(), tagDao, tagPathToId, tagsCreated,
                        playlistIdByOriginalName, viewIdByOriginalName));
            }
            int presetId = library.presets().create(name, new SessionSpec(segments, null));
            if (presetJson.has("pinned") && presetJson.get("pinned").getAsBoolean()) {
                library.presets().setPinned(presetId, true);
            }
            presetsImported++;
        }

        return new Result(newImages, taggedExisting, tagsCreated[0], playlistIdByOriginalName.size(), presetsImported);
    }

    private static boolean hasOriginal(ZipFile zip, String hash, String ext) {
        return zip.getEntry("images/" + hash + "." + ext) != null;
    }

    /** A deleted image's original is in the system trash, so restoring it from an archive copies the archive's back in. */
    private static void copyOriginalIfMissing(LibraryService library, ZipFile zip, String hash, String ext,
                                              List<Path> copied) throws IOException {
        if (!Files.isRegularFile(library.ref().imagePath(hash, ext))) copied.add(copyOriginal(library, zip, hash, ext));
    }

    /**
     * Copies via a temp file that's hashed before it takes its place, so a
     * file whose content doesn't match the hash it's filed under never lands
     * in the library (the library is content-addressed: a wrong file under a
     * hash would be wrong everywhere that hash is synced to).
     */
    private static Path copyOriginal(LibraryService library, ZipFile zip, String hash, String ext) throws IOException {
        Path target = library.ref().imagePath(hash, ext);
        Files.createDirectories(target.getParent());
        ZipEntry entry = zip.getEntry("images/" + hash + "." + ext);
        if (entry == null) {
            throw new IOException("Archive is missing the file for image " + hash + "." + ext);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try {
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!new HashService().hashFile(temp).equals(hash)) {
                throw new IOException("Archive is damaged: the file for image " + hash + "." + ext
                        + " doesn't match its hash");
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    private static int importNewImage(LibraryService library, ZipFile zip, JsonObject img, String hash, String ext,
                                        List<Integer> tagIds, boolean favorite, List<Path> copied) throws IOException {
        // A file already there belongs to no row yet (left by a crash) and is replaced with the checked copy;
        // it isn't this import's to delete on rollback.
        boolean existed = Files.isRegularFile(library.ref().imagePath(hash, ext));
        Path target = copyOriginal(library, zip, hash, ext);
        if (!existed) copied.add(target);
        // Measured here rather than trusted: archives from older versions recorded a phone photo's
        // stored width and height, not the way up it's shown.
        int[] size;
        try {
            size = ImportService.readDimensions(target);
        } catch (IOException e) {
            size = new int[]{img.get("width").getAsInt(), img.get("height").getAsInt()};
        }

        int imageId = library.images().insert(hash, ext,
                img.has("originalName") ? img.get("originalName").getAsString() : null,
                size[0], size[1],
                img.get("byteSize").getAsLong(), img.get("importedAt").getAsLong(), null);
        library.images().addTags(imageId, tagIds);
        if (favorite) library.images().setFavorite(imageId, true);

        if (img.has("viewEvents")) {
            long maxSeenAt = 0;
            int count = 0;
            for (JsonElement ve : img.getAsJsonArray("viewEvents")) {
                JsonObject veo = ve.getAsJsonObject();
                long seenAt = veo.get("seenAt").getAsLong();
                library.images().insertRawViewEvent(imageId, seenAt, veo.get("shownMs").getAsLong());
                maxSeenAt = Math.max(maxSeenAt, seenAt);
                count++;
            }
            if (count > 0) library.images().setSeenStats(imageId, maxSeenAt, count);
        } else {
            Long lastSeenAt = img.has("lastSeenAt") ? img.get("lastSeenAt").getAsLong() : null;
            int seenCount = img.has("seenCount") ? img.get("seenCount").getAsInt() : 0;
            if (lastSeenAt != null || seenCount > 0) library.images().setSeenStats(imageId, lastSeenAt, seenCount);
        }

        try {
            library.thumbnails().ensureThumbnail(library.ref(), target, hash);
        } catch (IOException ignored) {
            // best-effort: a missing thumbnail just regenerates the next time it's viewed
        }
        return imageId;
    }

    private static Segment decodeSegment(JsonObject o, TagDao tagDao, Map<String, Integer> tagPathToId,
                                          int[] tagsCreated, Map<String, Integer> playlistIdByOriginalName,
                                          Map<String, Integer> viewIdByOriginalName) {
        SourceKind sourceKind = SourceKind.valueOf(o.get("sourceKind").getAsString());
        Integer itemCount = o.has("itemCount") ? o.get("itemCount").getAsInt() : null;
        OrderMode orderMode = OrderMode.valueOf(o.get("orderMode").getAsString());
        int secondsPerImage = o.get("secondsPerImage").getAsInt();
        TickBehavior tickBehavior = TickBehavior.valueOf(o.get("tickBehavior").getAsString());
        int breakSeconds = o.get("breakSeconds").getAsInt();

        Segment segment;
        if (sourceKind == SourceKind.FILTER) {
            Filter filter = decodePortableFilter(o.getAsJsonObject("filter"), tagDao, tagPathToId, tagsCreated);
            segment = Segment.filterSegment(filter, itemCount, orderMode, secondsPerImage, tickBehavior, breakSeconds);
        } else if (sourceKind == SourceKind.VIEW) {
            Integer viewId = o.has("viewName") ? viewIdByOriginalName.get(o.get("viewName").getAsString()) : null;
            segment = Segment.viewSegment(viewId, itemCount, orderMode, secondsPerImage, tickBehavior, breakSeconds);
        } else {
            String playlistName = o.has("playlistName") ? o.get("playlistName").getAsString() : null;
            Integer playlistId = playlistName != null ? playlistIdByOriginalName.get(playlistName) : null;
            segment = playlistId != null
                    ? Segment.playlistSegment(playlistId, itemCount, orderMode, secondsPerImage, tickBehavior, breakSeconds)
                    : Segment.filterSegment(new Filter.All(), itemCount, orderMode, secondsPerImage, tickBehavior, breakSeconds);
        }
        if (o.has("rampToSeconds")) segment = segment.withRampToSeconds(o.get("rampToSeconds").getAsInt());
        if (o.has("memoryShowSeconds") && o.has("memoryHideSeconds")) {
            segment = segment.withMemoryMode(o.get("memoryShowSeconds").getAsInt(), o.get("memoryHideSeconds").getAsInt());
        }
        return segment;
    }

    /** Same shape as FilterCodec, but a TagPredicate's path is resolved/created in the destination library. */
    private static Filter decodePortableFilter(JsonObject o, TagDao tagDao, Map<String, Integer> tagPathToId,
                                                 int[] tagsCreated) {
        return FilterCodec.fromJson(o, tag ->
                resolveOrCreateTagPath(tagDao, tag.get("tagPath").getAsString(), tagPathToId, tagsCreated));
    }

    /** Walks a " / "-joined tag path without creating anything; null if any segment is missing. */
    private static Integer resolveExistingTagPath(TagDao tagDao, String path) {
        Integer parentId = null;
        for (String part : path.split(" / ")) {
            Tag match = tagDao.listChildren(parentId).stream().filter(t -> t.name().equals(part)).findFirst().orElse(null);
            if (match == null) return null;
            parentId = match.id();
        }
        return parentId;
    }

    /** Walks a " / "-joined tag path, creating any missing segment, caching every prefix it resolves. */
    private static int resolveOrCreateTagPath(TagDao tagDao, String path, Map<String, Integer> cache, int[] created) {
        Integer cached = cache.get(path);
        if (cached != null) return cached;

        Integer parentId = null;
        StringBuilder builtPath = new StringBuilder();
        for (String part : path.split(" / ")) {
            if (builtPath.length() > 0) builtPath.append(" / ");
            builtPath.append(part);
            String key = builtPath.toString();
            Integer id = cache.get(key);
            if (id == null) {
                final Integer searchParentId = parentId;
                Tag match = tagDao.listChildren(parentId).stream()
                        .filter(t -> t.name().equals(part)).findFirst().orElse(null);
                if (match != null) {
                    id = match.id();
                } else {
                    id = tagDao.create(part, searchParentId);
                    created[0]++;
                }
                cache.put(key, id);
            }
            parentId = id;
        }
        return parentId;
    }

    private static String uniqueName(String base, List<String> existing) {
        if (!existing.contains(base)) return base;
        int n = 2;
        while (existing.contains(base + " (" + n + ")")) n++;
        return base + " (" + n + ")";
    }

    private static JsonObject readManifest(Path zipPath) throws IOException {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null) throw new IOException("Not a Gesturtle archive: missing manifest.json");
            JsonObject manifest;
            try (InputStream in = zip.getInputStream(entry)) {
                manifest = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
            validateImages(manifest);
            return manifest;
        }
    }

    /**
     * hash and ext become a file path in the library, so they're checked
     * before anything is imported: a crafted archive could otherwise write
     * outside images/ (an ext of "/../../x"). Checked up front rather than
     * per image so a bad archive changes nothing instead of half-importing.
     */
    private static void validateImages(JsonObject manifest) throws IOException {
        for (JsonElement el : manifest.getAsJsonArray("images")) {
            JsonObject img = el.getAsJsonObject();
            String hash = img.has("hash") ? img.get("hash").getAsString() : null;
            String ext = img.has("ext") ? img.get("ext").getAsString() : null;
            if (!HashService.isValidHash(hash) || !ImportService.isSupportedExtension(ext)) {
                throw new IOException("Archive is damaged or not from Gesturtle: bad image entry " + hash + "." + ext);
            }
        }
    }
}
