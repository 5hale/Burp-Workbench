package com.burpworkbench.modules.extractor;

import com.burpworkbench.core.util.Hashes;
import com.burpworkbench.core.filter.MimeCategory;

import burp.api.montoya.http.message.HttpRequestResponse;

import java.nio.file.Path;
import java.util.Locale;

public record ExportCandidate(
        String method,
        String url,
        boolean hasResponse,
        int statusCode,
        String contentType,
        String contentEncoding,
        String contentDisposition,
        Path relativePath,
        MimeCategory mimeCategory,
        byte[] rawBody,
        byte[] decodedBody,
        boolean decoded,
        String decodeNote,
        String bodySha256,
        HttpRequestResponse requestResponse
) {
    public ExportCandidate {
        method = method == null || method.isBlank() ? "GET" : method;
        url = url == null ? "" : url;
        contentType = contentType == null ? "" : contentType;
        contentEncoding = contentEncoding == null ? "" : contentEncoding;
        contentDisposition = contentDisposition == null ? "" : contentDisposition;
        rawBody = rawBody == null ? new byte[0] : rawBody;
        decodedBody = decodedBody == null ? new byte[0] : decodedBody;
        decodeNote = decodeNote == null ? "" : decodeNote;
        bodySha256 = bodySha256 == null ? Hashes.sha256Hex(decodedBody) : bodySha256;
        if (relativePath == null) {
            relativePath = Path.of("unknown-host", "index.html");
        }
        if (mimeCategory == null) {
            mimeCategory = MimeCategory.from(contentType, relativePath);
        }
    }

    public ExportCandidate(
            String method,
            String url,
            boolean hasResponse,
            int statusCode,
            String contentType,
            String contentEncoding,
            String contentDisposition,
            Path relativePath,
            MimeCategory mimeCategory,
            byte[] rawBody,
            byte[] decodedBody,
            boolean decoded,
            String decodeNote,
            String bodySha256
    ) {
        this(
                method,
                url,
                hasResponse,
                statusCode,
                contentType,
                contentEncoding,
                contentDisposition,
                relativePath,
                mimeCategory,
                rawBody,
                decodedBody,
                decoded,
                decodeNote,
                bodySha256,
                null
        );
    }

    public static ExportCandidate withBody(String method, String url, int statusCode, String contentType, Path relativePath, byte[] decodedBody) {
        return new ExportCandidate(
                method,
                url,
                true,
                statusCode,
                contentType,
                "",
                "",
                relativePath,
                MimeCategory.from(contentType, relativePath),
                decodedBody,
                decodedBody,
                false,
                "test body",
                Hashes.sha256Hex(decodedBody),
                null
        );
    }

    public long rawByteCount() {
        return rawBody.length;
    }

    public long savedByteCount() {
        return decodedBody.length;
    }

    public String extension() {
        if (relativePath.getFileName() == null) {
            return "";
        }
        String fileName = relativePath.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot);
    }
}
