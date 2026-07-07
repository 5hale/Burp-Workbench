package com.burpworkbench.modules.extractor;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;

public final class ExportProgressDialog extends JDialog implements ExportProgressListener {
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel countsLabel = new JLabel("Preparing...");
    private final JLabel currentLabel = new JLabel(" ");
    private final JButton cancelButton = new JButton("Cancel");
    private volatile boolean cancelled;

    public ExportProgressDialog(String title) {
        super((JDialog) null, title, false);
        setLayout(new BorderLayout(8, 8));

        progressBar.setStringPainted(true);

        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(progressBar, BorderLayout.NORTH);
        center.add(countsLabel, BorderLayout.CENTER);
        center.add(currentLabel, BorderLayout.SOUTH);

        cancelButton.addActionListener(event -> {
            cancelled = true;
            cancelButton.setEnabled(false);
            cancelButton.setText("Cancelling...");
        });

        add(center, BorderLayout.CENTER);
        add(cancelButton, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(560, 145));
        pack();
        setLocationRelativeTo(null);
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }

    public void close() {
        SwingUtilities.invokeLater(this::dispose);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void onProgress(ExportProgress progress) {
        SwingUtilities.invokeLater(() -> {
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
        });
    }

    private String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}

