package com.burpworkbench.modules.search;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.InvocationType;
import com.burpworkbench.core.selection.SelectionResolver;
import com.burpworkbench.core.selection.SelectionScope;
import com.burpworkbench.platform.ExtractionHandler;

import javax.swing.JMenuItem;
import java.awt.Component;
import java.util.Collections;
import java.util.List;

public final class SearchPlusContextMenuProvider implements ContextMenuItemsProvider {
    private final MontoyaApi api;
    private final SelectionResolver selectionResolver;
    private final RepeaterCache repeaterCache;
    private final ExtractionHandler extractionHandler;

    public SearchPlusContextMenuProvider(MontoyaApi api, RepeaterCache repeaterCache, ExtractionHandler extractionHandler) {
        this.api = api;
        this.selectionResolver = new SelectionResolver(api);
        this.repeaterCache = repeaterCache;
        this.extractionHandler = extractionHandler;
    }

    public void openSearchPlus() {
        SearchPlusDialog.open(api, List.of(), repeaterCache, this::extract);
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        if (!isSupportedInvocation(event)) {
            return Collections.emptyList();
        }
        List<HttpRequestResponse> selectedItems = selectedItems(event);
        boolean includeSubtree = event.isFrom(InvocationType.SITE_MAP_TREE);
        List<HttpRequestResponse> contextItems = selectionResolver.resolve(selectedItems, includeSubtree);
        List<SelectionScope> contextScopes = includeSubtree ? SelectionResolver.scopesFor(selectedItems) : List.of();
        JMenuItem searchItem = new JMenuItem("Search++");
        searchItem.addActionListener(e -> SearchPlusDialog.open(api, contextItems, contextScopes, repeaterCache, this::extract));
        return List.of(searchItem);
    }

    private boolean isSupportedInvocation(ContextMenuEvent event) {
        return event.isFrom(
                InvocationType.SITE_MAP_TREE,
                InvocationType.SITE_MAP_TABLE,
                InvocationType.PROXY_HISTORY,
                InvocationType.SEARCH_RESULTS,
                InvocationType.MESSAGE_EDITOR_REQUEST,
                InvocationType.MESSAGE_EDITOR_RESPONSE,
                InvocationType.MESSAGE_VIEWER_REQUEST,
                InvocationType.MESSAGE_VIEWER_RESPONSE
        );
    }

    private List<HttpRequestResponse> selectedItems(ContextMenuEvent event) {
        if (event.selectedRequestResponses() != null && !event.selectedRequestResponses().isEmpty()) {
            return List.copyOf(event.selectedRequestResponses());
        }
        return event.messageEditorRequestResponse()
                .map(editor -> editor.requestResponse() == null ? List.<HttpRequestResponse>of() : List.of(editor.requestResponse()))
                .orElseGet(List::of);
    }

    private boolean extract(List<HttpRequestResponse> requestResponses) {
        return extractionHandler != null && extractionHandler.extract(requestResponses);
    }
}
