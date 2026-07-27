package com.burpworkbench.modules.extractor;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class BodyBeautifier {
    private static final int JSON_INDENT_WIDTH = 2;
    private static final String SPACES = "                                                                ";

    private final JavaScriptBeautifier javaScriptBeautifier;

    BodyBeautifier() {
        this(new JavaScriptBeautifier());
    }

    BodyBeautifier(JavaScriptBeautifier javaScriptBeautifier) {
        this.javaScriptBeautifier = javaScriptBeautifier;
    }

    BeautifiedText beautify(
            BeautifyType type,
            String source,
            long maxOutputBytes,
            BooleanSupplier cancelled
    ) {
        String safeSource = source == null ? "" : source;
        long safeOutputBudget = Math.max(maxOutputBytes, 1);
        checkCancelled(cancelled);
        return switch (type) {
            case JAVASCRIPT -> new BeautifiedText(
                    javaScriptBeautifier.beautify(safeSource, safeOutputBudget, cancelled),
                    "js-beautify 2.0.3 via Rhino 1.8.0"
            );
            case JSON -> new BeautifiedText(
                    beautifyJson(safeSource, safeOutputBudget, cancelled),
                    "json beautifier"
            );
        };
    }

    private String beautifyJson(String source, long maxOutputBytes, BooleanSupplier cancelled) {
        BoundedTextBuilder out = new BoundedTextBuilder(source.length(), maxOutputBytes, cancelled);
        int indent = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < source.length(); i++) {
            checkCancelled(cancelled);
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
                    if (indent == Integer.MAX_VALUE) {
                        throw outputLimit(maxOutputBytes);
                    }
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
        out.trimTrailingWhitespace();
        out.append('\n');
        return out.toString();
    }

    private void appendNewline(BoundedTextBuilder out, int indent) {
        out.append('\n');
        appendIndent(out, indent);
    }

    private void appendIndent(BoundedTextBuilder out, int indent) {
        long spaces = (long) Math.max(indent, 0) * JSON_INDENT_WIDTH;
        out.appendSpaces(spaces);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) {
            throw new CancellationException("cancelled by user");
        }
    }

    private static BeautifyLimitException outputLimit(long maxOutputBytes) {
        return new BeautifyLimitException(
                "beautified output exceeds budget of " + maxOutputBytes + " bytes"
        );
    }

    record BeautifiedText(String text, String note) {
    }

    private static final class BoundedTextBuilder {
        private final StringBuilder builder;
        private final long maxOutputBytes;
        private final BooleanSupplier cancelled;
        private long conservativeUtf8Bytes;

        private BoundedTextBuilder(
                int sourceLength,
                long maxOutputBytes,
                BooleanSupplier cancelled
        ) {
            long initial = Math.min((long) Math.min(sourceLength, 64 * 1024) + 32, maxOutputBytes);
            this.builder = new StringBuilder((int) Math.max(initial, 0));
            this.maxOutputBytes = maxOutputBytes;
            this.cancelled = cancelled;
        }

        private void append(char value) {
            checkCancelled(cancelled);
            long bytes = conservativeUtf8Bytes(value);
            ensureRemaining(bytes);
            builder.append(value);
            conservativeUtf8Bytes += bytes;
        }

        private void append(String value) {
            for (int index = 0; index < value.length(); index++) {
                append(value.charAt(index));
            }
        }

        private void appendSpaces(long count) {
            ensureRemaining(count);
            long remaining = count;
            while (remaining > 0) {
                checkCancelled(cancelled);
                int chunk = (int) Math.min(remaining, SPACES.length());
                builder.append(SPACES, 0, chunk);
                conservativeUtf8Bytes += chunk;
                remaining -= chunk;
            }
        }

        private void trimTrailingWhitespace() {
            while (!builder.isEmpty() && Character.isWhitespace(builder.charAt(builder.length() - 1))) {
                checkCancelled(cancelled);
                char removed = builder.charAt(builder.length() - 1);
                builder.setLength(builder.length() - 1);
                conservativeUtf8Bytes -= conservativeUtf8Bytes(removed);
            }
        }

        private void ensureRemaining(long additionalBytes) {
            if (additionalBytes < 0 || conservativeUtf8Bytes > maxOutputBytes - additionalBytes) {
                throw outputLimit(maxOutputBytes);
            }
        }

        private long conservativeUtf8Bytes(char value) {
            if (value <= 0x7f) {
                return 1;
            }
            if (value <= 0x7ff) {
                return 2;
            }
            return 3;
        }

        @Override
        public String toString() {
            checkCancelled(cancelled);
            return builder.toString();
        }
    }
}
