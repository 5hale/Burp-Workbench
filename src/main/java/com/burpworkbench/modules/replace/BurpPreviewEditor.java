package com.burpworkbench.modules.replace;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.Editor;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Uses Burp's own editor, including its configured font, themes and Pretty/Raw/Hex views. */
final class BurpPreviewEditor implements PreviewEditor {
    private static final String REQUEST = "request";
    private static final String RESPONSE = "response";
    private final CardLayout cards = new CardLayout();
    private final JPanel component = new JPanel(cards);
    private final Function<byte[], HttpRequest> requestFactory;
    private final Function<byte[], HttpResponse> responseFactory;
    private final Map<Component, Boolean> enabledStates = new IdentityHashMap<>();
    private HttpRequestEditor requestEditor;
    private HttpResponseEditor responseEditor;
    private boolean request = true;
    private boolean enabled = true;
    private boolean closed;
    private String sourceUrl = "";

    BurpPreviewEditor(UserInterface ui, boolean readOnly) {
        this(ui.createHttpRequestEditor(options(readOnly)), ui.createHttpResponseEditor(options(readOnly)),
                bytes -> HttpRequest.httpRequest(ByteArray.byteArray(bytes)),
                bytes -> HttpResponse.httpResponse(ByteArray.byteArray(bytes)));
    }

    /** Factories avoid Burp's global ObjectFactory when exercising the adapter outside Burp. */
    BurpPreviewEditor(HttpRequestEditor requestEditor, HttpResponseEditor responseEditor,
                      Function<byte[], HttpRequest> requestFactory, Function<byte[], HttpResponse> responseFactory) {
        this.requestEditor = Objects.requireNonNull(requestEditor);
        this.responseEditor = Objects.requireNonNull(responseEditor);
        this.requestFactory = Objects.requireNonNull(requestFactory);
        this.responseFactory = Objects.requireNonNull(responseFactory);
        component.add(requestEditor.uiComponent(), REQUEST);
        component.add(responseEditor.uiComponent(), RESPONSE);
        com.burpworkbench.platform.WorkbenchInput.bindNative(requestEditor,
                ()->closed||!this.request||requestEditor.getRequest()==null?null:requestEditor.getRequest().toByteArray(),"Request",
                ()->closed||!this.request?null:requestEditor.getRequest());
        com.burpworkbench.platform.WorkbenchInput.bindNative(responseEditor,
                ()->closed||this.request||responseEditor.getResponse()==null?null:responseEditor.getResponse().toByteArray(),"Response",()->null);
        com.burpworkbench.platform.WorkbenchInput.bindFull(requestEditor.uiComponent(),()->fullCapture(true));
        com.burpworkbench.platform.WorkbenchInput.bindFull(responseEditor.uiComponent(),()->fullCapture(false));
        cards.show(component, REQUEST);
    }

    @Override public void setSourceUrl(String url) { sourceUrl=Objects.requireNonNullElse(url, ""); }
    private com.burpworkbench.platform.WorkbenchInput.Value fullCapture(boolean requestSide) {
        if(closed||requestSide!=request)return null;
        ByteArray original=request ? requestEditor.getRequest()==null?null:requestEditor.getRequest().toByteArray()
                : responseEditor.getResponse()==null?null:responseEditor.getResponse().toByteArray();
        if(original==null)return null;
        com.burpworkbench.platform.WorkbenchInput.check(original.length());byte[] data=original.getBytes();
        return new com.burpworkbench.platform.WorkbenchInput.Value(data,request?"Request":"Response","Replace preview",sourceUrl,null);
    }

    private static EditorOptions[] options(boolean readOnly) {
        return readOnly ? new EditorOptions[]{EditorOptions.READ_ONLY} : new EditorOptions[0];
    }

    @Override public JComponent component() { return component; }

    @Override public void setMessage(boolean request, String text) {
        setBytes(request, Objects.requireNonNullElse(text, "").getBytes(StandardCharsets.UTF_8));
    }

    @Override public void setBytes(boolean request, byte[] bytes) {
        if (closed) return;
        if (request) requestEditor.setRequest(requestFactory.apply(bytes));
        else responseEditor.setResponse(responseFactory.apply(bytes));
        this.request = request;
        cards.show(component, request ? REQUEST : RESPONSE);
        if (!enabled) disableTree(component);
    }

    @Override public String text() {
        return new String(bytes(), StandardCharsets.UTF_8);
    }

    @Override public byte[] bytes() {
        if (closed) return new byte[0];
        ByteArray bytes;
        if (request) {
            HttpRequest message = requestEditor.getRequest();
            if (message == null) return new byte[0];
            bytes = message.toByteArray();
        } else {
            HttpResponse message = responseEditor.getResponse();
            if (message == null) return new byte[0];
            bytes = message.toByteArray();
        }
        return bytes == null ? new byte[0] : bytes.getBytes();
    }

    @Override public boolean isModified() { return !closed && active().isModified(); }

    @Override public void setCaretPosition(int position) {
        if (!closed) active().setCaretPosition(Math.max(0, position));
    }

    @Override public void setEnabled(boolean enabled) {
        if (closed) return;
        this.enabled = enabled;
        if (!enabled) disableTree(component);
        else {
            enabledStates.forEach(Component::setEnabled);
            enabledStates.clear();
        }
    }

    private void disableTree(Component item) {
        enabledStates.putIfAbsent(item, item.isEnabled());
        item.setEnabled(false);
        if (item instanceof Container container) {
            for (Component child : container.getComponents()) disableTree(child);
        }
    }

    private Editor active() { return request ? requestEditor : responseEditor; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        com.burpworkbench.platform.WorkbenchInput.bind(requestEditor.uiComponent(),null);
        com.burpworkbench.platform.WorkbenchInput.bind(responseEditor.uiComponent(),null);
        // Native editors have no public dispose method and this adapter registers no listeners.
        component.removeAll();
        enabledStates.clear();
        requestEditor = null;
        responseEditor = null;
    }
}
