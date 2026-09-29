package com.burpworkbench.modules.replace;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;
import burp.api.montoya.ui.hotkey.HotKeyEvent;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Reads event context immediately; never queues or retains the Burp event/message. */
record ScopeCapture(Optional<HotkeyScope> scope, String source, int selectedCount) {
    static ScopeCapture empty() { return new ScopeCapture(Optional.empty(), "NONE", 0); }

    static ScopeCapture from(HotKeyEvent event, Consumer<String> log) {
        if (event == null) return empty();
        try {
            Optional<MessageEditorHttpRequestResponse> editor = event.messageEditorRequestResponse();
            if (editor != null && editor.isPresent()) {
                // A present editor is authoritative even if its request is malformed.
                return capture(editor.get().requestResponse(), "EDITOR", 1, log);
            }
        } catch (RuntimeException error) {
            log.accept("HOTKEY_SCOPE_UNAVAILABLE source=EDITOR type=" + error.getClass().getName());
            return new ScopeCapture(Optional.empty(), "EDITOR", 0);
        }
        try {
            List<HttpRequestResponse> selected = event.selectedRequestResponses();
            if (selected == null || selected.isEmpty()) return empty();
            return capture(selected.get(0), "SELECTION", selected.size(), log);
        } catch (RuntimeException error) {
            log.accept("HOTKEY_SCOPE_UNAVAILABLE source=SELECTION type=" + error.getClass().getName());
            return new ScopeCapture(Optional.empty(), "SELECTION", 0);
        }
    }

    private static ScopeCapture capture(HttpRequestResponse exchange, String source, int count,
                                        Consumer<String> log) {
        try {
            HttpRequest request = exchange == null ? null : exchange.request();
            Optional<HotkeyScope> scope = request == null ? Optional.empty() : HotkeyScope.fromUrl(request.url());
            log.accept("HOTKEY_SCOPE_CAPTURE source=" + source + " selected=" + count + " captured=" + scope.isPresent());
            return new ScopeCapture(scope, source, count);
        } catch (RuntimeException error) {
            // URL/error text may contain secrets, so log only the exception class and source.
            log.accept("HOTKEY_SCOPE_UNAVAILABLE source=" + source + " selected=" + count
                    + " type=" + error.getClass().getName());
            return new ScopeCapture(Optional.empty(), source, count);
        }
    }
}
