package com.burpworkbench.core.filter;

import java.nio.file.Path;
import java.util.Locale;

public enum MimeCategory {
    HTML,
    JAVASCRIPT,
    CSS,
    JSON,
    IMAGE,
    FONT,
    ARCHIVE,
    TEXT,
    OTHER;

    public static MimeCategory from(String contentType, Path path) {
        String normalized = normalizeContentType(contentType);
        String extension = extensionOf(path);

        if (normalized.contains("html") || ".html".equals(extension) || ".htm".equals(extension)) {
            return HTML;
        }
        if (normalized.contains("javascript") || normalized.contains("ecmascript") || ".js".equals(extension) || ".mjs".equals(extension)) {
            return JAVASCRIPT;
        }
        if (normalized.equals("text/css") || ".css".equals(extension)) {
            return CSS;
        }
        if (normalized.equals("application/json") || normalized.endsWith("+json") || ".json".equals(extension) || ".map".equals(extension)) {
            return JSON;
        }
        if (normalized.startsWith("image/") || isOneOf(extension, ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".avif", ".bmp")) {
            return IMAGE;
        }
        if (normalized.startsWith("font/")
                || normalized.contains("font")
                || isOneOf(extension, ".woff", ".woff2", ".ttf", ".otf", ".eot")) {
            return FONT;
        }
        if (isArchiveMime(normalized) || isOneOf(extension, ".zip", ".gz", ".tgz", ".tar", ".7z", ".rar", ".jar", ".war", ".bz2", ".xz")) {
            return ARCHIVE;
        }
        if (normalized.startsWith("text/") || isOneOf(extension, ".txt", ".csv", ".xml", ".md", ".log")) {
            return TEXT;
        }
        return OTHER;
    }

    public String label() {
        if (this == JAVASCRIPT) {
            return "script";
        }
        return name().toLowerCase(Locale.ROOT);
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String mediaType = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return mediaType.trim().toLowerCase(Locale.ROOT);
    }

    private static String extensionOf(Path path) {
        if (path == null || path.getFileName() == null) {
            return "";
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot);
    }

    private static boolean isArchiveMime(String mediaType) {
        return mediaType.equals("application/zip")
                || mediaType.equals("application/gzip")
                || mediaType.equals("application/x-gzip")
                || mediaType.equals("application/x-tar")
                || mediaType.equals("application/x-7z-compressed")
                || mediaType.equals("application/x-rar-compressed")
                || mediaType.equals("application/java-archive");
    }

    private static boolean isOneOf(String value, String... expected) {
        for (String item : expected) {
            if (item.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
