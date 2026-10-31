package com.gesturtle.ui.common;

import com.gesturtle.AppLog;
import javafx.application.Platform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Central place for off-FX-thread work: one
 * single-threaded executor for DB access (SQLite connections aren't safe to
 * share across threads) and a small pool for file I/O / hashing / decoding.
 * Results are always marshalled back to the FX thread via Platform.runLater.
 */
public final class AsyncTask {
    private static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gesturtle-db");
        t.setDaemon(true);
        return t;
    });

    private static final ExecutorService IO_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "gesturtle-io");
        t.setDaemon(true);
        return t;
    });

    /** Runs on the DB thread after every piece of DB work, e.g. to save what it changed to meta/. */
    private static volatile Runnable afterDbWork = () -> {
    };

    private AsyncTask() {
    }

    public static void setAfterDbWork(Runnable hook) {
        afterDbWork = hook;
    }

    public static <T> void runDb(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        run(DB_EXECUTOR, () -> {
            try {
                return work.get();
            } finally {
                try {
                    afterDbWork.run();
                } catch (RuntimeException e) {
                    // The change itself is in the database; meta/ catches up on the next try.
                    AppLog.logger().log(Level.WARNING, "Could not save to meta/", e);
                }
            }
        }, onSuccess, onError);
    }

    public static void runDb(Runnable work, Runnable onSuccess, Consumer<Throwable> onError) {
        runDb(() -> {
            work.run();
            return null;
        }, ignored -> onSuccess.run(), onError);
    }

    /** DB work with nothing to do afterwards; a failure is only logged. Also usable as an Executor (AsyncTask::runDb). */
    public static void runDb(Runnable work) {
        runDb(work, () -> {
        }, err -> {
        });
    }

    public static <T> void runIo(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        run(IO_EXECUTOR, work, onSuccess, onError);
    }

    private static <T> void run(ExecutorService executor, Supplier<T> work, Consumer<T> onSuccess,
                                 Consumer<Throwable> onError) {
        executor.submit(() -> {
            try {
                T result = work.get();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Throwable t) {
                // onError usually shows a short message; the log keeps the stack trace behind it.
                AppLog.logger().log(Level.WARNING, "Background task failed", t);
                Platform.runLater(() -> onError.accept(t));
            }
        });
    }

    /**
     * Runs lastDbWork after whatever DB work is already queued (e.g. closing
     * the library, which saves the last changes to meta/), waits a few
     * seconds for it, then stops both threads.
     */
    public static void shutdown(Runnable lastDbWork) {
        DB_EXECUTOR.submit(lastDbWork);
        DB_EXECUTOR.shutdown();
        try {
            DB_EXECUTOR.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        shutdown();
    }

    public static void shutdown() {
        DB_EXECUTOR.shutdownNow();
        IO_EXECUTOR.shutdownNow();
    }
}
