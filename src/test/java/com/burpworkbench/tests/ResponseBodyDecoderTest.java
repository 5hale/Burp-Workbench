package com.burpworkbench.tests;

import com.burpworkbench.core.codec.DecodeResult;
import com.burpworkbench.core.codec.ResponseBodyDecoder;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseBodyDecoderTest {
    private final ResponseBodyDecoder decoder = new ResponseBodyDecoder();

    @Test
    void leavesUnencodedBodyUnchanged() {
        byte[] body = "plain-body".getBytes(StandardCharsets.UTF_8);

        DecodeResult result = decoder.decode(body, null);

        assertArrayEquals(body, result.bytes());
        assertFalse(result.decoded());
    }

    @Test
    void decodesGzipBody() throws Exception {
        byte[] compressed = gzip("gzip-body");

        DecodeResult result = decoder.decode(compressed, "gzip");

        assertArrayEquals("gzip-body".getBytes(StandardCharsets.UTF_8), result.bytes());
        assertTrue(result.decoded());
    }

    @Test
    void decodesDeflateBody() throws Exception {
        byte[] compressed = deflate("deflate-body");

        DecodeResult result = decoder.decode(compressed, "deflate");

        assertArrayEquals("deflate-body".getBytes(StandardCharsets.UTF_8), result.bytes());
        assertTrue(result.decoded());
    }

    @Test
    void decodesBrotliBody() {
        byte[] compressed = Base64.getDecoder().decode("CwWAYnJvdGxpLWJvZHkD");

        DecodeResult result = decoder.decode(compressed, "br");

        assertArrayEquals("brotli-body".getBytes(StandardCharsets.UTF_8), result.bytes());
        assertTrue(result.decoded());
    }

    @Test
    void fallsBackToRawBodyWhenDecodingFails() {
        byte[] body = "not-gzip".getBytes(StandardCharsets.UTF_8);

        DecodeResult result = decoder.decode(body, "gzip");

        assertArrayEquals(body, result.bytes());
        assertFalse(result.decoded());
        assertTrue(result.note().startsWith("decode failed"));
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
}

