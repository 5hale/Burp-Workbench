package com.burpworkbench.modules.extractor;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class ManifestWriter implements Closeable {
    private final Path target;
    private final Path temporary;
    private final BufferedWriter writer;
    private boolean closed;

    public ManifestWriter(Path path) throws IOException {
        this(path, temporary -> Files.newBufferedWriter(temporary, StandardCharsets.UTF_8));
    }

    ManifestWriter(Path path, WriterFactory writerFactory) throws IOException {
        this.target = path;
        Path createdTemporary = AtomicFiles.createTempSibling(path);
        BufferedWriter openedWriter;
        try {
            openedWriter = writerFactory.open(createdTemporary);
        } catch (IOException | RuntimeException | Error failure) {
            AtomicFiles.cleanupTemporary(createdTemporary, failure);
            throw failure;
        }
        this.temporary = createdTemporary;
        this.writer = openedWriter;
    }

    public void write(ManifestRecord record) throws IOException {
        boolean restoreInterrupt = Thread.interrupted();
        try {
            writer.write(record.toJsonLine());
            writer.newLine();
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        boolean restoreInterrupt = Thread.interrupted();
        try {
            boolean completed = false;
            Throwable primaryFailure = null;
            try {
                writer.close();
                AtomicFiles.moveIntoPlace(temporary, target);
                completed = true;
            } catch (IOException | RuntimeException | Error failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                if (!completed) {
                    AtomicFiles.cleanupTemporary(temporary, primaryFailure);
                }
            }
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @FunctionalInterface
    interface WriterFactory {
        BufferedWriter open(Path path) throws IOException;
    }
}
