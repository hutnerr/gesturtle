package com.gesturtle.ui.common;

import javafx.application.Platform;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The app's sound effects, from the "Minimal" theme of "UI SFX Vol.1 - Lite"
 * (clean, short, dry sine and triangle tones) as WAVs, played
 * through javax.sound so there's no media dependency. Every event uses one of
 * five sounds: a short tap for small actions, a rising confirm for things
 * appearing (and on), a falling cancel for things going away (and off), an
 * error, and a success chime for a finished session. Two groups, each
 * switchable in Settings: practice cues (pose ticks, new image, breaks) and
 * interface feedback (clicks, toggles, dialogs, favorites, deletes).
 *
 * Each file is decoded once, mixed down to mono, and cut to its audible part.
 * The pack is gain-staged by role (a tap sits under an error), so every file
 * gets the same boost rather than its own level. Plays are mixed in
 * software into a single output line on one daemon thread: overlapping plays
 * just add up, so nothing is ever restarted. (javax.sound Clips on Windows
 * went silent when restarted while running, and replayed stale buffer data
 * after short sounds, so some sounds played twice.) The line stops after a
 * moment of silence, so an idle app holds no audio stream open. Nothing here
 * can stall the UI; with no sound device, it's silent.
 */
public final class Sounds {
    public enum Group { PRACTICE, UI }

    private static final String TAP = "minimal-click";
    private static final String OPEN_UP = "minimal-confirm";
    private static final String CLOSE_DOWN = "minimal-cancel";
    private static final String THUD = "minimal-error";
    private static final String FANFARE = "minimal-success";

    public enum Sound {
        /** The last seconds of a pose. */
        TICK(TAP, Group.PRACTICE),
        /** A new image is up. */
        IMAGE(OPEN_UP, Group.PRACTICE),
        /** A new image the user skipped to (previous/next): the image cue at interface level, like the buttons around it. */
        SKIP(OPEN_UP, Group.UI),
        /** Memory drawing's image is hidden: draw. */
        GO(OPEN_UP, Group.PRACTICE),
        /** A break between images starts. */
        BREAK(CLOSE_DOWN, Group.PRACTICE),
        /** The session is over. */
        FINISH(FANFARE, Group.PRACTICE),

        CLICK(TAP, Group.UI),
        NAV(TAP, Group.UI),
        TOGGLE_ON(OPEN_UP, Group.UI),
        TOGGLE_OFF(CLOSE_DOWN, Group.UI),
        /** Picking an option: a segment, a choice box, a sort. */
        SWITCH(TAP, Group.UI),
        /** Something opens over the page: a dialog, the image viewer, a panel. */
        OPEN(OPEN_UP, Group.UI),
        CLOSE(CLOSE_DOWN, Group.UI),
        CONFIRM(OPEN_UP, Group.UI),
        ERROR(THUD, Group.UI),
        /** A dialog asking for a decision. */
        QUESTION(OPEN_UP, Group.UI),
        /** Files arrive: dropped, pasted, or staged. */
        DROP(TAP, Group.UI),
        DELETE(CLOSE_DOWN, Group.UI),
        FAVORITE(OPEN_UP, Group.UI),
        UNFAVORITE(CLOSE_DOWN, Group.UI),
        /** A keyboard shortcut did something; deliberately faint. */
        KEY(TAP, Group.UI);

        final String file;
        final Group group;

        Sound(String file, Group group) {
            this.file = file;
            this.group = group;
        }
    }

    /** Interface sounds sit under practice cues, which have to be heard across the room. */
    private static final double UI_LEVEL = 0.55;

