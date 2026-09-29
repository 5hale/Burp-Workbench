package com.burpworkbench.modules.replace;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Exact origins and bounded raw-path globs; query strings are not part of path scope. */
final class ScopeMatcher {
    private ScopeMatcher() {}

    /** Escape a captured raw path so a literal asterisk cannot widen its scope. */
    static String literalPath(String path) {
        return path.replace("\\", "\\\\").replace("*", "\\*");
    }

    static boolean matches(RuleDraft rule, String requestUrl) {
        if (rule.url().isBlank() && unrestrictedPath(rule.path())) return true;
        URI request = uri(requestUrl);
        String requestOrigin = origin(request, false);
        if (!rule.url().isBlank() && !origin(uri(rule.url().trim()), true).equals(requestOrigin)) return false;
        if (unrestrictedPath(rule.path())) return true;
        String path = request.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        return pathMatches(rule.path(), path);
    }

    private static boolean unrestrictedPath(String glob) {
        return glob.isBlank() || glob.equals("**");
    }

    static boolean pathMatches(String glob, String path) {
        if (unrestrictedPath(glob)) return true;
        if (glob.length() > 1024 || path.length() > 16 * 1024) {
            throw new IllegalArgumentException("Path scope exceeds 1024 pattern / 16384 path characters");
        }
        if (!glob.startsWith("/")) throw new IllegalArgumentException("Path scope must begin with /");
        List<Token> tokens = tokens(glob);
        // Dynamic programming prevents glob backtracking spikes even with many alternating stars.
        boolean[] reachable = new boolean[path.length() + 1];
        boolean[] next = new boolean[path.length() + 1];
        reachable[0] = true;
        for (int p = 0; p < tokens.size(); p++) {
            Token token = tokens.get(p);
            Arrays.fill(next, false);
            if (token.kind() == 1 || token.kind() == 2) {
                boolean recursive = token.kind() == 2;
                next[0] = reachable[0];
                for (int i = 1; i <= path.length(); i++) {
                    next[i] = reachable[i] || next[i - 1] && (recursive || path.charAt(i - 1) != '/');
                }
            } else if (token.kind() == 3) {
                // **/ accepts zero directory segments, or any prefix ending at '/'.
                boolean prefix = reachable[0];
                next[0] = reachable[0];
                for (int i = 1; i <= path.length(); i++) {
                    next[i] = reachable[i] || prefix && path.charAt(i - 1) == '/';
                    prefix |= reachable[i];
                }
            } else {
                for (int i = 1; i <= path.length(); i++) next[i] = reachable[i - 1] && path.charAt(i - 1) == token.literal();
                // A final /** also includes the directory itself, without a trailing slash.
                if (token.literal() == '/' && p == tokens.size() - 2 && tokens.get(p + 1).kind() == 2) {
                    next[path.length()] |= reachable[path.length()];
                }
            }
            boolean[] previous = reachable;
            reachable = next;
            next = previous;
        }
        return reachable[path.length()];
    }

    private record Token(int kind, char literal) {}

    private static List<Token> tokens(String glob) {
        List<Token> result = new ArrayList<>();
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '\\') {
                if (++i >= glob.length()) throw new IllegalArgumentException("Path glob ends with an incomplete escape");
                result.add(new Token(0, glob.charAt(i)));
            } else if (ch != '*') result.add(new Token(0, ch));
            else if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') { i++; result.add(new Token(3, '\0')); }
                else result.add(new Token(2, '\0'));
            } else result.add(new Token(1, '\0'));
        }
        return result;
    }

    private static URI uri(String value) {
        if (value == null || value.length() > 64 * 1024) throw new IllegalArgumentException("Invalid request URL");
        try { return URI.create(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid URL scope or request URL"); }
    }

    private static String origin(URI uri, boolean scope) {
        String scheme = uri.getScheme(), host = uri.getHost();
        if (scheme == null || host == null || uri.getUserInfo() != null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("URL scope requires an http(s) origin");
        }
        if (scope && ((uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                || uri.getRawQuery() != null || uri.getRawFragment() != null)) {
            throw new IllegalArgumentException("Put URL paths in Path; URL accepts only the origin");
        }
        int port = uri.getPort();
        if (port < 0) port = scheme.equalsIgnoreCase("https") ? 443 : 80;
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid origin port");
        return scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
    }
}
