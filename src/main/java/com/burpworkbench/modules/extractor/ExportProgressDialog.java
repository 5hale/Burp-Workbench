package com.burpworkbench.modules.extractor;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class ExportProgressDialog extends JDialog implements ExportProgressListener {
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel countsLabel = new JLabel("Preparing...");
    private final JLabel currentLabel = new JLabel(" ");
    private final JButton cancelButton = new JButton("Cancel");
    private final AtomicReference<ExportProgress> pendingProgress = new AtomicReference<>();
    private final AtomicBoolean updateScheduled = new AtomicBoolean();
    private volatile boolean cancelled;
    private volatile boolean closed;

    public ExportProgressDialog(String title) {
        super((JDialog) null, title, false);
        setLayout(new BorderLayout(8, 8));
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                requestCancellation();
            }
        });

        progressBar.setStringPainted(true);

        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(progressBar, BorderLayout.NORTH);
        center.add(countsLabel, BorderLayout.CENTER);
        center.add(currentLabel, BorderLayout.SOUTH);

        cancelButton.addActionListener(event -> requestCancellation());

        add(center, BorderLayout.CENTER);
        add(cancelButton, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(560, 145));
        pack();
        setLocationRelativeTo(null);
    }

    public void open() {
        SwingUtilities.invokeLater(() -> {
            if (!cancelled && !closed) {
                setVisible(true);
            }
        });
    }

    public void close() {
        closed = true;
        SwingUtilities.invokeLater(this::dispose);
    }

    public void requestCancellation() {
        cancelled = true;
        SwingUtilities.invokeLater(() -> {
            cancelButton.setEnabled(false);
            cancelButton.setText("Cancelling...");
        });
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void onProgress(ExportProgress progress) {
        pendingProgress.set(progress);
        scheduleProgressUpdate();
    }

    private void scheduleProgressUpdate() {
        if (updateScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(this::applyLatestProgress);
        }
    }

    private void applyLatestProgress() {
        ExportProgress progress = pendingProgress.getAndSet(null);
        if (progress != null && isDisplayable()) {
            progressBar.setMaximum(Math.max(progress.total(), 1));
            progressBar.setValue(Math.min(progress.processed(), progressBar.getMaximum()));
            progressBar.setString(progress.processed() + " / " + progress.total());
            countsLabel.setText(
                    "Saved: " + progress.saved()
                            + "   Skipped: " + progress.skipped()
                            + "   Duplicate: " + progress.duplicate()
                            + "   Failed: " + progress.failed()
            );
            String currentUrl = progress.currentUrl() == null || progress.currentUrl().isBlank()
                    ? " "
                    : progress.currentUrl();
            currentLabel.setText("<html><body style='width:520px'>Current: " + escape(currentUrl) + "</body></html>");
            if (progress.cancelled()) {
                cancelButton.setEnabled(false);
                cancelButton.setText("Cancelled");
            }
        }
        updateScheduled.set(false);
        if (pendingProgress.get() != null) {
            scheduleProgressUpdate();
        }
    }

    private String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
