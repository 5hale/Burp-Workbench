package com.burpworkbench.modules.extractor;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ManifestWriter implements Closeable {
    private final BufferedWriter writer;

    public ManifestWriter(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    public void write(ManifestRecord record) throws IOException {
        writer.write(record.toJsonLine());
        writer.newLine();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}

