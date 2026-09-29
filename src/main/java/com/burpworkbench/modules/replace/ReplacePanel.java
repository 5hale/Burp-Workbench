package com.burpworkbench.modules.replace;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Inline rule editor backed by persisted rules and the shared traffic transformation engine. */
final class ReplacePanel extends JPanel {
    private final RuleList rules;
    private final List<String> samples = new ArrayList<>();
    private final RuleTable model;
    private final JTable table;
    private final JComboBox<String> target = new JComboBox<>(RuleTypes.labels());
    private final JTextField comment = new JTextField(), url = new JTextField(), path = new JTextField();
    private final JCheckBox regex = new JCheckBox("Regex match");
    private final JTextArea match = text(true), replacement = text(true);
    private final PreviewEditor sample, result;
    private final Consumer<List<RuleDraft>> onRulesChanged;
    private final Consumer<Boolean> onEnabledChanged;
    private final JCheckBox enabled = new JCheckBox("Enabled");
    private final javax.swing.Timer samplePoll;
    private final JLabel status = new JLabel("Proxy traffic"), storageState = new JLabel();
    private final JLabel hotkeyStatus = new JLabel("Hotkey callback 0 · tab focus 0");
    private final JLabel previewStatus = new JLabel();
    private final JScrollPane listScroll;
    private final JSplitPane split;
    private final JPanel center = new JPanel(new BorderLayout());
    private JSplitPane previewSplit;
    private ResizableTextEditor matchEditor, replacementEditor;
    private JButton detailsToggle;
    private double listWidthFraction = 0.6;
    private int[] columnWidths;
    private boolean loading, closed;
    private int callbacks, focused;
    private int displayedRow = -1;

