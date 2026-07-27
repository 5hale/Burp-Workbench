package com.burpworkbench.modules.extractor;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class JavaScriptBeautifier {
    private static final String RESOURCE = "/vendor/js-beautify/beautify.js";
    private static final int INDENT_SIZE = 4;
    private static final int INSTRUCTION_OBSERVER_THRESHOLD = 1_024;
    private static final long MIN_INSTRUCTION_BUDGET = 1_000_000;
    private static final long MAX_INSTRUCTION_BUDGET = 100_000_000;

    private final String beautifyScript;

    JavaScriptBeautifier() {
        this.beautifyScript = loadBeautifyScript();
    }

    String beautify(String source, long maxOutputBytes, BooleanSupplier cancelled) {
        String safeSource = source == null ? "" : source;
        long safeOutputBudget = Math.max(maxOutputBytes, 1);
        checkCancelled(cancelled);
        preflight(safeSource, safeOutputBudget, cancelled);

        long instructionBudget = instructionBudget(safeSource.length(), safeOutputBudget);
        GuardedContextFactory contextFactory = new GuardedContextFactory(cancelled, instructionBudget);
        String beautified = contextFactory.call(context -> {
            context.setOptimizationLevel(-1);
            context.setInstructionObserverThreshold(INSTRUCTION_OBSERVER_THRESHOLD);
            Scriptable scope = context.initStandardObjects();
            ScriptableObject.putProperty(scope, "global", scope);
            ScriptableObject.putProperty(scope, "window", scope);
            context.evaluateString(scope, beautifyScript, RESOURCE, 1, null);

            Object value = scope.get("js_beautify", scope);
            if (!(value instanceof Function jsBeautify)) {
                throw new IllegalStateException("js_beautify function is unavailable");
            }

            Scriptable options = context.newObject(scope);
            ScriptableObject.putProperty(options, "indent_size", INDENT_SIZE);
            ScriptableObject.putProperty(options, "indent_char", " ");
            ScriptableObject.putProperty(options, "preserve_newlines", true);
            ScriptableObject.putProperty(options, "max_preserve_newlines", 2);
            ScriptableObject.putProperty(options, "space_in_empty_paren", false);

            checkCancelled(cancelled);
            Object result = jsBeautify.call(context, scope, scope, new Object[]{safeSource, options});
            checkCancelled(cancelled);
            return Context.toString(result);
        });

        checkCancelled(cancelled);
        if (beautified.length() > safeOutputBudget) {
            throw outputLimit(safeOutputBudget);
        }
        if (conservativeUtf8Length(beautified, safeOutputBudget, cancelled) > safeOutputBudget) {
            throw outputLimit(safeOutputBudget);
        }
        return beautified;
    }

    private void preflight(String source, long maxOutputBytes, BooleanSupplier cancelled) {
        if (source.length() > maxOutputBytes) {
            throw outputLimit(maxOutputBytes);
        }

        long estimatedOutput = saturatedAdd(saturatedMultiply(source.length(), 2), 128);
        int depth = 0;
        LexicalState state = LexicalState.CODE;
        boolean escaped = false;

        for (int index = 0; index < source.length(); index++) {
            if ((index & 0xff) == 0) {
                checkCancelled(cancelled);
            }
            char value = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

            if (state == LexicalState.LINE_COMMENT) {
                if (value == '\n' || value == '\r') {
                    state = LexicalState.CODE;
                    estimatedOutput = addIndentEstimate(estimatedOutput, depth);
                }
                continue;
            }
            if (state == LexicalState.BLOCK_COMMENT) {
                if (value == '*' && next == '/') {
                    state = LexicalState.CODE;
                    index++;
                }
                continue;
            }
            if (state != LexicalState.CODE) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == state.delimiter) {
                    state = LexicalState.CODE;
                }
                continue;
            }

            if (value == '/' && next == '/') {
                state = LexicalState.LINE_COMMENT;
                index++;
                continue;
            }
            if (value == '/' && next == '*') {
                state = LexicalState.BLOCK_COMMENT;
                index++;
                continue;
            }
            if (value == '\'' || value == '"' || value == '`') {
                state = LexicalState.forDelimiter(value);
                continue;
            }

            switch (value) {
                case '{', '[', '(' -> {
                    if (depth == Integer.MAX_VALUE) {
                        throw outputLimit(maxOutputBytes);
                    }
                    depth++;
                    estimatedOutput = addIndentEstimate(estimatedOutput, depth);
                }
                case '}', ']', ')' -> {
                    estimatedOutput = addIndentEstimate(estimatedOutput, depth);
                    depth = Math.max(depth - 1, 0);
                }
                case ';', ',', '\n', '\r' -> estimatedOutput = addIndentEstimate(estimatedOutput, depth);
                default -> {
                }
            }

            if (estimatedOutput > maxOutputBytes) {
                throw new BeautifyLimitException(
                        "javascript beautify preflight exceeds output budget of "
                                + maxOutputBytes + " bytes"
                );
            }
        }
        checkCancelled(cancelled);
    }

    private long addIndentEstimate(long estimate, int depth) {
        long indentation = saturatedMultiply(depth, INDENT_SIZE);
        return saturatedAdd(estimate, saturatedAdd(indentation, 2));
    }

    private long instructionBudget(int sourceLength, long maxOutputBytes) {
        long sourceAllowance = saturatedMultiply(sourceLength, 256);
        long outputAllowance = saturatedMultiply(maxOutputBytes, 16);
        long budget = saturatedAdd(MIN_INSTRUCTION_BUDGET, saturatedAdd(sourceAllowance, outputAllowance));
        return Math.min(budget, MAX_INSTRUCTION_BUDGET);
    }

    private long conservativeUtf8Length(
            String value,
            long maxOutputBytes,
            BooleanSupplier cancelled
    ) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            if ((index & 0x3ff) == 0) {
                checkCancelled(cancelled);
            }
            char character = value.charAt(index);
            long encoded;
            if (character <= 0x7f) {
                encoded = 1;
            } else if (character <= 0x7ff) {
                encoded = 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                encoded = 4;
                index++;
            } else {
                encoded = 3;
            }
            if (bytes > maxOutputBytes - encoded) {
                return maxOutputBytes + 1;
            }
            bytes += encoded;
        }
        checkCancelled(cancelled);
        return bytes;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0 || right <= 0) {
            return 0;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long saturatedAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
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

    private static String loadBeautifyScript() {
        try (InputStream stream = JavaScriptBeautifier.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing resource: " + RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to load resource: " + RESOURCE, exception);
        }
    }

    private enum LexicalState {
        CODE('\0'),
        SINGLE_QUOTE('\''),
        DOUBLE_QUOTE('"'),
        TEMPLATE('`'),
        LINE_COMMENT('\0'),
        BLOCK_COMMENT('\0');

        private final char delimiter;

        LexicalState(char delimiter) {
            this.delimiter = delimiter;
        }

        private static LexicalState forDelimiter(char delimiter) {
            return switch (delimiter) {
                case '\'' -> SINGLE_QUOTE;
                case '"' -> DOUBLE_QUOTE;
                case '`' -> TEMPLATE;
                default -> CODE;
            };
        }
    }

    private static final class GuardedContextFactory extends ContextFactory {
        private final BooleanSupplier cancelled;
        private final long instructionBudget;
        private long observedInstructions;

        private GuardedContextFactory(BooleanSupplier cancelled, long instructionBudget) {
            this.cancelled = cancelled;
            this.instructionBudget = instructionBudget;
        }

        @Override
        protected void observeInstructionCount(Context context, int instructionCount) {
            checkCancelled(cancelled);
            observedInstructions = saturatedAdd(observedInstructions, Math.max(instructionCount, 0));
            if (observedInstructions > instructionBudget) {
                throw new BeautifyLimitException("javascript beautify exceeded execution budget");
            }
        }
    }
}
