package com.burpworkbench.core.codec;

import org.brotli.dec.BrotliInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public final class ResponseBodyDecoder {
    public DecodeResult decode(byte[] rawBody, String contentEncodingHeader) {
        if (rawBody == null) {
            return new DecodeResult(new byte[0], false, "empty body");
        }

        List<String> encodings = parseEncodings(contentEncodingHeader);
        if (encodings.isEmpty()) {
            return new DecodeResult(rawBody, false, "no content-encoding");
        }

        byte[] current = rawBody;
        List<String> applied = new ArrayList<>();
        List<String> decodeOrder = new ArrayList<>(encodings);
        Collections.reverse(decodeOrder);

        try {
            for (String encoding : decodeOrder) {
                if ("identity".equals(encoding)) {
                    continue;
                }

                current = switch (encoding) {
                    case "gzip", "x-gzip" -> readAll(new GZIPInputStream(new ByteArrayInputStream(current)));
                    case "deflate" -> inflateDeflate(current);
                    case "br" -> readAll(new BrotliInputStream(new ByteArrayInputStream(current)));
                    default -> throw new IOException("unsupported content-encoding: " + encoding);
                };
                applied.add(encoding);
            }
        } catch (IOException | RuntimeException exception) {
            return new DecodeResult(rawBody, false, "decode failed: " + exception.getMessage());
        }

        if (applied.isEmpty()) {
            return new DecodeResult(rawBody, false, "identity content-encoding");
        }
        return new DecodeResult(current, true, "decoded: " + String.join(", ", applied));
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

    private byte[] inflateDeflate(byte[] body) throws IOException {
        try {
            return readAll(new InflaterInputStream(new ByteArrayInputStream(body)));
        } catch (IOException zlibException) {
            Inflater rawInflater = new Inflater(true);
            try {
                return readAll(new InflaterInputStream(new ByteArrayInputStream(body), rawInflater));
            } finally {
                rawInflater.end();
            }
        }
    }

    private byte[] readAll(InputStream inputStream) throws IOException {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}

