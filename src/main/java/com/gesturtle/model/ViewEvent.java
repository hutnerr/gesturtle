package com.gesturtle.model;

/** One practice-session viewing of an image, as exported/replayed by the archive feature. */
public record ViewEvent(long seenAt, long shownMs) {
}
