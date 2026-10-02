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
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ExtractorContextMenuProvider implements ContextMenuItemsProvider, AutoCloseable {
    private static final long CLOSE_AWAIT_MILLIS = 5_000;

    private final MontoyaApi api;
    private final ExportService exportService;
    private final java.util.function.Supplier<com.burpworkbench.modules.extractor.filter.FilterSettings> rules;
    private final Set<ActiveExport> activeExports = ConcurrentHashMap.newKeySet();
    private final Set<JDialog> selectionDialogs = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ExtractorContextMenuProvider(MontoyaApi api) {
        this(api, com.burpworkbench.modules.extractor.filter.FilterSettings::defaults);
    }

    public ExtractorContextMenuProvider(MontoyaApi api, java.util.function.Supplier<com.burpworkbench.modules.extractor.filter.FilterSettings> rules) {
        this.api = api;
        this.exportService = new ExportService(api);
        this.rules = rules;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        if (closed) {
            return Collections.emptyList();
        }
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
        if (request == null || closed) {
            return;
        }
        if (runExport(selectedItems, includeSubtree, request)) {
            api.logging().logToOutput("Extractor started: " + request.outputRoot());
        }
    }

    public boolean chooseDirectoryAndExportSelection(List<HttpRequestResponse> selectedItems) {
        if (closed) {
            return false;
        }
        List<HttpRequestResponse> selectionSnapshot = selectedItems == null
                ? List.of()
                : List.copyOf(selectedItems);
        ExportRequest request = chooseExportRequest();
        if (request == null || closed) {
            return false;
        }
        if (!runResolvedExport(selectionSnapshot, request)) {
            return false;
        }
        api.logging().logToOutput("Search++ extraction started: " + request.outputRoot());
        return true;
    }

    private ExportRequest chooseExportRequest() {
        if (closed) {
            return null;
        }
        ExportChooserPanel panel = new ExportChooserPanel(rules);
        JDialog dialog = new JDialog((Frame) null, "Choose Extractor output folder", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(panel);

        final ExportRequest[] request = new ExportRequest[1];
        panel.save.addActionListener(event -> {
            try {
                ExportChooserPanel.Choice choice = panel.choice();
                if (choice != null) request[0] = new ExportRequest(choice.root(), choice.options(), choice.filterSettings());
            } catch (IllegalArgumentException invalidRules) {
                JOptionPane.showMessageDialog(dialog, invalidRules.getMessage(), "Filter rules", JOptionPane.WARNING_MESSAGE);
                return;
            }
            dialog.dispose();
        });
        panel.cancel.addActionListener(event -> dialog.dispose());
        dialog.getRootPane().setDefaultButton(panel.save);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        selectionDialogs.add(dialog);
        if (closed) {
            selectionDialogs.remove(dialog);
            disposeDialog(dialog);
            return null;
        }
        try {
            dialog.setVisible(true);
        } finally {
            selectionDialogs.remove(dialog);
            disposeDialog(dialog);
        }
        return closed ? null : request[0];
    }

    private boolean runExport(List<HttpRequestResponse> selectedItems, boolean includeSubtree, ExportRequest request) {
        return startExport(
                request,
                progressListener -> serviceFor(request.filterSettings(), progressListener).export(
                        selectedItems,
                        includeSubtree,
                        request.outputRoot(),
                        request.options(),
                        progressListener
                )
        );
    }

    private boolean runResolvedExport(List<HttpRequestResponse> selectedItems, ExportRequest request) {
        return startExport(
                request,
                progressListener -> serviceFor(request.filterSettings(), progressListener).exportResolved(
                        selectedItems,
                        selectedItems.size(),
                        request.outputRoot(),
                        request.options(),
                        progressListener
                )
        );
    }

    ExportSummary exportMenuSelection(
            List<HttpRequestResponse> selectedItems,
            boolean includeSubtree,
            Path outputRoot,
            ExportOptions options,
            ExportProgressListener progressListener
    ) throws IOException {
        return exportService.export(
                selectedItems,
                includeSubtree,
                outputRoot,
                options,
                progressListener
        );
    }

    ExportService serviceFor(com.burpworkbench.modules.extractor.filter.FilterSettings settings, ExportProgressListener progress) {
        return settings == null ? exportService : new ExportService(api, 8L * 1024 * 1024,
                new FilterHook(new com.burpworkbench.modules.extractor.filter.FilterSession(settings, progress::isCancelled)));
    }

    ExportSummary exportSearchPlusSelection(
            List<HttpRequestResponse> selectedItems,
            Path outputRoot,
            ExportOptions options,
            ExportProgressListener progressListener
    ) throws IOException {
        List<HttpRequestResponse> selectionSnapshot = selectedItems == null
                ? List.of()
                : List.copyOf(selectedItems);
        return exportService.exportResolved(
                selectionSnapshot,
                selectionSnapshot.size(),
                outputRoot,
                options,
                progressListener
        );
    }

    private boolean startExport(ExportRequest request, ExportOperation exportOperation) {
        if (closed) {
            return false;
        }
        ExportProgressDialog progressDialog = new ExportProgressDialog("Extractor");
        ActiveExport activeExport = new ActiveExport(
                progressDialog,
                activeExports::remove
        );
        SwingWorker<ExportSummary, Void> worker = new SwingWorker<>() {
            @Override
            protected ExportSummary doInBackground() throws Exception {
                if (!activeExport.beginBackground()) {
                    throw new CancellationException("extractor was closed before export started");
                }
                try {
                    return exportOperation.run(progressDialog);
                } finally {
                    activeExport.finishBackground();
                }
            }

            @Override
            protected void done() {
                progressDialog.close();
                if (closed) {
                    return;
                }
                try {
                    ExportSummary summary = get();
                    api.logging().logToOutput(summary.toLogMessage());
                    JOptionPane.showMessageDialog(null, summary.toDialogMessage(), "Extractor", JOptionPane.INFORMATION_MESSAGE);
                } catch (CancellationException exception) {
                    api.logging().logToOutput("Extractor cancelled.");
                } catch (Exception exception) {
                    String message = exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage();
                    api.logging().logToError("Extractor failed: " + message);
                    JOptionPane.showMessageDialog(null, "Extractor failed:\n" + message, "Extractor", JOptionPane.ERROR_MESSAGE);
                }
            }
        };

        activeExport.attachWorker(worker);
        activeExports.add(activeExport);
        if (closed) {
            activeExport.cancelAndClose();
            return false;
        }
        worker.execute();
        progressDialog.open();
        return true;
    }

    @Override
    public void close() {
        closed = true;
        for (JDialog dialog : selectionDialogs) {
            disposeDialog(dialog);
        }

        List<ActiveExport> running = List.copyOf(activeExports);
        for (ActiveExport activeExport : running) {
            activeExport.cancelAndClose();
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_AWAIT_MILLIS);
            for (ActiveExport activeExport : running) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    logUnloadTimeout();
                    break;
                }
                try {
                    if (!activeExport.awaitBackground(remaining, TimeUnit.NANOSECONDS)) {
                        logUnloadTimeout();
                        break;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void logUnloadTimeout() {
        api.logging().logToError(
                "Extractor unload timed out; background task may still be finishing."
        );
    }

    private static void disposeDialog(JDialog dialog) {
        if (SwingUtilities.isEventDispatchThread()) {
            dialog.dispose();
        } else {
            SwingUtilities.invokeLater(dialog::dispose);
        }
    }

    static final class ActiveExport {
        private final ExportProgressDialog dialog;
        private final Consumer<ActiveExport> onFinished;
        private final CountDownLatch backgroundFinished = new CountDownLatch(1);
        private volatile SwingWorker<?, ?> worker;
        private boolean backgroundStarted;
        private boolean finished;

        ActiveExport(ExportProgressDialog dialog, Consumer<ActiveExport> onFinished) {
            this.dialog = dialog;
            this.onFinished = onFinished;
        }

        void attachWorker(SwingWorker<?, ?> worker) {
            this.worker = worker;
        }

        synchronized boolean beginBackground() {
            if (finished) {
                return false;
            }
            backgroundStarted = true;
            return true;
        }

        void finishBackground() {
            boolean notify;
            synchronized (this) {
                notify = !finished;
                finished = true;
            }
            if (notify) {
                backgroundFinished.countDown();
                onFinished.accept(this);
            }
        }

        void cancelAndClose() {
            if (dialog != null) {
                dialog.requestCancellation();
            }
            SwingWorker<?, ?> activeWorker = worker;
            if (activeWorker != null) {
                activeWorker.cancel(false);
            }
            boolean notify;
            synchronized (this) {
                notify = !backgroundStarted && !finished;
                if (notify) {
                    finished = true;
                }
            }
            if (notify) {
                backgroundFinished.countDown();
                onFinished.accept(this);
            }
            if (dialog != null) {
                dialog.close();
            }
        }

        boolean awaitBackground(long timeout, TimeUnit unit) throws InterruptedException {
            return backgroundFinished.await(timeout, unit);
        }
    }

    private record ExportRequest(Path outputRoot, ExportOptions options, com.burpworkbench.modules.extractor.filter.FilterSettings filterSettings) {
    }

    @FunctionalInterface
    private interface ExportOperation {
        ExportSummary run(ExportProgressListener progressListener) throws IOException;
    }
}
