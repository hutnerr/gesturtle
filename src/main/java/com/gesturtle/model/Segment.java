package com.gesturtle.model;

import com.gesturtle.filter.Filter;

/**
 * One slice of a practice session. itemCount null means
 * unlimited; memoryShowSeconds null means memory-drawing mode is off;
 * rampToSeconds null means the timer is flat across the segment, otherwise
 * secondsPerImage is the starting value and rampToSeconds is the ending
 * value, interpolated linearly across the segment's images (a
 * ramping timer). A VIEW segment reads its saved view's filter when the
 * session starts, so editing the view changes what the segment draws;
 * viewId null there means the view was deleted.
 */
public record Segment(
        SourceKind sourceKind,
        Filter filter,
        Integer playlistId,
        Integer viewId,
        Integer itemCount,
        OrderMode orderMode,
        int secondsPerImage,
        TickBehavior tickBehavior,
        int breakSeconds,
        Integer memoryShowSeconds,
        Integer memoryHideSeconds,
        Integer rampToSeconds
) {
    public static Segment filterSegment(Filter filter, Integer itemCount, OrderMode orderMode,
                                         int secondsPerImage, TickBehavior tickBehavior, int breakSeconds) {
        return new Segment(SourceKind.FILTER, filter, null, null, itemCount, orderMode,
                secondsPerImage, tickBehavior, breakSeconds, null, null, null);
    }

    public static Segment playlistSegment(int playlistId, Integer itemCount, OrderMode orderMode,
                                           int secondsPerImage, TickBehavior tickBehavior, int breakSeconds) {
        return new Segment(SourceKind.PLAYLIST, null, playlistId, null, itemCount, orderMode,
                secondsPerImage, tickBehavior, breakSeconds, null, null, null);
    }

    public static Segment viewSegment(Integer viewId, Integer itemCount, OrderMode orderMode,
                                       int secondsPerImage, TickBehavior tickBehavior, int breakSeconds) {
        return new Segment(SourceKind.VIEW, null, null, viewId, itemCount, orderMode,
                secondsPerImage, tickBehavior, breakSeconds, null, null, null);
    }

    public Segment withMemoryMode(Integer showSeconds, Integer hideSeconds) {
        return new Segment(sourceKind, filter, playlistId, viewId, itemCount, orderMode, secondsPerImage,
                tickBehavior, breakSeconds, showSeconds, hideSeconds, rampToSeconds);
    }

    public Segment withRampToSeconds(Integer rampToSeconds) {
        return new Segment(sourceKind, filter, playlistId, viewId, itemCount, orderMode, secondsPerImage,
                tickBehavior, breakSeconds, memoryShowSeconds, memoryHideSeconds, rampToSeconds);
    }

    public boolean isTimed() {
        return secondsPerImage > 0;
    }

    public boolean isMemoryMode() {
        return memoryShowSeconds != null;
    }

    public boolean isRamped() {
        return rampToSeconds != null && isTimed();
    }
}
