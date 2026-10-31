package com.gesturtle.practice;

import com.gesturtle.filter.Filter;
import com.gesturtle.model.OrderMode;
import com.gesturtle.model.Segment;
import com.gesturtle.model.SessionSpec;
import com.gesturtle.model.TickBehavior;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionSnapshotTest {
    private static final Segment A = Segment.filterSegment(new Filter.FavoritePredicate(), 3, OrderMode.RANDOM,
            30, TickBehavior.BREAK, 5);
    private static final Segment B = Segment.filterSegment(new Filter.All(), null, OrderMode.SEQUENTIAL,
            60, TickBehavior.ADVANCE_IMMEDIATELY, 0).withRampToSeconds(120);

    private static SessionSnapshot sample() {
        return new SessionSnapshot(new SessionSpec(List.of(A, B), 7),
                List.of(new ResolvedSegment(A, List.of(10, 11, 12)), new ResolvedSegment(B, List.of(20, 21))),
                3, 1234L);
    }

    @Test
    void roundTripsSpecPresetImagesAndPosition() {
        SessionSnapshot decoded = SessionSnapshot.decode(sample().encode());
        assertEquals(7, decoded.spec().presetId());
        assertEquals(List.of(A, B), decoded.spec().segments());
        assertEquals(List.of(10, 11, 12), decoded.resolved().get(0).imageIds());
        assertEquals(List.of(20, 21), decoded.resolved().get(1).imageIds());
        assertEquals(3, decoded.cursor());
        assertEquals(5, decoded.total());
        assertEquals(1234L, decoded.savedAt());
    }

    @Test
    void droppingDeletedImagesKeepsTheSameNextImage() {
        // Position 3 is image 20; with 11 gone it's at position 2.
        SessionSnapshot trimmed = sample().withoutImages(id -> !Set.of(11).contains(id));
        assertEquals(2, trimmed.cursor());
        assertEquals(4, trimmed.total());
        // If the next image itself is gone, the one after it is next.
        SessionSnapshot nextGone = sample().withoutImages(id -> id != 20);
        assertEquals(3, nextGone.cursor());
        assertEquals(21, nextGone.resolved().get(1).imageIds().get(0));
    }

    @Test
    void malformedJsonThrows() {
        assertThrows(RuntimeException.class, () -> SessionSnapshot.decode("{\"spec\": 1}"));
    }
}
