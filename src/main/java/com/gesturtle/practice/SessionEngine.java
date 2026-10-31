package com.gesturtle.practice;

import com.gesturtle.library.LibraryService;
import com.gesturtle.model.Segment;
import com.gesturtle.model.TickBehavior;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Drives a practice session over a flattened queue of (segment, imageId)
 * pairs. Owns the timer/tick-behavior state machine; the UI calls tick()
 * once a second and reacts to listener callbacks.
 *
 * Memory-drawing mode runs its own three-phase cycle per image
 * inside this state machine: SHOWING for memoryShowSeconds, then
 * MEMORY_HIDDEN (blank canvas) for memoryHideSeconds, then MEMORY_REVEALED
 * (image back, held until dismissed). A segment's normal secondsPerImage /
 * tickBehavior are ignored while memoryMode is on, since the show/hide
 * seconds govern timing instead.
 *
 * Per-segment timer ramping interpolates secondsPerImage -> rampToSeconds
 * linearly across a segment's images, keyed off each image's position
 * within its own segment (not the whole session).
 *
 * A session can start partway through (startCursor), which is how an
 * interrupted session resumes from its saved snapshot.
 *
 * Driven from the FX thread, but its database writes (the session row and
 * each view) are handed to dbWork, the app's DB thread, in order, so
 * practice never waits on the database or shares its connection.
 */
public class SessionEngine {

    public enum Phase { SHOWING, MEMORY_HIDDEN, MEMORY_REVEALED, BREAK, WAITING_FOR_CLICK, FINISHED }

    public interface Listener {
        void onImageChanged(int imageId, Segment segment, int positionInSession, int totalInSession);

        void onPhaseChanged(Phase phase, int secondsRemaining);

        void onSessionFinished();
    }

    private record FlatItem(int imageId, Segment segment, int indexInSegment, int segmentSize) {
    }

    private final LibraryService library;
    private final List<ResolvedSegment> resolved;
    private final List<FlatItem> queue = new ArrayList<>();
    private final Integer presetId;
    private final int startCursor;
    private final Executor dbWork;
    private Listener listener = NO_OP;

    private int cursor = -1;
    private Phase phase = Phase.FINISHED;
    private int remainingSeconds;
    private boolean paused;
    /** Set on the DB thread by the first task queued; only read by later tasks on that thread. */
    private volatile Integer sessionId;
    private long currentImageStartedAt;
    /** Every image shown at least once, in first-shown order, for the end-of-session review. */
    private final Set<Integer> shownImageIds = new LinkedHashSet<>();

    private static final Listener NO_OP = new Listener() {
        public void onImageChanged(int imageId, Segment segment, int position, int total) {
        }

        public void onPhaseChanged(Phase phase, int secondsRemaining) {
        }

        public void onSessionFinished() {
        }
    };

    public SessionEngine(LibraryService library, List<ResolvedSegment> resolved, Integer presetId) {
        this(library, resolved, presetId, 0, Runnable::run);
    }

