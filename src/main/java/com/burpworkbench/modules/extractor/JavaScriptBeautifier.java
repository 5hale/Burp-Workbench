package com.burpworkbench.modules.extractor;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class JavaScriptBeautifier {
    private static final String RESOURCE = "/vendor/js-beautify/beautify.js";

    private final String beautifyScript;

    JavaScriptBeautifier() {
        this.beautifyScript = loadBeautifyScript();
    }

    String beautify(String source) {
        return ContextFactory.getGlobal().call(context -> {
            context.setOptimizationLevel(-1);
            Scriptable scope = context.initStandardObjects();
            ScriptableObject.putProperty(scope, "global", scope);
            ScriptableObject.putProperty(scope, "window", scope);
            context.evaluateString(scope, beautifyScript, RESOURCE, 1, null);

            Object value = scope.get("js_beautify", scope);
            if (!(value instanceof Function jsBeautify)) {
                throw new IllegalStateException("js_beautify function is unavailable");
            }

            Scriptable options = context.newObject(scope);
            ScriptableObject.putProperty(options, "indent_size", 4);
            ScriptableObject.putProperty(options, "indent_char", " ");
            ScriptableObject.putProperty(options, "preserve_newlines", true);
            ScriptableObject.putProperty(options, "max_preserve_newlines", 2);
            ScriptableObject.putProperty(options, "space_in_empty_paren", false);

            Object result = jsBeautify.call(context, scope, scope, new Object[]{source == null ? "" : source, options});
            return Context.toString(result);
        });
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
}
