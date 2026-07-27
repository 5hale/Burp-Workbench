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
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class SearchPlusContextMenuProvider implements ContextMenuItemsProvider, AutoCloseable {
    private final MontoyaApi api;
    private final SelectionResolver selectionResolver;
    private final RepeaterCache repeaterCache;
    private final ExtractionHandler extractionHandler;
    private final SearchExecutionCoordinator executionCoordinator;
    private final Set<SearchPlusDialog> openDialogs = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SearchPlusContextMenuProvider(
            MontoyaApi api,
            RepeaterCache repeaterCache,
            ExtractionHandler extractionHandler,
            SearchExecutionCoordinator executionCoordinator
    ) {
        this.api = api;
        this.selectionResolver = new SelectionResolver(api);
        this.repeaterCache = repeaterCache;
        this.extractionHandler = extractionHandler;
        this.executionCoordinator = executionCoordinator;
    }

    public void openSearchPlus() {
        openDialog(List.of(), List.of());
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        if (!isSupportedInvocation(event)) {
            return Collections.emptyList();
        }
        List<HttpRequestResponse> selectedItems = selectedItems(event);
        boolean includeSubtree = event.isFrom(InvocationType.SITE_MAP_TREE);
        List<HttpRequestResponse> contextItems = includeSubtree
                ? List.of()
                : selectionResolver.resolve(selectedItems, false);
        List<SelectionScope> contextScopes = includeSubtree ? SelectionResolver.scopesFor(selectedItems) : List.of();
        JMenuItem searchItem = new JMenuItem("Search++");
        searchItem.addActionListener(e -> openDialog(contextItems, contextScopes));
        return List.of(searchItem);
    }

    private void openDialog(
            List<HttpRequestResponse> contextItems,
            List<SelectionScope> contextScopes
    ) {
        if (closed.get()) {
            return;
        }
        SearchPlusDialog.open(
                api,
                contextItems,
                contextScopes,
                repeaterCache,
                this::extract,
                executionCoordinator,
                this::trackDialog
        );
    }

    private void trackDialog(SearchPlusDialog dialog) {
        if (closed.get()) {
            dialog.requestShutdown();
            dialog.dispose();
            return;
        }
        openDialogs.add(dialog);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                openDialogs.remove(dialog);
            }
        });
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<SearchPlusDialog> dialogs = List.copyOf(openDialogs);
        for (SearchPlusDialog dialog : dialogs) {
            dialog.requestShutdown();
        }
        if (repeaterCache != null) {
            repeaterCache.clear();
        }
        Runnable disposeDialogs = () -> {
            for (SearchPlusDialog dialog : List.copyOf(openDialogs)) {
                dialog.requestShutdown();
                dialog.dispose();
            }
            openDialogs.clear();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            disposeDialogs.run();
        } else {
            SwingUtilities.invokeLater(disposeDialogs);
        }
    }
}
