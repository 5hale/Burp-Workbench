package com.burpworkbench.modules.extractor;

import org.brotli.dec.BrotliInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

final class ResponseBodyDecoder {
    private static final int STREAM_BUFFER_SIZE = 64 * 1024;
    private static final long DEFAULT_MAX_DECODED_BYTES = 512L * 1024 * 1024;

    private final long maxDecodedBytes;

    public ResponseBodyDecoder() {
        this(Long.getLong("burpworkbench.extractor.maxDecodedBytes", DEFAULT_MAX_DECODED_BYTES));
    }

    ResponseBodyDecoder(long maxDecodedBytes) {
        this.maxDecodedBytes = Math.max(maxDecodedBytes, 1);
    }

    /**
     * Decodes one response body to a temporary file so the decompressed representation is never
     * accumulated in the Java heap. Invalid/unsupported encodings retain the legacy fallback to
     * the raw body. A decode safety-limit, cancellation, or output I/O failure is propagated so
     * the caller can record that item as failed without ending the full export.
     */
    public FileDecodeResult decodeToTempFile(
            byte[] rawBody,
            String contentEncodingHeader,
            Path temporaryDirectory,
            BooleanSupplier cancelled
    ) throws IOException {
        byte[] safeRawBody = rawBody == null ? new byte[0] : rawBody;
        Files.createDirectories(temporaryDirectory);
        checkCancelled(cancelled);

        try (TempFileTracker temporaryFiles = new TempFileTracker()) {
            List<String> encodings = parseEncodings(contentEncodingHeader);
            if (encodings.isEmpty()) {
                FileDecodeResult result = rawResult(
                        safeRawBody,
                        temporaryDirectory,
                        "no content-encoding",
                        cancelled,
                        temporaryFiles
                );
                temporaryFiles.transfer(result.path());
                return result;
            }

            List<String> decodeOrder = new ArrayList<>(encodings);
            Collections.reverse(decodeOrder);
            List<String> applied = new ArrayList<>();
            Path currentPath = null;

            for (String encoding : decodeOrder) {
                checkCancelled(cancelled);
                if ("identity".equals(encoding)) {
                    continue;
                }

                Path sourcePath = currentPath;
                InputSource source = sourcePath == null
                        ? () -> new ByteArrayInputStream(safeRawBody)
                        : () -> Files.newInputStream(sourcePath);
                Path nextPath = temporaryFiles.create(temporaryDirectory);
                try {
                    decodeOne(source, nextPath, encoding, cancelled);
                } catch (DecodedBodyLimitException | DecodeOutputException exception) {
                    throw exception;
                } catch (CancellationException exception) {
                    throw exception;
                } catch (IOException | RuntimeException exception) {
                    FileDecodeResult result = rawResult(
                            safeRawBody,
                            temporaryDirectory,
                            "decode failed: " + exception.getMessage(),
                            cancelled,
                            temporaryFiles
                    );
                    temporaryFiles.transfer(result.path());
                    return result;
                }

                temporaryFiles.delete(currentPath);
                currentPath = nextPath;
                applied.add(encoding);
            }

            if (applied.isEmpty()) {
                FileDecodeResult result = rawResult(
                        safeRawBody,
                        temporaryDirectory,
                        "identity content-encoding",
                        cancelled,
                        temporaryFiles
                );
                temporaryFiles.transfer(result.path());
                return result;
            }

            checkCancelled(cancelled);
            long decodedByteCount = Files.size(currentPath);
            FileDecodeResult result = new FileDecodeResult(
                    currentPath,
                    true,
                    "decoded: " + String.join(", ", applied),
                    safeRawBody.length,
                    decodedByteCount,
                    sha256(currentPath, cancelled)
            );
            temporaryFiles.transfer(result.path());
            return result;
        }
    }

    private List<String> parseEncodings(String contentEncodingHeader) {
        if (contentEncodingHeader == null || contentEncodingHeader.isBlank()) {
            return List.of();
        }

        List<String> encodings = new ArrayList<>();
        for (String part : contentEncodingHeader.split(",")) {
            String encoding = part.trim().toLowerCase(Locale.ROOT);
            if (!encoding.isEmpty()) {
                encodings.add(encoding);
            }
        }
        return encodings;
    }

    private void decodeOne(
            InputSource source,
            Path target,
            String encoding,
            BooleanSupplier cancelled
    ) throws IOException {
        switch (encoding) {
            case "gzip", "x-gzip" -> {
                try (InputStream sourceInput = source.open();
                     InputStream input = new GZIPInputStream(sourceInput)) {
                    copyDecoded(input, target, cancelled);
                }
            }
            case "deflate" -> decodeDeflate(source, target, cancelled);
            case "br" -> {
                try (InputStream sourceInput = source.open();
                     InputStream input = new BrotliInputStream(sourceInput)) {
                    copyDecoded(input, target, cancelled);
                }
            }
            default -> throw new IOException("unsupported content-encoding: " + encoding);
        }
    }

