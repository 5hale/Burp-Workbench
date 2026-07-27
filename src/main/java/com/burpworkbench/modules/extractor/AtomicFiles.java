package com.burpworkbench.modules.extractor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class AtomicFiles {
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private AtomicFiles() {
    }

    static Path createTempSibling(Path target) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("target has no parent directory: " + target);
        }
        Files.createDirectories(parent);
        String fileName = target.getFileName() == null ? "extract" : target.getFileName().toString();
        return Files.createTempFile(parent, "." + fileName + ".", ".tmp");
    }

    static void copy(Path source, Path target, BooleanSupplier cancelled) throws IOException {
        Path temporary = createTempSibling(target);
        boolean completed = false;
        Throwable primaryFailure = null;
        try {
            try (InputStream input = Files.newInputStream(source);
                 OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    if (read > 0) {
                        output.write(buffer, 0, read);
                    }
                }
            }
            checkCancelled(cancelled);
            moveIntoPlace(temporary, target);
            completed = true;
        } catch (IOException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!completed) {
                cleanupTemporary(temporary, primaryFailure);
            }
        }
    }

    static void writeBytes(Path target, byte[] bytes) throws IOException {
        Path temporary = createTempSibling(target);
        boolean completed = false;
        Throwable primaryFailure = null;
        try {
            Files.write(temporary, bytes);
            moveIntoPlace(temporary, target);
            completed = true;
        } catch (IOException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!completed) {
                cleanupTemporary(temporary, primaryFailure);
            }
        }
    }

    static void writeString(Path target, String value, Charset charset) throws IOException {
        Path temporary = createTempSibling(target);
        boolean completed = false;
        Throwable primaryFailure = null;
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, charset)) {
                writer.write(value);
            }
            moveIntoPlace(temporary, target);
            completed = true;
        } catch (IOException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!completed) {
                cleanupTemporary(temporary, primaryFailure);
            }
        }
    }

    static void cleanupTemporary(Path temporary, Throwable primaryFailure) throws IOException {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException | Error cleanupFailure) {
            if (primaryFailure != null) {
                if (primaryFailure != cleanupFailure) {
                    primaryFailure.addSuppressed(cleanupFailure);
                }
                return;
            }
            throw cleanupFailure;
        }
    }

    static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) {
            throw new CancellationException("cancelled by user");
        }
    }
}
