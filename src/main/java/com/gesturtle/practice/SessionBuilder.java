package com.gesturtle.practice;

import com.gesturtle.filter.Filter;
import com.gesturtle.library.LibraryService;
import com.gesturtle.model.ImageRecord;
import com.gesturtle.model.OrderMode;
import com.gesturtle.model.SavedView;
import com.gesturtle.model.Segment;
import com.gesturtle.model.SessionSpec;
import com.gesturtle.model.SourceKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a SessionSpec's segments into concrete, ordered image-id lists.
 * If a segment's count exceeds what its source can supply, the segment is
 * shrunk rather than padded by repeating images.
 */
public class SessionBuilder {

    public List<ResolvedSegment> resolve(SessionSpec spec, LibraryService library) {
        List<ResolvedSegment> resolved = new ArrayList<>();
        for (Segment segment : spec.segments()) {
            List<Integer> ids;
            if (segment.sourceKind() == SourceKind.PLAYLIST) {
                ids = library.playlists().listImageIds(segment.playlistId(), segment.orderMode() == OrderMode.RANDOM);
            } else {
                Filter filter = filterOf(segment, library);
                ids = filter == null ? List.of()
                        : library.images().query(filter, segment.orderMode(), segment.itemCount())
                                .stream().map(ImageRecord::id).toList();
            }
            if (segment.itemCount() != null && ids.size() > segment.itemCount()) {
                ids = ids.subList(0, segment.itemCount());
            }
            resolved.add(new ResolvedSegment(segment, ids));
        }
        return resolved;
    }

    /** The filter a FILTER or VIEW segment draws from; null for a VIEW whose saved view was deleted. */
    public static Filter filterOf(Segment segment, LibraryService library) {
        if (segment.sourceKind() == SourceKind.FILTER) return segment.filter();
        if (segment.viewId() == null) return null;
        return library.savedViews().get(segment.viewId()).map(SavedView::filter).orElse(null);
    }
}
