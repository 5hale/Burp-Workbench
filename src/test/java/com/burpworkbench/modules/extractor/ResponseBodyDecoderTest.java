package com.burpworkbench.modules.extractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseBodyDecoderTest {
    private final ResponseBodyDecoder decoder = new ResponseBodyDecoder();

    @Test
    void leavesUnencodedBodyUnchanged(@TempDir Path tempDir) throws Exception {
        byte[] body = "plain-body".getBytes(StandardCharsets.UTF_8);

        try (FileDecodeResult result = decoder.decodeToTempFile(body, null, tempDir, () -> false)) {
            assertArrayEquals(body, Files.readAllBytes(result.path()));
            assertFalse(result.decoded());
        }
    }

    @Test
    void decodesGzipBody(@TempDir Path tempDir) throws Exception {
        byte[] compressed = gzip("gzip-body");

        try (FileDecodeResult result = decoder.decodeToTempFile(compressed, "gzip", tempDir, () -> false)) {
            assertArrayEquals("gzip-body".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(result.path()));
            assertTrue(result.decoded());
        }
    }

    @Test
    void decodesDeflateBody(@TempDir Path tempDir) throws Exception {
        byte[] compressed = deflate("deflate-body");

        try (FileDecodeResult result = decoder.decodeToTempFile(compressed, "deflate", tempDir, () -> false)) {
            assertArrayEquals("deflate-body".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(result.path()));
            assertTrue(result.decoded());
        }
    }

    @Test
    void decodesBrotliBody(@TempDir Path tempDir) throws Exception {
        byte[] compressed = Base64.getDecoder().decode("CwWAYnJvdGxpLWJvZHkD");

        try (FileDecodeResult result = decoder.decodeToTempFile(compressed, "br", tempDir, () -> false)) {
            assertArrayEquals("brotli-body".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(result.path()));
            assertTrue(result.decoded());
        }
    }

    @Test
    void fallsBackToRawBodyWhenDecodingFails(@TempDir Path tempDir) throws Exception {
        byte[] body = "not-gzip".getBytes(StandardCharsets.UTF_8);

        try (FileDecodeResult result = decoder.decodeToTempFile(body, "gzip", tempDir, () -> false)) {
            assertArrayEquals(body, Files.readAllBytes(result.path()));
            assertFalse(result.decoded());
            assertTrue(result.note().startsWith("decode failed"));
        }
    }

    @Test
    void streamsDecodedBodyToTemporaryFile(@TempDir Path tempDir) throws Exception {
        byte[] compressed = gzip("streamed-body");
        Path decodedPath;

        try (FileDecodeResult result = decoder.decodeToTempFile(compressed, "gzip", tempDir, () -> false)) {
            decodedPath = result.path();
            assertTrue(Files.exists(decodedPath));
            assertArrayEquals("streamed-body".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(decodedPath));
            assertTrue(result.decoded());
            assertTrue(result.sha256().matches("[0-9a-f]{64}"));
        }

        assertFalse(Files.exists(decodedPath));
    }

    @Test
    void stopsCompressionExpansionAtConfiguredSafetyLimit(@TempDir Path tempDir) throws Exception {
        ResponseBodyDecoder limited = new ResponseBodyDecoder(16);
        byte[] compressed = gzip("x".repeat(100));

        IOException exception = assertThrows(
                IOException.class,
                () -> limited.decodeToTempFile(compressed, "gzip", tempDir, () -> false)
        );

        assertTrue(exception.getMessage().contains("safety limit"));
        assertDirectoryEmpty(tempDir);
    }

    @Test
    void streamingDecodeHonorsCancellation(@TempDir Path tempDir) {
        assertThrows(
                CancellationException.class,
                () -> decoder.decodeToTempFile(bytes("body"), null, tempDir, () -> true)
        );
        assertDirectoryEmpty(tempDir);
    }

    @Test
    void chainedDecodeFailureKeepsOnlyTransferredRawResult(@TempDir Path tempDir) throws Exception {
        byte[] compressed = gzip("not-a-second-gzip");
        Path resultPath;

        try (FileDecodeResult result = decoder.decodeToTempFile(
                compressed,
                "gzip, gzip",
                tempDir,
                () -> false
        )) {
            resultPath = result.path();
            assertFalse(result.decoded());
            assertArrayEquals(compressed, Files.readAllBytes(resultPath));
            try (var paths = Files.list(tempDir)) {
                assertEquals(1, paths.count());
            }
        }

        assertFalse(Files.exists(resultPath));
        assertDirectoryEmpty(tempDir);
    }

    @Test
    void rawWriteAndHashHonorCancellationBetweenChunks(@TempDir Path tempDir) {
        byte[] body = new byte[8 * 64 * 1024];
        AtomicInteger checks = new AtomicInteger();

        assertThrows(
                CancellationException.class,
                () -> decoder.decodeToTempFile(
                        body,
                        null,
                        tempDir,
                        () -> checks.incrementAndGet() >= 6
                )
        );

        assertTrue(checks.get() >= 6);
        assertDirectoryEmpty(tempDir);
    }

    @Test
    void temporaryFileIsRemovedWhenCancellationProbeThrowsError(@TempDir Path tempDir) {
        AssertionError expected = new AssertionError("synthetic fatal error");
        AtomicInteger checks = new AtomicInteger();

        AssertionError actual = assertThrows(
                AssertionError.class,
                () -> decoder.decodeToTempFile(
                        new byte[2 * 64 * 1024],
                        null,
                        tempDir,
                        () -> {
                            if (checks.incrementAndGet() >= 3) {
                                throw expected;
                            }
                            return false;
                        }
                )
        );

        assertSame(expected, actual);
        assertDirectoryEmpty(tempDir);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] gzip(String value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private byte[] deflate(String value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private void assertDirectoryEmpty(Path directory) {
        try (var paths = Files.list(directory)) {
            assertEquals(0, paths.count());
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
