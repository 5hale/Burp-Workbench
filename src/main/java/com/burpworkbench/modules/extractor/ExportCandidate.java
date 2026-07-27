package com.burpworkbench.modules.extractor;

import com.burpworkbench.core.filter.MimeCategory;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Metadata for the item currently being exported.
 *
 * <p>This type deliberately contains no request/response or body reference. Keeping it body-free
 * makes it safe to use while writing manifest and index records without retaining prior payloads.
 */
record ExportCandidate(
        String method,
        String url,
        boolean hasResponse,
        int statusCode,
        String contentType,
        String contentEncoding,
        String contentDisposition,
        Path relativePath,
        MimeCategory mimeCategory,
        boolean decoded,
        String decodeNote,
        String bodySha256,
        long rawByteCount,
        long savedByteCount
) {
    public ExportCandidate {
        method = method == null || method.isBlank() ? "GET" : method;
        url = url == null ? "" : url;
        contentType = contentType == null ? "" : contentType;
        contentEncoding = contentEncoding == null ? "" : contentEncoding;
        contentDisposition = contentDisposition == null ? "" : contentDisposition;
        relativePath = relativePath == null ? Path.of("unknown-host", "index.html") : relativePath;
        mimeCategory = mimeCategory == null ? MimeCategory.from(contentType, relativePath) : mimeCategory;
        decodeNote = decodeNote == null ? "" : decodeNote;
        bodySha256 = bodySha256 == null ? "" : bodySha256;
        rawByteCount = Math.max(rawByteCount, 0);
        savedByteCount = Math.max(savedByteCount, 0);
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
