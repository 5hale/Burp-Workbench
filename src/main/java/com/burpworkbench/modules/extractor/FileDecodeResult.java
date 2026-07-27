package com.burpworkbench.modules.extractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

record FileDecodeResult(
        Path path,
        boolean decoded,
        String note,
        long rawByteCount,
        long decodedByteCount,
        String sha256
) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        Files.deleteIfExists(path);
    }
}
