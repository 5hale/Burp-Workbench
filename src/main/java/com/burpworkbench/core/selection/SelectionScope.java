package com.burpworkbench.core.selection;

import burp.api.montoya.http.message.HttpRequestResponse;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

public record SelectionScope(String scheme, String host, int port, String path, boolean exactPathOnly) {
    public static Optional<SelectionScope> from(HttpRequestResponse item) {
        try {
            return fromUrl(item.request().url());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public static Optional<SelectionScope> fromUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = normalizeScheme(uri.getScheme());
            String host = normalizeHost(uri.getHost());
            if (scheme.isEmpty() || host.isEmpty()) {
                return Optional.empty();
            }

            String path = normalizePath(uri.getRawPath());
            boolean exact = !path.endsWith("/") && lastSegment(path).contains(".");
            return Optional.of(new SelectionScope(scheme, host, effectivePort(scheme, uri.getPort()), path, exact));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public boolean matches(HttpRequestResponse item) {
        try {
            return matchesUrl(item.request().url());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public boolean matchesUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!scheme.equals(normalizeScheme(uri.getScheme()))) {
                return false;
            }
            if (!host.equals(normalizeHost(uri.getHost()))) {
                return false;
            }
            if (port != effectivePort(scheme, uri.getPort())) {
                return false;
            }

            String candidatePath = normalizePath(uri.getRawPath());
            if ("/".equals(path)) {
                return true;
            }
            if (exactPathOnly) {
                return candidatePath.equals(path);
            }
            String directoryPrefix = path.endsWith("/") ? path : path + "/";
            return candidatePath.equals(path) || candidatePath.startsWith(directoryPrefix);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String normalizeScheme(String scheme) {
        return scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
    }

    private static String normalizeHost(String host) {
        return host == null ? "" : host.toLowerCase(Locale.ROOT);
    }

    private static int effectivePort(String scheme, int port) {
        if (port > 0) {
            return port;
        }
        if ("https".equals(scheme)) {
            return 443;
        }
        if ("http".equals(scheme)) {
            return 80;
        }
        return -1;
    }

    private static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "/";
        }
        return rawPath.startsWith("/") ? rawPath : "/" + rawPath;
    }

    private static String lastSegment(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }
}