    /** Everything is decoded to this, and the line plays it. */
    private static final AudioFormat FORMAT = new AudioFormat(44100f, 16, 1, true, false);
    /** Frames mixed per write (~6 ms), and the line's buffer (~46 ms): the delay between a click and its sound. */
    private static final int CHUNK_FRAMES = 256;
    private static final int LINE_FRAMES = 2048;
    /** Silence played before the line stops; long enough that quick bursts of clicks don't stop and restart it. */
    private static final double IDLE_SECONDS = 0.5;
    /** Below this, a sample (of full scale) counts as silence when trimming a file's tail. */
    private static final double SILENCE = 0.003;
    /** Kept after the last audible sample, so the release isn't clipped. */
    private static final double TAIL_SECONDS = 0.03;
    /**
     * Applied to every file alike (about +7 dB): the pack peaks at -7.5 to -18.7 dBFS, which is quiet at app
     * volumes. Its loudest file (success) stays just under full scale at 100% volume.
     */
    private static final float BOOST = 2.3f;

    /** A play request; gain 0 only loads the file (preload). */
    private record Request(String file, float gain) {
    }

    private static final class Voice {
        final float[] samples;
        final float gain;
        int position;

        Voice(float[] samples, float gain) {
            this.samples = samples;
            this.gain = gain;
        }
    }

    private static final BlockingQueue<Request> REQUESTS = new LinkedBlockingQueue<>();
    /** Decoded files, by name; only touched on the mixer thread. */
    private static final Map<String, float[]> SAMPLES = new HashMap<>();
    private static volatile boolean practiceOn = true;
    private static volatile boolean uiOn = true;
    private static volatile double volume = 0.7;
    /** Counts every play, so a deferred control sound can tell whether a more specific one came after it. */
    private static final AtomicInteger PLAYS = new AtomicInteger();
    private static volatile long lastPlayNanos;
    /** A generic sound this soon after a specific one belongs to the same press (Esc closing the viewer). */
    private static final long SAME_PRESS_NANOS = 50_000_000;

    static {
        Thread mixer = new Thread(Sounds::mixLoop, "gesturtle-audio");
        mixer.setDaemon(true);
        mixer.start();
    }

    private Sounds() {
    }

    /** volume is 0 to 1. */
    public static void configure(boolean practice, boolean ui, double newVolume) {
        practiceOn = practice;
        uiOn = ui;
        volume = Math.max(0, Math.min(1, newVolume));
    }

    public static void play(Sound sound) {
        counted();
        emit(sound);
    }

    /** Plays even when its group is off; for the Settings "Test" buttons. */
    public static void playAnyway(Sound sound) {
        counted();
        enqueue(sound);
    }

    private static void counted() {
        PLAYS.incrementAndGet();
        lastPlayNanos = System.nanoTime();
    }

    /**
     * A generic sound (a control's click, toggle, or switch; a shortcut's key tap), dropped if a specific
     * sound played just before it or plays within the next pulse: the action's own sound (a favorite, a
     * delete, a dialog appearing, the viewer closing) speaks for the press. Controls call this as they fire,
     * before their action runs, so an action that opens a dialog with showAndWait can't hold its button's
     * click back until the dialog closes. Without this, those pairs played together (twice as loud when they
     * share a file) or a beat apart. Call on the FX thread.
     */
    public static void playUnlessSuperseded(Sound sound) {
        if (System.nanoTime() - lastPlayNanos < SAME_PRESS_NANOS) return;
        int seen = PLAYS.get();
        Platform.runLater(() -> {
            if (PLAYS.get() == seen) emit(sound);
        });
    }

    private static void emit(Sound sound) {
        boolean on = sound.group == Group.PRACTICE ? practiceOn : uiOn;
        if (on && volume > 0) enqueue(sound);
    }

    private static void enqueue(Sound sound) {
        double v = sound.group == Group.UI ? volume * UI_LEVEL : volume;
        if (v > 0) REQUESTS.offer(new Request(sound.file, (float) v));
    }

    /** Decodes every sound and opens the line in the background, so the first click of a session isn't late. */
    public static void preload() {
        for (Sound sound : Sound.values()) REQUESTS.offer(new Request(sound.file, 0));
    }

