package com.burpworkbench.modules.replace;

/** Immutable rule configuration shared by the UI, project storage, and Proxy engine. */
record RuleDraft(boolean enabled, String name, String target, String url, String path,
                 String match, String replacement, boolean regex, boolean caseSensitive) {
    RuleDraft(boolean enabled, String name, String target, String url, String path,
              String match, String replacement, boolean regex) {
        this(enabled, name, target, url, path, match, replacement, regex, false);
    }
    RuleDraft {
        if (name == null || target == null || url == null || path == null || match == null || replacement == null) {
            throw new IllegalArgumentException("Rule fields cannot be null");
        }
    }

    RuleDraft renamed(String newName) {
        return new RuleDraft(enabled, newName, target, url, path, match, replacement, regex, caseSensitive);
    }

    RuleDraft toggled(boolean newEnabled) {
        return new RuleDraft(newEnabled, name, target, url, path, match, replacement, regex, caseSensitive);
    }

    static RuleDraft sample() {
        return new RuleDraft(true, "한글 문구", "Response body", "https://example.test", "/api/profile",
                "안녕하세요", "반갑습니다", false);
    }
}
