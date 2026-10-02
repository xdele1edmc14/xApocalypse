package com.deleted.xapocalypse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Serializes ordered YAML snapshots to a temporary file and atomically replaces the target. */
final class AtomicYamlWriter implements AutoCloseable {

    private static final long CLOSE_TIMEOUT_SECONDS = 10L;

    private final Path target;
    private final Consumer<IOException> errorHandler;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    AtomicYamlWriter(Path target, Consumer<IOException> errorHandler) {
        this.target = Objects.requireNonNull(target, "target").toAbsolutePath();
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        String threadName = "xapocalypse-yaml-" + this.target.getFileName();
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    void write(String yaml) {
        Objects.requireNonNull(yaml, "yaml");
        if (closed.get()) {
            errorHandler.accept(new IOException("YAML writer is already closed for " + target));
            return;
        }

        try {
            executor.execute(() -> writeAtomically(yaml));
        } catch (RejectedExecutionException exception) {
            if (!closed.get()) {
                errorHandler.accept(new IOException("Could not queue YAML write for " + target, exception));
            }
        }
    }

    private void writeAtomically(String yaml) {
        Path temporary = null;
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            Files.writeString(temporary, yaml, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException exception) {
            errorHandler.accept(exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    errorHandler.accept(cleanupFailure);
                }
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                errorHandler.accept(new IOException(
                        "Timed out flushing YAML writes for " + target));
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            errorHandler.accept(new IOException(
                    "Interrupted while flushing YAML writes for " + target, exception));
        }
    }
}
