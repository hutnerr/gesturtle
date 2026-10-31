package com.gesturtle.practice;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gesturtle.model.Segment;
import com.gesturtle.model.SessionSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a running session is, saved after every image so a session cut short
 * (the app closed, the machine slept for good) can pick up where it left
 * off: the spec, the exact images each segment drew, and the position in
 * the queue. Stored as JSON in the library's settings; finishing or exiting
 * a session clears it.
 */
public record SessionSnapshot(SessionSpec spec, List<ResolvedSegment> resolved, int cursor, long savedAt) {
    public static final String SETTING_KEY = "interrupted_session";
    private static final Gson GSON = new Gson();

    public int total() {
        return resolved.stream().mapToInt(r -> r.imageIds().size()).sum();
    }

    public String encode() {
        JsonObject root = new JsonObject();
        root.addProperty("spec", SessionSpecCodec.encode(spec));
        if (spec.presetId() != null) root.addProperty("presetId", spec.presetId());
        JsonArray lists = new JsonArray();
        for (ResolvedSegment r : resolved) {
            JsonArray ids = new JsonArray();
            r.imageIds().forEach(ids::add);
            lists.add(ids);
        }
        root.add("imageIds", lists);
        root.addProperty("cursor", cursor);
        root.addProperty("savedAt", savedAt);
        return GSON.toJson(root);
    }

    /** Throws on anything malformed; callers treat that as "nothing to resume". */
    public static SessionSnapshot decode(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        SessionSpec decoded = SessionSpecCodec.decode(root.get("spec").getAsString());
        Integer presetId = root.has("presetId") ? root.get("presetId").getAsInt() : null;
        SessionSpec spec = new SessionSpec(decoded.segments(), presetId);
        JsonArray lists = root.getAsJsonArray("imageIds");
        if (lists.size() != spec.segments().size()) throw new IllegalArgumentException("Segment count mismatch");
        List<ResolvedSegment> resolved = new ArrayList<>();
        for (int i = 0; i < lists.size(); i++) {
            List<Integer> ids = new ArrayList<>();
            lists.get(i).getAsJsonArray().forEach(el -> ids.add(el.getAsInt()));
            Segment segment = spec.segments().get(i);
            resolved.add(new ResolvedSegment(segment, ids));
        }
        return new SessionSnapshot(spec, resolved, root.get("cursor").getAsInt(), root.get("savedAt").getAsLong());
    }

    /**
     * Drops images that were deleted or recycled since the snapshot, keeping
     * the cursor on the same next image (or the one after it, if it went).
     */
    public SessionSnapshot withoutImages(java.util.function.IntPredicate stillThere) {
        List<ResolvedSegment> kept = new ArrayList<>();
        int newCursor = 0, position = 0;
        for (ResolvedSegment r : resolved) {
            List<Integer> ids = new ArrayList<>();
            for (int id : r.imageIds()) {
                if (stillThere.test(id)) {
                    ids.add(id);
                    if (position < cursor) newCursor++;
                }
                position++;
            }
            kept.add(new ResolvedSegment(r.segment(), ids));
        }
        return new SessionSnapshot(spec, kept, newCursor, savedAt);
    }
}
