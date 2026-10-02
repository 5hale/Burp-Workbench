package com.burpworkbench.modules.extractor;

import java.util.function.BooleanSupplier;

/** Shared, bounded entry point for the formatter already used by Extractor. */
public final class BeautifyService {
    public enum Format { JAVASCRIPT, JSON }
    private BeautifyService() {}

    public static String format(Format format, String source, long maxOutputBytes, BooleanSupplier cancelled) {
        BeautifyType type = switch (format) {
            case JAVASCRIPT -> BeautifyType.JAVASCRIPT;
            case JSON -> BeautifyType.JSON;
        };
        return new BodyBeautifier().beautify(type, source, maxOutputBytes, cancelled).text();
    }
}
