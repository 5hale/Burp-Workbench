package com.burpworkbench.modules.replace;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.IDN;
import java.util.Locale;
import java.util.Optional;

/** Only immutable scope metadata crosses from the hotkey callback to the EDT. */
record HotkeyScope(String origin, String path) {
    static Optional<HotkeyScope> fromUrl(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String authority = uri.getRawAuthority();
            if (scheme == null || authority == null || authority.isBlank()
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return Optional.empty();
            }
            // Origin never includes credentials, the query, or a fragment.
            int userInfoEnd = authority.lastIndexOf('@');
            if (userInfoEnd >= 0) authority = authority.substring(userInfoEnd + 1);
            if (!validAuthority(authority)) return Optional.empty();
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            return Optional.of(new HotkeyScope(scheme.toLowerCase(Locale.ROOT) + "://" + authority, path));
        } catch (URISyntaxException | IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private static boolean validAuthority(String authority) throws URISyntaxException {
        if (authority.isBlank()) return false;
        String validatedHost;
        String portSuffix;
        if (authority.startsWith("[")) {
            int closingBracket = authority.indexOf(']');
            if (closingBracket < 0) return false;
            validatedHost = authority.substring(0, closingBracket + 1);
            portSuffix = authority.substring(closingBracket + 1);
        } else {
            int separator = authority.indexOf(':');
            String host = separator < 0 ? authority : authority.substring(0, separator);
            if (host.isBlank() || host.equals(".")) return false;
            // Validate Unicode DNS names without changing the user's displayed origin.
            validatedHost = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            if (validatedHost.isEmpty() || validatedHost.length() > (validatedHost.endsWith(".") ? 254 : 253)) return false;
            portSuffix = separator < 0 ? "" : authority.substring(separator);
        }
        if (!portSuffix.isEmpty()) {
            if (portSuffix.charAt(0) != ':' || portSuffix.length() == 1) return false;
            String port = portSuffix.substring(1);
            for (int index = 0; index < port.length(); index++) {
                if (port.charAt(index) < '0' || port.charAt(index) > '9') return false;
            }
            if (Integer.parseInt(port) > 65535) return false;
        }
        URI server = new URI("http://" + validatedHost + portSuffix).parseServerAuthority();
        return server.getHost() != null && !server.getHost().isEmpty();
    }
}
