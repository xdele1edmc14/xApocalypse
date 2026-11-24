package com.deleted.xapocalypse;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckCoordinatorTest {

    @Test
    void reloadCancelsOldTimerAndKeepsExactlyOnePeriodicCheck() {
        RecordingScheduler scheduler = new RecordingScheduler();
        UpdateCheckCoordinator coordinator = coordinator(scheduler,
                version -> CompletableFuture.completedFuture(Optional.empty()));

        coordinator.start(true);
        coordinator.start(true);

        assertEquals(2, scheduler.tasks.size());
        assertTrue(scheduler.tasks.get(0).cancelled);
        assertFalse(scheduler.tasks.get(1).cancelled);
        assertEquals(1, scheduler.activeTaskCount());
        assertEquals(0L, scheduler.tasks.get(1).initialDelayTicks);
        assertEquals(432_000L, scheduler.tasks.get(1).periodTicks);
    }

    @Test
    void disabledChecksScheduleNothingAndClearPreviousResult() {
        RecordingScheduler scheduler = new RecordingScheduler();
        List<String> published = new ArrayList<>();
        UpdateCheckCoordinator coordinator = new UpdateCheckCoordinator(
                version -> CompletableFuture.completedFuture(Optional.of("1.8.0")),
                scheduler, published::add, ignored -> { }, "1.7.2");
        coordinator.start(true);
        scheduler.runActiveTimer();
        scheduler.runMainTasks();
        assertEquals(Optional.of("1.8.0"), coordinator.availableVersion());

        coordinator.start(false);

        assertEquals(Optional.empty(), coordinator.availableVersion());
        assertEquals(0, scheduler.activeTaskCount());
    }

    @Test
    void staleRequestAfterReloadCannotPublishOrOverwriteState() {
        RecordingScheduler scheduler = new RecordingScheduler();
        CompletableFuture<Optional<String>> oldRequest = new CompletableFuture<>();
        CompletableFuture<Optional<String>> newRequest = new CompletableFuture<>();
        List<CompletableFuture<Optional<String>>> requests = new ArrayList<>(List.of(
                oldRequest, newRequest));
        List<String> published = new ArrayList<>();
        UpdateCheckCoordinator coordinator = new UpdateCheckCoordinator(
                version -> requests.removeFirst(), scheduler, published::add,
                ignored -> { }, "1.7.2");

        coordinator.start(true);
        scheduler.runActiveTimer();
        coordinator.start(true);
        scheduler.runActiveTimer();
        oldRequest.complete(Optional.of("9.9.9"));
        newRequest.complete(Optional.of("1.8.0"));
        scheduler.runMainTasks();

        assertEquals(List.of("1.8.0"), published);
        assertEquals(Optional.of("1.8.0"), coordinator.availableVersion());
    }

    @Test
    void stopIgnoresInflightResult() {
        RecordingScheduler scheduler = new RecordingScheduler();
        CompletableFuture<Optional<String>> request = new CompletableFuture<>();
        List<String> published = new ArrayList<>();
        UpdateCheckCoordinator coordinator = new UpdateCheckCoordinator(
                version -> request, scheduler, published::add,
                ignored -> { }, "1.7.2");
        coordinator.start(true);
        scheduler.runActiveTimer();

        coordinator.stop();
        request.complete(Optional.of("1.8.0"));
        scheduler.runMainTasks();

        assertEquals(List.of(), published);
        assertEquals(Optional.empty(), coordinator.availableVersion());
    }

    @Test
    void rejectedMainThreadSubmissionIsHandledWithoutPublishing() {
        RecordingScheduler scheduler = new RecordingScheduler();
        CompletableFuture<Optional<String>> request = new CompletableFuture<>();
        List<String> published = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        UpdateCheckCoordinator coordinator = new UpdateCheckCoordinator(
                version -> request, scheduler, published::add,
                failures::add, "1.7.2");
        coordinator.start(true);
        scheduler.runActiveTimer();
        scheduler.rejectMainSubmissions = true;

        request.complete(Optional.of("1.8.0"));

        assertEquals(List.of(), published);
        assertEquals(1, failures.size());
        assertEquals("plugin disabled", failures.getFirst().getMessage());
    }

    @Test
    void adminNotificationRequiresVersionToggleAndPermission() {
        assertTrue(xApocalypse.shouldNotifyAdmin("1.8.0", true, true));
        assertFalse(xApocalypse.shouldNotifyAdmin(null, true, true));
        assertFalse(xApocalypse.shouldNotifyAdmin("1.8.0", false, true));
        assertFalse(xApocalypse.shouldNotifyAdmin("1.8.0", true, false));
    }

    private UpdateCheckCoordinator coordinator(
            RecordingScheduler scheduler, UpdateCheckCoordinator.Checker checker) {
        return new UpdateCheckCoordinator(checker, scheduler,
                ignored -> { }, ignored -> { }, "1.7.2");
    }

    private static final class RecordingScheduler implements UpdateCheckCoordinator.Scheduler {
        private final List<RecordingTask> tasks = new ArrayList<>();
        private final List<Runnable> mainTasks = new ArrayList<>();
        private boolean rejectMainSubmissions;

        @Override
        public UpdateCheckCoordinator.Task scheduleRepeating(
                Runnable runnable, long initialDelayTicks, long periodTicks) {
            RecordingTask task = new RecordingTask(runnable, initialDelayTicks, periodTicks);
            tasks.add(task);
            return task;
        }

        @Override
        public void runMain(Runnable runnable) {
            if (rejectMainSubmissions) {
                throw new IllegalStateException("plugin disabled");
            }
            mainTasks.add(runnable);
        }

        void runActiveTimer() {
            tasks.stream().filter(task -> !task.cancelled).findFirst().orElseThrow().runnable.run();
        }

        void runMainTasks() {
            List<Runnable> queued = new ArrayList<>(mainTasks);
            mainTasks.clear();
            queued.forEach(Runnable::run);
        }

        int activeTaskCount() {
            return (int) tasks.stream().filter(task -> !task.cancelled).count();
        }
    }

    private static final class RecordingTask implements UpdateCheckCoordinator.Task {
        private final Runnable runnable;
        private final long initialDelayTicks;
        private final long periodTicks;
        private boolean cancelled;

        private RecordingTask(Runnable runnable, long initialDelayTicks, long periodTicks) {
            this.runnable = runnable;
            this.initialDelayTicks = initialDelayTicks;
            this.periodTicks = periodTicks;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
