package com.burpworkbench.modules.extractor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.InvocationType;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.File;
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
        ExportRequest request = chooseExportRequest();
        if (request == null) {
            return;
        }
        api.logging().logToOutput("Extractor started: " + request.outputRoot());
        runExport(selectedItems, includeSubtree, request);
    }

    public boolean chooseDirectoryAndExportSelection(List<HttpRequestResponse> selectedItems) {
        ExportRequest request = chooseExportRequest();
        if (request == null) {
            return false;
        }
        api.logging().logToOutput("Search++ extraction started: " + request.outputRoot());
        runExport(selectedItems, false, request);
        return true;
    }

    private ExportRequest chooseExportRequest() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose Extractor output folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setControlButtonsAreShown(false);

        JCheckBox beautify = new JCheckBox("Beautify", true);
        beautify.setToolTipText("Beautify JS and JSON responses before saving.");

        JPanel optionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        optionPanel.setBorder(BorderFactory.createEmptyBorder(7, 10, 10, 10));
        optionPanel.add(beautify);

        JButton saveButton = new JButton("Save");
        JButton cancelButton = new JButton("Cancel");
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(7, 10, 10, 10));
        buttonPanel.add(saveButton);
        buttonPanel.add(cancelButton);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(optionPanel, BorderLayout.WEST);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);

        JDialog dialog = new JDialog((Frame) null, "Choose Extractor output folder", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(chooser, BorderLayout.CENTER);
        dialog.getContentPane().add(bottomPanel, BorderLayout.SOUTH);

        final ExportRequest[] request = new ExportRequest[1];
        saveButton.addActionListener(event -> {
            File selected = chooser.getSelectedFile();
            if (selected == null) {
                selected = chooser.getCurrentDirectory();
            }
            if (selected != null) {
                ExportOptions options = ExportOptions.defaults().withBeautify(beautify.isSelected());
                request[0] = new ExportRequest(selected.toPath(), options);
            }
            dialog.dispose();
        });
        cancelButton.addActionListener(event -> dialog.dispose());
        dialog.getRootPane().setDefaultButton(saveButton);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        return request[0];
    }

    private void runExport(List<HttpRequestResponse> selectedItems, boolean includeSubtree, ExportRequest request) {
        ExportProgressDialog progressDialog = new ExportProgressDialog("Extractor");
        SwingWorker<ExportSummary, Void> worker = new SwingWorker<>() {
            @Override
            protected ExportSummary doInBackground() throws Exception {
                return exportService.export(
                        selectedItems,
                        includeSubtree,
                        request.outputRoot(),
                        request.options(),
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

    private record ExportRequest(Path outputRoot, ExportOptions options) {
    }
}
