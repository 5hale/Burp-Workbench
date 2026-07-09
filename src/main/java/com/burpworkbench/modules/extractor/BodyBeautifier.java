package com.burpworkbench.modules.extractor;

final class BodyBeautifier {
    private final JavaScriptBeautifier javaScriptBeautifier;

    BodyBeautifier() {
        this(new JavaScriptBeautifier());
    }

    BodyBeautifier(JavaScriptBeautifier javaScriptBeautifier) {
        this.javaScriptBeautifier = javaScriptBeautifier;
    }

    BeautifiedText beautify(BeautifyType type, String source) {
        String safeSource = source == null ? "" : source;
        return switch (type) {
            case JAVASCRIPT -> new BeautifiedText(
                    javaScriptBeautifier.beautify(safeSource),
                    "js-beautify 2.0.3 via Rhino 1.8.0"
            );
            case JSON -> new BeautifiedText(
                    beautifyJson(safeSource),
                    "json beautifier"
            );
        };
    }

    private String beautifyJson(String source) {
        StringBuilder out = new StringBuilder(source.length() + 32);
        int indent = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (Character.isWhitespace(c)) {
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    out.append(c);
                }
                case '{', '[' -> {
                    out.append(c);
                    appendNewline(out, ++indent);
                }
                case '}', ']' -> {
                    appendNewline(out, Math.max(--indent, 0));
                    out.append(c);
                }
                case ',' -> {
                    out.append(c);
                    appendNewline(out, indent);
                }
                case ':' -> out.append(": ");
                default -> out.append(c);
            }
        }
        return trimTrailingWhitespace(out).append('\n').toString();
    }

    private void appendNewline(StringBuilder out, int indent) {
        out.append('\n');
        appendIndent(out, indent);
    }

    private void appendIndent(StringBuilder out, int indent) {
        out.append("  ".repeat(Math.max(indent, 0)));
    }

    private StringBuilder trimTrailingWhitespace(StringBuilder builder) {
        while (!builder.isEmpty() && Character.isWhitespace(builder.charAt(builder.length() - 1))) {
            builder.setLength(builder.length() - 1);
        }
        return builder;
    }

    record BeautifiedText(String text, String note) {
    }
}
