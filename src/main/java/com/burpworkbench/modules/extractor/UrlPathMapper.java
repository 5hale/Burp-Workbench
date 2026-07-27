package com.burpworkbench.modules.extractor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class UrlPathMapper {
    private static final Pattern WINDOWS_UNSAFE_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    public Path map(String method, String url) {
        return map(method, url, null, null);
    }

    public Path map(String method, String url, String contentType, String contentDisposition) {
        URI uri = URI.create(url);
        List<String> segments = new ArrayList<>();
        segments.add(hostDirectory(uri));

        String rawPath = uri.getRawPath();
        Optional<String> contentDispositionFileName = contentDispositionFileName(contentDisposition);
        String fileNameOverride = contentDispositionFileName.orElse(null);

        if (rawPath == null || rawPath.isBlank() || "/".equals(rawPath)) {
            String fileName = fileNameOverride == null ? defaultIndexFileName(contentType) : fileNameOverride;
            segments.add(fileNameWithSuffix(applyContentTypeExtension(fileName, contentType), method, uri));
            return Path.of(segments.get(0), tail(segments));
        }

        boolean endsWithSlash = rawPath.endsWith("/");
        String[] rawSegments = rawPath.split("/");
        for (int index = 0; index < rawSegments.length; index++) {
            String rawSegment = rawSegments[index];
            if (rawSegment.isEmpty()) {
                continue;
            }

            boolean isLast = index == rawSegments.length - 1;
            String safeSegment = sanitizeSegment(percentDecodePathSegment(rawSegment));
            if (isLast && !endsWithSlash) {
                if (fileNameOverride == null) {
                    segments.add(fileNameWithSuffix(applyContentTypeExtension(safeSegment, contentType), method, uri));
                }
            } else {
                segments.add(safeSegment);
            }
        }

        if (fileNameOverride != null) {
            segments.add(fileNameWithSuffix(applyContentTypeExtension(fileNameOverride, contentType), method, uri));
        } else if (endsWithSlash) {
            segments.add(fileNameWithSuffix(defaultIndexFileName(contentType), method, uri));
        }

        if (segments.size() == 1) {
            segments.add(fileNameWithSuffix(defaultIndexFileName(contentType), method, uri));
        }

        return Path.of(segments.get(0), tail(segments));
    }

    private String[] tail(List<String> segments) {
        return segments.subList(1, segments.size()).toArray(String[]::new);
    }

    private String hostDirectory(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            host = "unknown-host";
        }

        String normalizedHost = sanitizeSegment(host.toLowerCase(Locale.ROOT));
        int port = uri.getPort();
        if (port > 0 && !isDefaultPort(uri.getScheme(), port)) {
            return normalizedHost + "_" + port;
        }
        return normalizedHost;
    }

    private boolean isDefaultPort(String scheme, int port) {
        return ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
    }

    private String fileNameWithSuffix(String fileName, String method, URI uri) {
        String safeFileName = sanitizeSegment(fileName);
        String suffix = suffixFor(method, uri);
        if (suffix.isEmpty()) {
            return safeFileName;
        }

        int dot = safeFileName.lastIndexOf('.');
        if (dot > 0) {
            return safeFileName.substring(0, dot) + suffix + safeFileName.substring(dot);
        }
        return safeFileName + suffix;
    }

    private String suffixFor(String method, URI uri) {
        boolean hasQuery = uri.getRawQuery() != null && !uri.getRawQuery().isBlank();
        boolean isGet = method == null || method.equalsIgnoreCase("GET");
        if (!hasQuery && isGet) {
            return "";
        }

        String prefix = hasQuery ? "__q_" : "__" + method.toLowerCase(Locale.ROOT) + "_";
        if (hasQuery && !isGet) {
            prefix = "__" + method.toLowerCase(Locale.ROOT) + "_q_";
        }
        return prefix + Hashes.shortSha256((method == null ? "GET" : method) + " " + uri);
    }

    private String defaultIndexFileName(String contentType) {
        String extension = extensionForContentType(contentType).orElse(".html");
        return "index" + extension;
    }

    private String applyContentTypeExtension(String fileName, String contentType) {
        String safeFileName = fileName == null || fileName.isBlank() ? "index" : fileName;
        if (hasExtension(safeFileName)) {
            return safeFileName;
        }
        return safeFileName + extensionForContentType(contentType).orElse("");
    }

    private boolean hasExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && dot < fileName.length() - 1;
    }

    private Optional<String> extensionForContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return Optional.empty();
        }

        String mediaType = contentType;
        int semicolon = mediaType.indexOf(';');
        if (semicolon >= 0) {
            mediaType = mediaType.substring(0, semicolon);
        }
        mediaType = mediaType.trim().toLowerCase(Locale.ROOT);

        return switch (mediaType) {
            case "text/html", "application/xhtml+xml" -> Optional.of(".html");
            case "application/javascript", "text/javascript", "application/x-javascript", "text/ecmascript", "application/ecmascript" -> Optional.of(".js");
            case "text/css" -> Optional.of(".css");
            case "application/json" -> Optional.of(".json");
            case "image/png" -> Optional.of(".png");
            case "image/jpeg" -> Optional.of(".jpg");
            case "image/gif" -> Optional.of(".gif");
            case "image/webp" -> Optional.of(".webp");
            case "image/svg+xml" -> Optional.of(".svg");
            case "image/x-icon", "image/vnd.microsoft.icon" -> Optional.of(".ico");
            case "image/avif" -> Optional.of(".avif");
            case "font/woff" -> Optional.of(".woff");
            case "font/woff2" -> Optional.of(".woff2");
            case "font/ttf", "application/x-font-ttf" -> Optional.of(".ttf");
            case "font/otf", "application/x-font-otf" -> Optional.of(".otf");
            case "application/vnd.ms-fontobject" -> Optional.of(".eot");
            case "application/pdf" -> Optional.of(".pdf");
            case "application/zip" -> Optional.of(".zip");
            case "application/gzip", "application/x-gzip" -> Optional.of(".gz");
            case "application/x-tar" -> Optional.of(".tar");
            case "application/x-7z-compressed" -> Optional.of(".7z");
            case "application/wasm" -> Optional.of(".wasm");
            case "application/xml", "text/xml" -> Optional.of(".xml");
            case "text/plain" -> Optional.of(".txt");
            default -> {
                if (mediaType.endsWith("+json")) {
                    yield Optional.of(".json");
                }
                if (mediaType.endsWith("+xml")) {
                    yield Optional.of(".xml");
                }
                yield Optional.empty();
            }
        };
    }

    private Optional<String> contentDispositionFileName(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isBlank()) {
            return Optional.empty();
        }

        String filename = null;
        String encodedFilename = null;
        for (String part : splitHeaderParameters(contentDisposition)) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }

            String name = part.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = unquote(part.substring(equals + 1).trim());
            if ("filename*".equals(name)) {
                encodedFilename = decodeRfc5987(value);
            } else if ("filename".equals(name)) {
                filename = value;
            }
        }

        String selected = encodedFilename != null && !encodedFilename.isBlank() ? encodedFilename : filename;
        if (selected == null || selected.isBlank()) {
            return Optional.empty();
        }

        selected = selected.replace('\\', '/');
        int slash = selected.lastIndexOf('/');
        if (slash >= 0) {
            selected = selected.substring(slash + 1);
        }
        return selected.isBlank() ? Optional.empty() : Optional.of(selected);
    }

    private List<String> splitHeaderParameters(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\' && inQuotes) {
                current.append(character);
                escaped = true;
                continue;
            }
            if (character == '"') {
                inQuotes = !inQuotes;
                current.append(character);
                continue;
            }
            if (character == ';' && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private String decodeRfc5987(String value) {
        int firstQuote = value.indexOf('\'');
        int secondQuote = firstQuote < 0 ? -1 : value.indexOf('\'', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return percentDecode(value, StandardCharsets.UTF_8);
        }

        String charsetName = value.substring(0, firstQuote);
        String encoded = value.substring(secondQuote + 1);
        Charset charset;
        try {
            charset = Charset.forName(charsetName);
        } catch (RuntimeException exception) {
            charset = StandardCharsets.UTF_8;
        }
        return percentDecode(encoded, charset);
    }

    private String percentDecode(String value, Charset charset) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), charset);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private String sanitizeSegment(String segment) {
        String sanitized = segment == null ? "" : segment;
        sanitized = WINDOWS_UNSAFE_CHARS.matcher(sanitized).replaceAll("_");
        sanitized = sanitized.strip();

        while (sanitized.endsWith(".") || sanitized.endsWith(" ")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }

        if (sanitized.isEmpty() || ".".equals(sanitized) || "..".equals(sanitized)) {
            sanitized = "_";
        }

        String baseName = sanitized;
        int dot = sanitized.indexOf('.');
        if (dot > 0) {
            baseName = sanitized.substring(0, dot);
        }

        if (WINDOWS_RESERVED_NAMES.contains(baseName.toUpperCase(Locale.ROOT))) {
            sanitized = "_" + sanitized;
        }

        if (sanitized.length() > 150) {
            sanitized = sanitized.substring(0, 120) + "__" + Hashes.shortSha256(sanitized);
        }

        return sanitized;
    }

    private String percentDecodePathSegment(String rawSegment) {
        try {
            return URLDecoder.decode(rawSegment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return rawSegment;
        }
    }

}
