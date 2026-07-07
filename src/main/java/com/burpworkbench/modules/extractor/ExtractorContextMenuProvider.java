package com.burpworkbench.modules.extractor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.InvocationType;

import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import java.awt.Component;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public final class ExtractorContextMenuProvider implements ContextMenuItemsProvider {
    private final MontoyaApi api;
    private final ExportService exportService;

    public ExtractorContextMenuProvider(MontoyaApi api) {
        this.api = api;
        this.exportService = new ExportService(api);
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        if (!isSupportedInvocation(event)) {
            return Collections.emptyList();
        }
        List<HttpRequestResponse> selectedItems = selectedItems(event);
        if (selectedItems.isEmpty()) {
            return Collections.emptyList();
        }
        boolean includeSubtree = event.isFrom(InvocationType.SITE_MAP_TREE);
        JMenuItem extractItem = new JMenuItem("Extractor");
        extractItem.addActionListener(e -> chooseDirectoryAndExport(selectedItems, includeSubtree));
        return List.of(extractItem);
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

    private void chooseDirectoryAndExport(List<HttpRequestResponse> selectedItems, boolean includeSubtree) {
        Path outputRoot = chooseOutputRoot();
        if (outputRoot == null) {
            return;
        }
        api.logging().logToOutput("Extractor started: " + outputRoot);
        runExport(selectedItems, includeSubtree, outputRoot);
    }

    public boolean chooseDirectoryAndExportSelection(List<HttpRequestResponse> selectedItems) {
        Path outputRoot = chooseOutputRoot();
        if (outputRoot == null) {
            return false;
        }
        api.logging().logToOutput("Search++ extraction started: " + outputRoot);
        runExport(selectedItems, false, outputRoot);
        return true;
    }

    private Path chooseOutputRoot() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose Extractor output folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);

        int result = chooser.showSaveDialog(null);
        if (result != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return null;
        }
        return chooser.getSelectedFile().toPath();
    }

    private void runExport(List<HttpRequestResponse> selectedItems, boolean includeSubtree, Path outputRoot) {
                ExportProgressDialog progressDialog = new ExportProgressDialog("Extractor");
                SwingWorker<ExportSummary, Void> worker = new SwingWorker<>() {
            @Override
            protected ExportSummary doInBackground() throws Exception {
                            return exportService.export(
                                    selectedItems,
                                    includeSubtree,
                                    outputRoot,
                                    ExportOptions.defaults(),
                                    progressDialog
                );
            }

            @Override
            protected void done() {
                progressDialog.close();
                try {
                    ExportSummary summary = get();
                    api.logging().logToOutput(summary.toLogMessage());
                    JOptionPane.showMessageDialog(null, summary.toDialogMessage(), "Extractor", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception exception) {
                    String message = exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage();
                    api.logging().logToError("Extractor failed: " + message);
                    JOptionPane.showMessageDialog(null, "Extractor failed:\n" + message, "Extractor", JOptionPane.ERROR_MESSAGE);
                }
            }
        };

        worker.execute();
        progressDialog.open();
    }
}
