package com.gesturtle.practice;

import com.gesturtle.db.Database;
import com.gesturtle.db.dao.ImageDao;
import com.gesturtle.filter.Filter;
import com.gesturtle.library.LibraryService;
import com.gesturtle.model.OrderMode;
import com.gesturtle.model.Segment;
import com.gesturtle.model.SessionSpec;
import com.gesturtle.model.TickBehavior;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionBuilderTest {

    @Test
    void segmentShrinksWhenFilterMatchesFewerImagesThanRequestedCount(@TempDir Path tempDir) {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            ImageDao imageDao = library.images();
            for (int i = 0; i < 3; i++) {
                imageDao.insert("hash" + i, "png", "img" + i + ".png", 10, 10, 100,
                        System.currentTimeMillis(), null);
            }

            Segment segment = Segment.filterSegment(new Filter.All(), 5, OrderMode.SEQUENTIAL,
                    0, TickBehavior.ADVANCE_IMMEDIATELY, 0);
            List<ResolvedSegment> resolved = new SessionBuilder().resolve(SessionSpec.single(segment), library);

            assertEquals(1, resolved.size());
            assertEquals(3, resolved.get(0).imageIds().size());
        }
    }

    @Test
    void viewSegmentDrawsFromTheViewsCurrentFilterAndNothingOnceDeleted(@TempDir Path tempDir) {
        try (LibraryService library = new LibraryService(tempDir.resolve("lib").toString())) {
            int a = library.images().insert("hashA", "png", "a.png", 10, 10, 100, 1, null);
            library.images().insert("hashB", "png", "b.png", 10, 10, 100, 2, null);
            library.images().setFavorite(a, true);
            int viewId = library.savedViews().create("Faves", new Filter.FavoritePredicate());

            Segment segment = Segment.viewSegment(viewId, null, OrderMode.SEQUENTIAL,
                    0, TickBehavior.ADVANCE_IMMEDIATELY, 0);
            int presetId = library.presets().create("From view", SessionSpec.single(segment));
            SessionSpec loaded = library.presets().loadSpec(presetId);
            assertEquals(viewId, loaded.segments().get(0).viewId());
            assertEquals(List.of(a), new SessionBuilder().resolve(loaded, library).get(0).imageIds());

            library.savedViews().updateFilter(viewId, new Filter.All());
            assertEquals(2, new SessionBuilder().resolve(loaded, library).get(0).imageIds().size());

            library.savedViews().delete(viewId);
            SessionSpec afterDelete = library.presets().loadSpec(presetId);
            assertEquals(null, afterDelete.segments().get(0).viewId());
            assertEquals(List.of(), new SessionBuilder().resolve(afterDelete, library).get(0).imageIds());
        }
    }
}
