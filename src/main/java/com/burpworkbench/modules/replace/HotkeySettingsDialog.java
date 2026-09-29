package com.burpworkbench.modules.replace;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/** Session-only shortcut editor; it does not claim to modify Burp's native Hotkeys settings. */
final class HotkeySettingsDialog extends JDialog {
    private final HotkeyBinding binding;
    private final JLabel current = new JLabel();
    private final JLabel cleanup = new JLabel();
    private final JLabel feedback = new JLabel(" ");
    private final JTextField capture = new JTextField();
    private Shortcut proposed;

    HotkeySettingsDialog(Window owner, HotkeyBinding binding, Consumer<Component> applyTheme) {
        super(owner, "replace ++ Hotkeys", ModalityType.MODELESS);
        this.binding = binding;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(470, 240));

        JPanel body = new JPanel(new BorderLayout(8, 12));
        body.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        JPanel fields = new JPanel();
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.add(new JLabel("Open replace ++ tab"));
        fields.add(Box.createVerticalStrut(6));
        fields.add(current);
        fields.add(Box.createVerticalStrut(10));
        capture.setEditable(false);
        capture.setToolTipText("Click here, then press Ctrl or Cmd with a letter, number, or F1–F12.");
        capture.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                event.consume();
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    proposed = null;
                    capture.setText("");
                    feedback.setText("Capture cancelled.");
                    return;
                }
                try {
                    proposed = Shortcut.fromKeyEvent(event);
                    capture.setText(proposed.value());
                    feedback.setText("Press Apply to use this shortcut.");
                } catch (IllegalArgumentException error) {
                    if (event.getKeyCode() != KeyEvent.VK_CONTROL && event.getKeyCode() != KeyEvent.VK_META
                            && event.getKeyCode() != KeyEvent.VK_SHIFT && event.getKeyCode() != KeyEvent.VK_ALT) {
                        proposed = null;
                        capture.setText("");
                        feedback.setText(error.getMessage());
                    }
                }
            }
        });
        fields.add(capture);
        fields.add(Box.createVerticalStrut(6));
        fields.add(new JLabel("Click the box and press Ctrl/Cmd + key. Session-only; no traffic rules."));
        fields.add(Box.createVerticalStrut(8));
        fields.add(cleanup);
        body.add(fields, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton apply = new JButton("Apply");
        apply.addActionListener(event -> {
            HotkeyBinding.Result result = binding.assign(proposed);
            feedback.setText(result.message());
            if (result.success()) proposed = null;
            refresh();
        });
        JButton clear = new JButton("Clear");
        clear.addActionListener(event -> {
            HotkeyBinding.Result result = binding.clear();
            proposed = null;
            feedback.setText(result.message());
            refresh();
        });
        JButton retry = new JButton("Retry cleanup");
        retry.addActionListener(event -> {
            feedback.setText(binding.retryCleanup().message());
            refresh();
        });
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        actions.add(apply);
        actions.add(clear);
        actions.add(retry);
        actions.add(close);

        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.add(feedback, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.SOUTH);
        body.add(footer, BorderLayout.SOUTH);
        setContentPane(body);
        applyTheme.accept(body);
        pack();
        setSize(Math.max(520, getWidth()), Math.max(250, getHeight()));
        setLocationRelativeTo(owner);
        refresh();
    }

    void showHotkeys() {
        refresh();
        setVisible(true);
        toFront();
        capture.requestFocusInWindow();
    }

    private void refresh() {
        Shortcut selected = binding.current();
        current.setText("Current shortcut: " + (selected == null ? "Not assigned" : selected.value()));
        cleanup.setText(binding.pendingCount() == 0 ? " "
                : "Previous registration cleanup pending: " + binding.pendingCount());
        capture.setText(proposed == null ? "Press shortcut here" : proposed.value());
    }
}