    ReplacePanel(List<RuleDraft> initial, boolean enabled, PreviewEditor sample, PreviewEditor result,
                 Consumer<List<RuleDraft>> onRulesChanged, Consumer<Boolean> onEnabledChanged,
                 Runnable openHotkeys) {
        super(new BorderLayout(0, 5));
        this.rules = new RuleList(initial);
        this.model = new RuleTable(); this.table = new JTable(model);
        this.sample = sample; this.result = result;
        this.onRulesChanged = onRulesChanged; this.onEnabledChanged = onEnabledChanged;
        this.enabled.setSelected(enabled);
        samplePoll = new javax.swing.Timer(250, event -> pollSampleChanges());
        samplePoll.setRepeats(true);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (!closed && isShowing()) samplePoll.start(); else samplePoll.stop();
            }
        });
        setBorder(new EmptyBorder(8, 10, 6, 10));
        for (int i = 0; i < rules.size(); i++) samples.add(sampleFor(rules.get(i).target()));
        configureNames();
        configureTable();
        listScroll = new JScrollPane(table);
        listScroll.setColumnHeaderView(table.getTableHeader());
        listScroll.setMinimumSize(new Dimension(300, 200));
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, details());
        split.setResizeWeight(0.6);
        split.setContinuousLayout(true);
        split.setDividerLocation(0.6);
        add(toolbar(openHotkeys), BorderLayout.NORTH);
        center.add(split, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        installEditorListeners();
        if (rules.size() > 0) table.setRowSelectionInterval(0, 0);
        else showSelected();
    }

    private void configureNames() {
        target.setName("rule.type"); comment.setName("rule.comment");
        url.setName("rule.url"); path.setName("rule.path"); regex.setName("rule.regex");
        match.setName("rule.match"); replacement.setName("rule.replace");
        sample.component().setName("preview.sample"); result.component().setName("preview.result");
        previewStatus.setName("preview.status");
        previewStatus.setVisible(false);
        enabled.setName("traffic.enabled");
    }

    private JComponent toolbar(Runnable openHotkeys) {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        enabled.addActionListener(event -> { if (!closed) onEnabledChanged.accept(enabled.isSelected()); });
        enabled.setToolTipText("Apply enabled rules to Proxy traffic");
        actions.add(enabled);
        for (JButton action : List.of(
                button("Add", "rule.add", this::add),
                button("Copy", "rule.copy", this::copy),
                button("Remove", "rule.remove", this::removeSelected),
                button("Up", "rule.up", () -> move(-1)),
                button("Down", "rule.down", () -> move(1)))) actions.add(action);
        bar.add(actions, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton toggle = new JButton("Hide details"); toggle.setName("details.toggle");
        detailsToggle = toggle;
        toggle.addActionListener(event -> toggleDetails(toggle));
        JButton hotkeys = button("Hotkeys", "hotkeys.open", openHotkeys);
        hotkeys.setToolTipText("replace ++ 단축키 설정 열기");
        right.add(toggle); right.add(hotkeys);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private void toggleDetails(JButton toggle) {
        if (split.getParent() == null) {
            center.remove(listScroll);
            split.setLeftComponent(listScroll);
            center.add(split, BorderLayout.CENTER);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            if (columnWidths != null) {
                for (int i = 0; i < columnWidths.length; i++) {
                    table.getColumnModel().getColumn(i).setPreferredWidth(columnWidths[i]);
                    table.getColumnModel().getColumn(i).setWidth(columnWidths[i]);
                }
            }
            split.setDividerLocation(listWidthFraction);
            SwingUtilities.invokeLater(() -> {
                if (split.getParent() != null) split.setDividerLocation(listWidthFraction);
            });
            toggle.setText("Hide details");
        } else {
            if (split.getWidth() > 0 && split.getDividerLocation() > 0) {
                listWidthFraction = Math.max(0.2, Math.min(0.85,
                        (double) split.getDividerLocation() / split.getWidth()));
            }
            columnWidths = new int[table.getColumnCount()];
            for (int i = 0; i < columnWidths.length; i++) {
                int width = table.getColumnModel().getColumn(i).getWidth();
                columnWidths[i] = width > 0 ? width : table.getColumnModel().getColumn(i).getPreferredWidth();
            }
            split.setLeftComponent(null);
            center.remove(split);
            center.add(listScroll, BorderLayout.CENTER);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
            toggle.setText("Show details");
        }
        center.revalidate(); center.repaint();
    }

    private JComponent footer() {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        JPanel messages = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        messages.add(status); messages.add(storageState);
        footer.add(messages, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(hotkeyStatus);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    private JComponent details() {
        JPanel sections = new DetailStack();
        sections.setName("details.stack");
        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
        sections.setBorder(new EmptyBorder(0, 6, 0, 0));

        JPanel fields = new JPanel(new GridLayout(2, 2, 8, 6));
        fields.add(labeled("Type", target));
        fields.add(labeled("Comment", comment));
        fields.add(labeled("URL / origin", url));
        fields.add(labeled("Path", path));
        JPanel metadata = new PreferredHeightPanel(new BorderLayout(0, 5));
        metadata.setName("rule.metadata");
        metadata.setBorder(new EmptyBorder(3, 8, 9, 8));
        metadata.add(fields, BorderLayout.CENTER);
        metadata.add(regex, BorderLayout.SOUTH);
        metadata.setAlignmentX(Component.LEFT_ALIGNMENT);
        sections.add(metadata);

        matchEditor = new ResizableTextEditor("Match", "rule.match.editor", match);
        replacementEditor = new ResizableTextEditor("Replace", "rule.replace.editor", replacement);
        JPanel ruleBody = new JPanel();
        ruleBody.setLayout(new BoxLayout(ruleBody, BoxLayout.Y_AXIS));
        ruleBody.setBorder(new EmptyBorder(5, 8, 9, 8));
        for (ResizableTextEditor editor : List.of(matchEditor, replacementEditor)) {
            editor.setAlignmentX(Component.LEFT_ALIGNMENT);
            ruleBody.add(editor);
        }
        CollapsibleSection ruleSection = new CollapsibleSection("Rule details", ruleBody, true);

        JPanel previewBody = new JPanel(new BorderLayout(0, 5));
        previewBody.setBorder(new EmptyBorder(5, 8, 9, 8));
        JPanel previewBar = new JPanel(new BorderLayout(5, 0));
        JPanel previewActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        previewActions.add(button("Reset sample", "preview.reset", this::resetSample));
        JButton test = button("Test", "preview.test", this::preview);
        test.setToolTipText("Test this rule's Type, Match and Replace on the sample; On and URL/Path apply only to live traffic.");
        previewActions.add(test);
        previewBar.add(previewActions, BorderLayout.EAST);
        previewBody.add(previewBar, BorderLayout.NORTH);
        previewSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                labeled("Original sample", sample.component()), labeled("Modified sample", result.component()));
        previewSplit.setName("preview.split");
        previewSplit.setResizeWeight(0.5);
        previewSplit.setContinuousLayout(true);
        previewSplit.setDividerLocation(0.5);
        previewBody.add(new ResizablePreview(previewSplit), BorderLayout.CENTER);
        previewBody.add(previewStatus, BorderLayout.SOUTH);
        CollapsibleSection previewSection = new CollapsibleSection("Test preview", previewBody, true);

        for (CollapsibleSection section : List.of(ruleSection, previewSection)) {
            section.setAlignmentX(Component.LEFT_ALIGNMENT);
            sections.add(section);
        }
        JScrollPane scroll = new JScrollPane(sections);
        scroll.setName("details.scroll");
        scroll.setMinimumSize(new Dimension(320, 200));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void configureTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(30);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getTableHeader().setReorderingAllowed(false);
        int[] widths = {48, 175, 140, 220, 220, 75};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelected();
        });
    }

    private void installEditorListeners() {
        DocumentListener ruleChanges = listener(this::commitRule);
        for (JTextField field : List.of(comment, url, path)) field.getDocument().addDocumentListener(ruleChanges);
        for (JTextArea field : List.of(match, replacement)) field.getDocument().addDocumentListener(ruleChanges);
        target.addActionListener(event -> changeTarget());
        regex.addActionListener(event -> commitRule());
    }

    private static DocumentListener listener(Runnable action) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { action.run(); }
            public void removeUpdate(DocumentEvent event) { action.run(); }
            public void changedUpdate(DocumentEvent event) { /* Syntax styles do not edit text. */ }
        };
    }

    private int selected() { return table.getSelectedRow(); }

    private void showSelected() {
        captureSample();
        int row = selected();
        displayedRow = row;
        loading = true;
        try {
            if (row < 0 || row >= rules.size()) {
                comment.setText(""); url.setText(""); path.setText("");
                match.setText(""); replacement.setText(""); sample.setMessage(false, "");
                regex.setSelected(false);
                setEditorEnabled(false);
            } else {
                RuleDraft item = rules.get(row);
                setEditorEnabled(true);
                target.setSelectedItem(item.target());
                comment.setText(item.name()); url.setText(item.url()); path.setText(item.path());
                match.setText(item.match()); replacement.setText(item.replacement());
                regex.setSelected(item.regex());
                sample.setMessage(isRequest(item.target()), samples.get(row));
                match.setCaretPosition(0); replacement.setCaretPosition(0);
                sample.setCaretPosition(0);
            }
        } finally { loading = false; }
        invalidatePreview();
    }

    private void setEditorEnabled(boolean enabled) {
        for (JComponent field : List.of(target, comment, url, path, regex, match, replacement)) {
            field.setEnabled(enabled);
        }
        sample.setEnabled(enabled);
    }

    private void changeTarget() {
        if (loading || closed) return;
        int row = selected();
        if (row < 0 || row >= rules.size()) return;
        String oldTarget = rules.get(row).target();
        String selectedTarget = (String) target.getSelectedItem();
        if (selectedTarget == null) return;
        captureSample();
        if (samples.get(row).equals(sampleFor(oldTarget))) {
            String generated = sampleFor(selectedTarget);
            samples.set(row, generated);
        }
        loading = true;
        try { sample.setMessage(isRequest(selectedTarget), samples.get(row)); } finally { loading = false; }
        commitRule();
    }

    private void commitRule() {
        if (loading || closed) return;
        int row = selected();
        if (row < 0 || row >= rules.size()) return;
        RuleDraft current = rules.get(row);
        boolean behaviorChanged = !current.target().equals(target.getSelectedItem())
                || !current.url().equals(url.getText()) || !current.path().equals(path.getText())
                || !current.match().equals(match.getText()) || !current.replacement().equals(replacement.getText())
                || current.regex() != regex.isSelected();
        RuleDraft changed = new RuleDraft(current.enabled() && !behaviorChanged, comment.getText(),
                (String) target.getSelectedItem(), url.getText(), path.getText(),
                match.getText(), replacement.getText(), regex.isSelected());
        if (current.equals(changed)) return;
        rules.set(row, changed);
        model.fireTableRowsUpdated(row, row);
        invalidatePreview();
        if (current.enabled() && behaviorChanged) status.setText("Rule changed · turn On to apply");
        onRulesChanged.accept(rules.snapshot());
    }

    private void invalidatePreview() {
        int row = selected();
        boolean hasRule = row >= 0 && row < rules.size();
        result.setMessage(hasRule && isRequest(rules.get(row).target()), "");
        showPreviewIssue("");
    }

    private void showPreviewIssue(String message) {
        previewStatus.setText(message);
        previewStatus.setVisible(!message.isEmpty());
        for (Container ancestor = previewStatus.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            ancestor.invalidate();
        }
        previewStatus.revalidate();
        previewStatus.repaint();
    }

    private void add() {
        if (closed) return;
        captureSample(); displayedRow = -1;
        RuleDraft fresh = new RuleDraft(false, "New rule", "Response body", "", "", "", "", false);
        samples.add(sampleFor(fresh.target()));
        selectAfterMutation(rules.add(fresh));
        SwingUtilities.invokeLater(() -> { comment.requestFocusInWindow(); comment.selectAll(); });
    }

    void addFromScope(String origin, String requestPath) {
        if (closed) return;
        captureSample(); displayedRow = -1;
        RuleDraft fresh = new RuleDraft(false, "New rule", "Request header", origin,
                ScopeMatcher.literalPath(requestPath), "", "", false);
        samples.add(sampleFor(fresh.target()));
        selectAfterMutation(rules.add(fresh));
        if (split.getParent() == null) toggleDetails(detailsToggle);
        status.setText("URL / Path imported into a new rule");
        SwingUtilities.invokeLater(() -> { comment.requestFocusInWindow(); comment.selectAll(); });
    }

    void scopeNotImported(String source) {
        if (closed) return;
        status.setText("NONE".equals(source) ? "No request selected · tab opened"
                : "Could not read selected URL · no rule created");
    }

    private void copy() {
        int row = selected(); if (closed || row < 0) return;
        captureSample(); displayedRow = -1;
        samples.add(row + 1, samples.get(row));
        selectAfterMutation(rules.duplicate(row));
    }

    private void removeSelected() {
        int row = selected(); if (closed || row < 0) return;
        captureSample(); displayedRow = -1;
        rules.remove(row);
        samples.remove(row);
        selectAfterMutation(Math.min(row, rules.size() - 1));
    }

    private void move(int direction) {
        int row = selected(); if (closed || row < 0) return;
        captureSample();
        int moved = rules.move(row, direction);
        if (moved != row) {
            displayedRow = -1;
            String localSample = samples.remove(row);
            samples.add(moved, localSample);
            selectAfterMutation(moved);
        }
    }

    private void selectAfterMutation(int row) {
        model.fireTableDataChanged();
        if (row >= 0 && row < rules.size()) table.setRowSelectionInterval(row, row);
        else showSelected();
        onRulesChanged.accept(rules.snapshot());
    }

    private void resetSample() {
        int row = selected(); if (closed || row < 0) return;
        String generated = sampleFor(rules.get(row).target());
        samples.set(row, generated);
        loading = true;
        try { sample.setMessage(isRequest(rules.get(row).target()), generated); } finally { loading = false; }
        invalidatePreview();
    }

    private void preview() {
        int row = selected(); if (closed || row < 0) return;
        try {
            captureSample();
            boolean request = isRequest(rules.get(row).target());
            TrafficEngine.Result applied = TrafficEngine.preview(rules.get(row), request,
                    samples.get(row).getBytes(StandardCharsets.UTF_8));
            result.setMessage(request, new String(applied.message(), StandardCharsets.UTF_8));
            result.setCaretPosition(0);
            showPreviewIssue(String.join("; ", applied.issues()));
        } catch (RuntimeException error) {
            result.setMessage(isRequest(rules.get(row).target()), "");
            showPreviewIssue("미리보기 실패: " + (error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    private static String sampleFor(String selectedTarget) {
        if (selectedTarget.equals("Request param name") || selectedTarget.equals("Request param value")) {
            return "POST /api/profile?name=old&name=keep HTTP/1.1\nHost: example.test\n"
                    + "Content-Type: application/x-www-form-urlencoded\n\n"
                    + "name=old&message=%EC%95%88%EB%85%95";
        }
        if (selectedTarget.startsWith("Request")) {
            return "POST /api/profile HTTP/1.1\nHost: example.test\nX-Demo: old\nContent-Type: application/json; charset=utf-8\n\n"
                    + "{\n  \"message\": \"안녕하세요. 안녕하세요!\",\n  \"active\": true,\n  \"count\": 2\n}";
        }
        return "HTTP/1.1 200 OK\nContent-Type: application/json; charset=utf-8\nX-Demo: old\n\n"
                + "{\n  \"message\": \"안녕하세요. 안녕하세요!\",\n  \"active\": true,\n  \"count\": 2\n}";
    }

    private static boolean isRequest(String target) { return target != null && target.startsWith("Request"); }

    private boolean captureSample() {
        if (loading || closed || displayedRow < 0 || displayedRow >= samples.size() || !sample.isModified()) return false;
        String current = sample.text();
        if (current.equals(samples.get(displayedRow))) return false;
        samples.set(displayedRow, current);
        return true;
    }

    void pollSampleChanges() {
        if (captureSample()) invalidatePreview();
    }

    void storageStatus(String message) { if (!closed) storageState.setText(message); }

    /** Called when the extension unloads or the module shuts down. */
    void closeDialogs() {
        if (closed) return;
        closed = true;
        samplePoll.stop();
        sample.close(); result.close();
    }

    void hotkeyReceived(boolean focusedTab) {
        if (closed) return;
        callbacks++;
        if (focusedTab) focused++;
        hotkeyStatus.setText("Hotkey callback " + callbacks + " · tab focus " + focused);
    }

    int ruleCount() { return rules.size(); }
    RuleDraft ruleAt(int index) { return rules.get(index); }

    private static JButton button(String label, String name, Runnable action) {
        JButton button = new JButton(label);
        button.setName(name);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static JPanel labeled(String label, JComponent child) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(new JLabel(label), BorderLayout.NORTH);
        panel.add(child, BorderLayout.CENTER);
        return panel;
    }

    private static JTextArea text(boolean editable) {
        JTextArea field = new JTextArea();
        field.setEditable(editable);
        field.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        field.setLineWrap(true);
        field.setWrapStyleWord(true);
        return field;
    }

    private static final class PreferredHeightPanel extends JPanel {
        PreferredHeightPanel(LayoutManager layout) { super(layout); }
        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
        @Override public Dimension getMinimumSize() {
            return new Dimension(120, getPreferredSize().height);
        }
    }

    private static final class DetailStack extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return new Dimension(480, 700); }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(16, visible.height - 32);
        }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private final class RuleTable extends AbstractTableModel {
        private final String[] names = {"On", "Rule", "Type", "Match", "Replace", "Mode"};
        public int getRowCount() { return rules.size(); }
        public int getColumnCount() { return names.length; }
        public String getColumnName(int column) { return names[column]; }
        public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
        public boolean isCellEditable(int row, int column) { return column == 0; }
        public Object getValueAt(int row, int column) {
            RuleDraft item = rules.get(row);
            return switch (column) {
                case 0 -> item.enabled(); case 1 -> item.name(); case 2 -> item.target();
                case 3 -> item.match(); case 4 -> item.replacement();
                default -> item.regex() ? "Regex" : "Literal";
            };
        }
        public void setValueAt(Object value, int row, int column) {
            if (!closed && column == 0) {
                RuleDraft changed = rules.get(row).toggled(Boolean.TRUE.equals(value));
                if (changed.equals(rules.get(row))) return;
                rules.set(row, changed);
                fireTableRowsUpdated(row, row);
                onRulesChanged.accept(rules.snapshot());
            }
        }
    }
}
