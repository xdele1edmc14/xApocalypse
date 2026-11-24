package com.deleted.xapocalypse;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Owns the periodic update-check lifecycle independently of Bukkit and HTTP details. */
final class UpdateCheckCoordinator {

    static final long CHECK_INTERVAL_TICKS = 6L * 60L * 60L * 20L;

    @FunctionalInterface
    interface Checker {
        CompletableFuture<Optional<String>> check(String currentVersion);
    }

    interface Scheduler {
        Task scheduleRepeating(Runnable runnable, long initialDelayTicks, long periodTicks);

        void runMain(Runnable runnable);
    }

    @FunctionalInterface
    interface Task {
        void cancel();
    }

    private final Checker checker;
    private final Scheduler scheduler;
    private final Consumer<String> publisher;
    private final Consumer<Throwable> failureHandler;
    private final String currentVersion;
    private final AtomicLong generation = new AtomicLong();

    private volatile Task periodicTask;
    private volatile String availableVersion;

    UpdateCheckCoordinator(Checker checker, Scheduler scheduler, Consumer<String> publisher,
                           Consumer<Throwable> failureHandler, String currentVersion) {
        this.checker = checker;
        this.scheduler = scheduler;
        this.publisher = publisher;
        this.failureHandler = failureHandler;
        this.currentVersion = currentVersion;
    }

    void start(boolean enabled) {
        stop();
        if (!enabled) {
            return;
        }

        long activeGeneration = generation.get();
        periodicTask = scheduler.scheduleRepeating(
                () -> runCheck(activeGeneration), 0L, CHECK_INTERVAL_TICKS);
    }

    void stop() {
        generation.incrementAndGet();
        availableVersion = null;
        Task activeTask = periodicTask;
        periodicTask = null;
        if (activeTask != null) {
            activeTask.cancel();
        }
    }

    Optional<String> availableVersion() {
        return Optional.ofNullable(availableVersion);
    }

    private void runCheck(long activeGeneration) {
        CompletableFuture<Optional<String>> request;
        try {
            request = checker.check(currentVersion);
        } catch (RuntimeException exception) {
            handleFailure(activeGeneration, exception);
            return;
        }

        request.whenComplete((result, error) -> {
            if (activeGeneration != generation.get()) {
                return;
            }
            if (error != null) {
                handleFailure(activeGeneration, unwrap(error));
                return;
            }

            try {
                scheduler.runMain(() -> publishOnMainThread(activeGeneration, result));
            } catch (RuntimeException exception) {
                // Bukkit can reject a task if disable wins the race after the generation check.
                handleFailure(activeGeneration, exception);
            }
        });
    }

    private void publishOnMainThread(long activeGeneration, Optional<String> result) {
        if (activeGeneration != generation.get()) {
            return;
        }
        availableVersion = result.orElse(null);
        result.ifPresent(publisher);
    }

    private void handleFailure(long activeGeneration, Throwable error) {
        if (activeGeneration == generation.get()) {
            failureHandler.accept(error);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
    }
}
