package com.gesturtle.practice;

import com.gesturtle.filter.Filter;
import com.gesturtle.library.LibraryService;
import com.gesturtle.model.OrderMode;
import com.gesturtle.model.Segment;
import com.gesturtle.model.SessionSpec;
import com.gesturtle.model.TickBehavior;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionEngineTest {

    private LibraryService newLibraryWithImages(Path tempDir, int count) {
        LibraryService library = new LibraryService(tempDir.resolve("lib").toString());
        for (int i = 0; i < count; i++) {
            library.images().insert("hash" + i, "png", "img" + i + ".png", 10, 10, 100,
                    System.currentTimeMillis(), null);
        }
        return library;
    }

    private record PhaseEvent(SessionEngine.Phase phase, int secondsRemaining) {
    }

    @Test
    void rampTimerInterpolatesSecondsPerImageAcrossTheSegment(@TempDir Path tempDir) {
        try (LibraryService library = newLibraryWithImages(tempDir, 3)) {
            Segment segment = Segment.filterSegment(new Filter.All(), null, OrderMode.SEQUENTIAL,
                    10, TickBehavior.ADVANCE_IMMEDIATELY, 0).withRampToSeconds(2);
            List<ResolvedSegment> resolved = new SessionBuilder().resolve(SessionSpec.single(segment), library);
            SessionEngine engine = new SessionEngine(library, resolved, null);

            List<PhaseEvent> events = new ArrayList<>();
            engine.setListener(new SessionEngine.Listener() {
                public void onImageChanged(int imageId, Segment segment, int position, int total) {
                }

                public void onPhaseChanged(SessionEngine.Phase phase, int secondsRemaining) {
                    events.add(new PhaseEvent(phase, secondsRemaining));
                }

                public void onSessionFinished() {
                }
            });

            engine.start();
            assertEquals(10, events.get(events.size() - 1).secondsRemaining());
            engine.next();
            assertEquals(6, events.get(events.size() - 1).secondsRemaining());
            engine.next();
            assertEquals(2, events.get(events.size() - 1).secondsRemaining());
        }
    }

    @Test
    void memoryModeCyclesThroughShowHiddenThenRevealedUntilDismissed(@TempDir Path tempDir) {
        try (LibraryService library = newLibraryWithImages(tempDir, 1)) {
            Segment segment = Segment.filterSegment(new Filter.All(), null, OrderMode.SEQUENTIAL,
                    0, TickBehavior.ADVANCE_IMMEDIATELY, 0).withMemoryMode(2, 1);
            List<ResolvedSegment> resolved = new SessionBuilder().resolve(SessionSpec.single(segment), library);
            SessionEngine engine = new SessionEngine(library, resolved, null);

            boolean[] finished = {false};
            engine.setListener(new SessionEngine.Listener() {
                public void onImageChanged(int imageId, Segment segment, int position, int total) {
                }

                public void onPhaseChanged(SessionEngine.Phase phase, int secondsRemaining) {
                }

                public void onSessionFinished() {
                    finished[0] = true;
                }
            });

            engine.start();
            assertEquals(SessionEngine.Phase.SHOWING, engine.phase());

            engine.tick();
            assertEquals(SessionEngine.Phase.SHOWING, engine.phase());
            engine.tick();
            assertEquals(SessionEngine.Phase.MEMORY_HIDDEN, engine.phase());
            engine.tick();
            assertEquals(SessionEngine.Phase.MEMORY_REVEALED, engine.phase());

            engine.advanceClick();
            assertTrue(finished[0], "the only image in the segment finished the session once dismissed");
        }
    }

    private static SessionEngine.Listener recording(List<PhaseEvent> events) {
        return new SessionEngine.Listener() {
            public void onImageChanged(int imageId, Segment segment, int position, int total) {
            }

            public void onPhaseChanged(SessionEngine.Phase phase, int secondsRemaining) {
                events.add(new PhaseEvent(phase, secondsRemaining));
            }

            public void onSessionFinished() {
            }
        };
    }

    private static PhaseEvent last(List<PhaseEvent> events) {
        return events.get(events.size() - 1);
    }

    @Test
    void extendAddsTimeAndRestartStartsOver(@TempDir Path tempDir) {
        try (LibraryService library = newLibraryWithImages(tempDir, 1)) {
            Segment segment = Segment.filterSegment(new Filter.All(), null, OrderMode.SEQUENTIAL,
                    10, TickBehavior.ADVANCE_IMMEDIATELY, 0);
            SessionEngine engine = new SessionEngine(library, new SessionBuilder().resolve(SessionSpec.single(segment), library), null);
            List<PhaseEvent> events = new ArrayList<>();
            engine.setListener(recording(events));
            engine.start();
            engine.tick();
            engine.tick();
            assertEquals(8, last(events).secondsRemaining());
            assertTrue(engine.extend(30));
            assertEquals(38, last(events).secondsRemaining());
            engine.restartCurrent();
            assertEquals(new PhaseEvent(SessionEngine.Phase.SHOWING, 10), last(events));
        }
    }

    @Test
    void extendDoesNothingForAnUntimedImage(@TempDir Path tempDir) {
        try (LibraryService library = newLibraryWithImages(tempDir, 1)) {
            Segment segment = Segment.filterSegment(new Filter.All(), null, OrderMode.SEQUENTIAL,
                    0, TickBehavior.ADVANCE_IMMEDIATELY, 0);
            SessionEngine engine = new SessionEngine(library, new SessionBuilder().resolve(SessionSpec.single(segment), library), null);
            engine.start();
            assertEquals(false, engine.extend(30));
        }
    }

    @Test
    void startCursorResumesPartway(@TempDir Path tempDir) {
        try (LibraryService library = newLibraryWithImages(tempDir, 4)) {
            Segment segment = Segment.filterSegment(new Filter.All(), null, OrderMode.SEQUENTIAL,
                    10, TickBehavior.ADVANCE_IMMEDIATELY, 0);
            List<ResolvedSegment> resolved = new SessionBuilder().resolve(SessionSpec.single(segment), library);
            SessionEngine engine = new SessionEngine(library, resolved, null, 2, Runnable::run);
            engine.start();
            assertEquals(2, engine.positionInSession());
            assertEquals(resolved.get(0).imageIds().get(2), engine.currentImageId());
        }
    }
}