    private static void mixLoop() {
        List<Voice> playing = new ArrayList<>();
        float[] mix = new float[CHUNK_FRAMES];
        byte[] out = new byte[CHUNK_FRAMES * FORMAT.getFrameSize()];
        int idleChunks = (int) (IDLE_SECONDS * FORMAT.getFrameRate() / CHUNK_FRAMES);
        SourceDataLine line = null;
        boolean running = false;
        int silentChunks = 0;
        while (true) {
            try {
                // Stopped and nothing to play: sleep until a request arrives.
                if (!running && playing.isEmpty()) start(playing, REQUESTS.take());
                for (Request r; (r = REQUESTS.poll()) != null; ) start(playing, r);
                if (line == null) line = openLine();
                if (line == null) {
                    playing.clear();
                    continue;
                }
                if (!running) {
                    line.start();
                    running = true;
                }
                mix(playing, mix, out);
                line.write(out, 0, out.length);
                silentChunks = playing.isEmpty() ? silentChunks + 1 : 0;
                if (silentChunks >= idleChunks) {
                    line.drain();
                    line.stop();
                    running = false;
                    silentChunks = 0;
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // The device went away: drop what was playing and reopen on the next sound.
                if (line != null) line.close();
                line = null;
                running = false;
                playing.clear();
            }
        }
    }

    private static void start(List<Voice> playing, Request request) {
        float[] samples = SAMPLES.computeIfAbsent(request.file(), Sounds::load);
        if (samples != null && request.gain() > 0) playing.add(new Voice(samples, request.gain()));
    }

    /** Sums the next chunk of every playing voice into out (16-bit little-endian), dropping voices that finish. */
    private static void mix(List<Voice> playing, float[] mix, byte[] out) {
        Arrays.fill(mix, 0);
        for (Iterator<Voice> it = playing.iterator(); it.hasNext(); ) {
            Voice voice = it.next();
            int n = Math.min(mix.length, voice.samples.length - voice.position);
            for (int i = 0; i < n; i++) mix[i] += voice.samples[voice.position + i] * voice.gain;
            voice.position += n;
            if (voice.position >= voice.samples.length) it.remove();
        }
        for (int i = 0; i < mix.length; i++) {
            int sample = Math.round(Math.max(-1, Math.min(1, mix[i])) * Short.MAX_VALUE);
            out[2 * i] = (byte) sample;
            out[2 * i + 1] = (byte) (sample >> 8);
        }
    }

    private static SourceDataLine openLine() {
        try {
            SourceDataLine line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, LINE_FRAMES * FORMAT.getFrameSize());
            return line;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A file's samples as mono -1..1 floats at FORMAT's rate, trimmed and boosted; null if it's missing or
     * unreadable. javax.sound converts sample size and byte order but not channel count, so the decode keeps
     * the file's channels and they're averaged here.
     */
    private static float[] load(String file) {
        try (InputStream raw = Sounds.class.getResourceAsStream("/sounds/" + file + ".wav")) {
            if (raw == null) return null;
            try (AudioInputStream source = AudioSystem.getAudioInputStream(new BufferedInputStream(raw))) {
                int channels = source.getFormat().getChannels();
                AudioFormat decoded = new AudioFormat(FORMAT.getSampleRate(), 16, channels, true, false);
                try (AudioInputStream in = source.getFormat().matches(decoded)
                        ? source : AudioSystem.getAudioInputStream(decoded, source)) {
                    byte[] data = in.readAllBytes();
                    float[] samples = new float[data.length / (2 * channels)];
                    int last = 0;
                    for (int i = 0; i < samples.length; i++) {
                        float sum = 0;
                        for (int c = 0; c < channels; c++) {
                            int at = 2 * (i * channels + c);
                            sum += (short) ((data[at + 1] << 8) | (data[at] & 0xff));
                        }
                        samples[i] = sum / channels / Short.MAX_VALUE * BOOST;
                        if (Math.abs(samples[i]) > SILENCE) last = i + 1;
                    }
                    return Arrays.copyOf(samples,
                            Math.min(samples.length, last + (int) (TAIL_SECONDS * FORMAT.getFrameRate())));
                }
            }
        } catch (Exception e) {
            return null;
        }
    }
}