    /**
     * Starts at queue position startCursor (clamped), e.g. to resume an
     * interrupted session. dbWork runs the database writes, in order.
     */
    public SessionEngine(LibraryService library, List<ResolvedSegment> resolved, Integer presetId, int startCursor,
                         Executor dbWork) {
        this.library = library;
        this.dbWork = dbWork;
        this.resolved = List.copyOf(resolved);
        this.presetId = presetId;
        this.startCursor = Math.max(0, startCursor);
        for (ResolvedSegment rs : resolved) {
            int size = rs.imageIds().size();
            for (int i = 0; i < size; i++) {
                queue.add(new FlatItem(rs.imageIds().get(i), rs.segment(), i, size));
            }
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? NO_OP : listener;
    }

    public int totalCount() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** The segments as resolved for this session, for saving a resumable snapshot. */
    public List<ResolvedSegment> resolved() {
        return resolved;
    }

    public void start() {
        long startedAt = System.currentTimeMillis();
        dbWork.execute(() -> sessionId = library.views().startSession(presetId, startedAt));
        cursor = Math.min(startCursor, queue.size()) - 1;
        if (!advanceCursor(1)) {
            finish();
            return;
        }
        beginShowingCurrent();
    }

    public void tick() {
        if (paused || phase == Phase.FINISHED) return;
        switch (phase) {
            case SHOWING -> {
                Segment seg = current().segment();
                if (seg.isMemoryMode()) {
                    remainingSeconds--;
                    if (remainingSeconds <= 0) {
                        enterMemoryHidden(seg);
                    } else {
                        listener.onPhaseChanged(phase, remainingSeconds);
                    }
                    return;
                }
                if (!seg.isTimed()) return;
                remainingSeconds--;
                if (remainingSeconds <= 0) {
                    handleTimerExpired(seg);
                } else {
                    listener.onPhaseChanged(phase, remainingSeconds);
                }
            }
            case MEMORY_HIDDEN -> {
                remainingSeconds--;
                if (remainingSeconds <= 0) {
                    phase = Phase.MEMORY_REVEALED;
                    listener.onPhaseChanged(phase, 0);
                } else {
                    listener.onPhaseChanged(phase, remainingSeconds);
                }
            }
            case BREAK -> {
                remainingSeconds--;
                if (remainingSeconds <= 0) {
                    goTo(cursor + 1);
                } else {
                    listener.onPhaseChanged(phase, remainingSeconds);
                }
            }
            default -> {
            }
        }
    }

    public void advanceClick() {
        if (phase == Phase.WAITING_FOR_CLICK || phase == Phase.MEMORY_REVEALED) {
            goTo(cursor + 1);
        }
    }

    public void next() {
        goTo(cursor + 1);
    }

    public void previous() {
        if (cursor > 0) {
            goTo(cursor - 1);
        }
    }

    /**
     * Adds time to whatever is counting down for this image (its pose
     * timer, memory show/hide, or a break). Returns false when nothing is
     * counting (untimed, waiting for a click).
     */
    public boolean extend(int seconds) {
        boolean counting = switch (phase) {
            case SHOWING -> current() != null && (current().segment().isTimed() || current().segment().isMemoryMode());
            case MEMORY_HIDDEN, BREAK -> true;
            default -> false;
        };
        if (!counting) return false;
        remainingSeconds += seconds;
        listener.onPhaseChanged(phase, remainingSeconds);
        return true;
    }

    /**
     * Starts the current image's timer over from the top (memory mode goes
     * back to its show phase). Its viewing
     * time keeps counting from when it first appeared, so a restart doesn't
     * log a second, partial view.
     */
    public void restartCurrent() {
        FlatItem item = current();
        if (item == null || phase == Phase.FINISHED) return;
        paused = false;
        remainingSeconds = initialSeconds(item);
        phase = Phase.SHOWING;
        listener.onPhaseChanged(phase, remainingSeconds);
    }

    public void togglePause() {
        if (phase == Phase.FINISHED) return;
        paused = !paused;
        listener.onPhaseChanged(phase, remainingSeconds);
    }

    public boolean isPaused() {
        return paused;
    }

    public Phase phase() {
        return phase;
    }

    public void end() {
        recordCurrentView();
        endSession();
        phase = Phase.FINISHED;
    }

    public List<Integer> shownImageIds() {
        return List.copyOf(shownImageIds);
    }

    public Integer currentImageId() {
        FlatItem item = current();
        return item == null ? null : item.imageId();
    }

    public Segment currentSegment() {
        FlatItem item = current();
        return item == null ? null : item.segment();
    }

    public int positionInSession() {
        return cursor;
    }

    private FlatItem current() {
        return (cursor >= 0 && cursor < queue.size()) ? queue.get(cursor) : null;
    }

    private void goTo(int newCursor) {
        if (newCursor < 0) return;
        recordCurrentView();
        if (newCursor >= queue.size()) {
            finish();
            return;
        }
        cursor = newCursor;
        beginShowingCurrent();
    }

    private boolean advanceCursor(int delta) {
        int target = cursor + delta;
        if (target < 0 || target >= queue.size()) return false;
        cursor = target;
        return true;
    }

    /** Announces the new image (so it can load), then starts its timer. */
    private void beginShowingCurrent() {
        FlatItem item = current();
        paused = false;
        listener.onImageChanged(item.imageId(), item.segment(), cursor, queue.size());
        startShowing();
    }

    private void startShowing() {
        FlatItem item = current();
        currentImageStartedAt = System.currentTimeMillis();
        shownImageIds.add(item.imageId());
        remainingSeconds = initialSeconds(item);
        phase = Phase.SHOWING;
        listener.onPhaseChanged(phase, remainingSeconds);
    }

    private int initialSeconds(FlatItem item) {
        Segment seg = item.segment();
        return seg.isMemoryMode() ? seg.memoryShowSeconds() : effectiveSecondsForItem(item);
    }

    private void enterMemoryHidden(Segment seg) {
        phase = Phase.MEMORY_HIDDEN;
        remainingSeconds = seg.memoryHideSeconds();
        listener.onPhaseChanged(phase, remainingSeconds);
    }

    private void handleTimerExpired(Segment seg) {
        switch (seg.tickBehavior()) {
            case ADVANCE_IMMEDIATELY -> goTo(cursor + 1);
            case BREAK -> {
                phase = Phase.BREAK;
                remainingSeconds = seg.breakSeconds();
                listener.onPhaseChanged(phase, remainingSeconds);
            }
            case WAIT_FOR_CLICK -> {
                phase = Phase.WAITING_FOR_CLICK;
                listener.onPhaseChanged(phase, 0);
            }
        }
    }

    /** Interpolates secondsPerImage -> rampToSeconds across this item's position within its own segment. */
    private int effectiveSecondsForItem(FlatItem item) {
        Segment seg = item.segment();
        if (!seg.isRamped() || item.segmentSize() <= 1) return seg.secondsPerImage();
        double t = (double) item.indexInSegment() / (item.segmentSize() - 1);
        return Math.round(seg.secondsPerImage() + (float) (t * (seg.rampToSeconds() - seg.secondsPerImage())));
    }

    private void recordCurrentView() {
        FlatItem item = current();
        if (item == null || phase == Phase.FINISHED) return;
        long now = System.currentTimeMillis();
        long shownMs = now - currentImageStartedAt;
        int imageId = item.imageId();
        dbWork.execute(() -> library.images().recordView(imageId, sessionId, now, shownMs));
    }

    private void endSession() {
        long now = System.currentTimeMillis();
        int shown = shownImageIds.size();
        dbWork.execute(() -> {
            if (sessionId != null) library.views().endSession(sessionId, now, shown);
        });
    }

    private void finish() {
        endSession();
        phase = Phase.FINISHED;
        listener.onSessionFinished();
    }
}
