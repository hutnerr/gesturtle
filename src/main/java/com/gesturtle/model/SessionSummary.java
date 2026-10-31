package com.gesturtle.model;

/**
 * One finished-or-abandoned practice session. presetName
 * is null for sessions not started from a preset (or whose preset was deleted).
 */
public record SessionSummary(int id, String presetName, long startedAt, long endedAt, int imagesShown) {
    public long durationMillis() {
        return Math.max(0, endedAt - startedAt);
    }
}
