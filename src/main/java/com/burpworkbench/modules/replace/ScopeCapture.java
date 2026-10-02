package com.burpworkbench.modules.replace;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;
import burp.api.montoya.ui.hotkey.HotKeyEvent;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Reads event context immediately; never queues or retains the Burp event/message. */
record ScopeCapture(Optional<HotkeyScope> scope, String source, int selectedCount,
                    boolean response, byte[] message, String previewIssue) {
    static final int MAX_PREVIEW_BYTES = 1024 * 1024;
    ScopeCapture {
        message = message == null ? null : message.clone();
    }
    ScopeCapture(Optional<HotkeyScope> scope, String source, int selectedCount) {
        this(scope, source, selectedCount, false, null, "");
    }
    @Override public byte[] message() { return message == null ? null : message.clone(); }
    static ScopeCapture fromWorkbench(java.awt.Component focus,Consumer<String> log){
        try{
            var value=com.burpworkbench.platform.WorkbenchInput.captureFull(focus);
            return value==null?empty():new ScopeCapture(HotkeyScope.fromUrl(value.url()),"WORKBENCH",1,
                    "Response".equals(value.kind()), value.bytes(), "");
        }catch(RuntimeException error){log.accept("WORKBENCH_SCOPE_UNAVAILABLE "+error.getClass().getSimpleName());return empty();}
    }
    static ScopeCapture empty() { return new ScopeCapture(Optional.empty(), "NONE", 0); }

    static ScopeCapture from(HotKeyEvent event, Consumer<String> log) {
        if (event == null) return empty();
        return fromContext(event::messageEditorRequestResponse,event::selectedRequestResponses,log);
    }
    static ScopeCapture fromMenu(burp.api.montoya.ui.contextmenu.ContextMenuEvent event,Consumer<String> log){
        if(event==null)return empty();
        return fromContext(event::messageEditorRequestResponse,event::selectedRequestResponses,log);
    }
    private static ScopeCapture fromContext(java.util.function.Supplier<Optional<MessageEditorHttpRequestResponse>> editorSource,
            java.util.function.Supplier<List<HttpRequestResponse>> selectionSource,Consumer<String> log){
        try {
            Optional<MessageEditorHttpRequestResponse> editor = editorSource.get();
            if (editor != null && editor.isPresent()) {
                // A present editor is authoritative even if its request is malformed.
                boolean response = editor.get().selectionContext()
                        == MessageEditorHttpRequestResponse.SelectionContext.RESPONSE;
                return capture(editor.get().requestResponse(), "EDITOR", 1, response, log);
            }
        } catch (RuntimeException error) {
            log.accept("HOTKEY_SCOPE_UNAVAILABLE source=EDITOR type=" + error.getClass().getName());
            return new ScopeCapture(Optional.empty(), "EDITOR", 0);
        }
        try {
            List<HttpRequestResponse> selected = selectionSource.get();
            if (selected == null || selected.isEmpty()) return empty();
            return capture(selected.get(0), "SELECTION", selected.size(), false, log);
        } catch (RuntimeException error) {
            log.accept("HOTKEY_SCOPE_UNAVAILABLE source=SELECTION type=" + error.getClass().getName());
            return new ScopeCapture(Optional.empty(), "SELECTION", 0);
        }
    }

    private static ScopeCapture capture(HttpRequestResponse exchange, String source, int count, boolean response,
                                        Consumer<String> log) {
        try {
            HttpRequest request = exchange == null ? null : exchange.request();
            Optional<HotkeyScope> scope = request == null ? Optional.empty() : HotkeyScope.fromUrl(request.url());
            byte[] message = null;
            String issue = "";
            try {
                var selected = response ? exchange.response() : request;
                var bytes = selected == null ? null : selected.toByteArray();
                if (bytes == null) issue = "Selected message unavailable";
                else if (bytes.length() > MAX_PREVIEW_BYTES) issue = "Preview limit: 1 MiB; message not imported";
                else message = bytes.getBytes();
            } catch (RuntimeException unavailable) {
                issue = "Selected message unavailable";
            }
            return new ScopeCapture(scope, source, count, response, message, issue);
        } catch (RuntimeException error) {
            // URL/error text may contain secrets, so log only the exception class and source.
            log.accept("HOTKEY_SCOPE_UNAVAILABLE source=" + source + " selected=" + count
                    + " type=" + error.getClass().getName());
            return new ScopeCapture(Optional.empty(), source, count);
        }
    }
}