    private void decodeDeflate(InputSource source, Path target, BooleanSupplier cancelled) throws IOException {
        try (InputStream sourceInput = source.open();
             InputStream input = new InflaterInputStream(sourceInput)) {
            copyDecoded(input, target, cancelled);
            return;
        } catch (DecodedBodyLimitException | DecodeOutputException exception) {
            throw exception;
        } catch (IOException zlibException) {
            Files.deleteIfExists(target);
        }

        Inflater rawInflater = new Inflater(true);
        try (InputStream sourceInput = source.open();
             InputStream input = new InflaterInputStream(sourceInput, rawInflater)) {
            copyDecoded(input, target, cancelled);
        } finally {
            rawInflater.end();
        }
    }

    private void copyDecoded(InputStream input, Path target, BooleanSupplier cancelled) throws IOException {
        long written = 0;
        try (OutputStream output = Files.newOutputStream(
                target,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            byte[] buffer = new byte[STREAM_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkCancelled(cancelled);
                if (read == 0) {
                    continue;
                }
                if (written > maxDecodedBytes - read) {
                    throw new DecodedBodyLimitException(
                            "decoded body exceeds safety limit of " + maxDecodedBytes + " bytes"
                    );
                }
                try {
                    output.write(buffer, 0, read);
                } catch (IOException exception) {
                    throw new DecodeOutputException("failed to write decoded body: " + exception.getMessage(), exception);
                }
                written += read;
            }
        }
    }

    private FileDecodeResult rawResult(
            byte[] rawBody,
            Path temporaryDirectory,
            String note,
            BooleanSupplier cancelled,
            TempFileTracker temporaryFiles
    ) throws IOException {
        Path path = temporaryFiles.create(temporaryDirectory);
        MessageDigest digest = sha256Digest();
        try (OutputStream output = Files.newOutputStream(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            checkCancelled(cancelled);
            for (int offset = 0; offset < rawBody.length; ) {
                checkCancelled(cancelled);
                int length = Math.min(STREAM_BUFFER_SIZE, rawBody.length - offset);
                output.write(rawBody, offset, length);
                digest.update(rawBody, offset, length);
                offset += length;
            }
        }
        checkCancelled(cancelled);
        return new FileDecodeResult(
                path,
                false,
                note,
                rawBody.length,
                rawBody.length,
                hex(digest.digest())
        );
    }

    private String sha256(Path path, BooleanSupplier cancelled) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[STREAM_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkCancelled(cancelled);
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return hex(digest.digest());
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) {
            throw new CancellationException("cancelled by user");
        }
    }

    @FunctionalInterface
    private interface InputSource {
        InputStream open() throws IOException;
    }

    /**
     * Owns every temporary file until a single completed result is explicitly transferred.
     * Because cleanup runs from try-with-resources, it also runs for {@link Error}s.
     */
    private static final class TempFileTracker implements AutoCloseable {
        private final Set<Path> ownedPaths = new LinkedHashSet<>();

        private Path create(Path temporaryDirectory) throws IOException {
            Path path = Files.createTempFile(temporaryDirectory, ".decoded-", ".tmp");
            try {
                ownedPaths.add(path);
                return path;
            } catch (RuntimeException | Error failure) {
                try {
                    Files.deleteIfExists(path);
                } catch (Throwable cleanupFailure) {
                    addSuppressed(failure, cleanupFailure);
                }
                throw failure;
            }
        }

        private void delete(Path path) throws IOException {
            if (path == null || !ownedPaths.contains(path)) {
                return;
            }
            Files.deleteIfExists(path);
            ownedPaths.remove(path);
        }

        private void transfer(Path retainedPath) throws IOException {
            Throwable cleanupFailure = null;
            Iterator<Path> iterator = ownedPaths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (path.equals(retainedPath)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(path);
                    iterator.remove();
                } catch (Throwable failure) {
                    cleanupFailure = merge(cleanupFailure, failure);
                }
            }
            if (cleanupFailure != null) {
                throwFailure(cleanupFailure);
            }
            ownedPaths.remove(retainedPath);
        }

        @Override
        public void close() throws IOException {
            Throwable cleanupFailure = null;
            Iterator<Path> iterator = ownedPaths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                try {
                    Files.deleteIfExists(path);
                    iterator.remove();
                } catch (Throwable failure) {
                    cleanupFailure = merge(cleanupFailure, failure);
                }
            }
            if (cleanupFailure != null) {
                throwFailure(cleanupFailure);
            }
        }

        private static Throwable merge(Throwable primary, Throwable secondary) {
            if (primary == null) {
                return secondary;
            }
            addSuppressed(primary, secondary);
            return primary;
        }

        private static void addSuppressed(Throwable primary, Throwable secondary) {
            if (primary != secondary) {
                primary.addSuppressed(secondary);
            }
        }

        private static void throwFailure(Throwable failure) throws IOException {
            if (failure instanceof IOException ioException) {
                throw ioException;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IOException("failed to clean decoded temporary files", failure);
        }
    }

    private static final class DecodedBodyLimitException extends IOException {
        private DecodedBodyLimitException(String message) {
            super(message);
        }
    }

    private static final class DecodeOutputException extends IOException {
        private DecodeOutputException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
