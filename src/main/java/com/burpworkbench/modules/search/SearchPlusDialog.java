package com.burpworkbench.modules.search;

import com.burpworkbench.core.filter.ExtensionFilters;
import com.burpworkbench.core.http.HttpExchange;
import com.burpworkbench.core.http.HttpExchangeFactory;
import com.burpworkbench.core.filter.MimeCategory;
import com.burpworkbench.core.selection.SelectionScope;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.JToggleButton;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager2;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.datatransfer.StringSelection;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class SearchPlusDialog extends JFrame {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int CONTROL_GRID_COLUMNS = 4;
    private static final int CONTROL_GRID_GAP = 8;
    private static final int CONTROL_GRID_ROW_GAP = 6;
    private static final int CONTROL_GRID_MIN_WIDTH = 1080;
    private static final Dimension DIALOG_PREFERRED_SIZE = new Dimension(1450, 900);
    private static final Dimension DIALOG_MINIMUM_SIZE = new Dimension(1120, 650);
    private static final double RESULTS_TABLE_INITIAL_RATIO = 0.56;
    private static final int RESULTS_TABLE_MIN_HEIGHT = 180;
    private static final int PREVIEW_MIN_HEIGHT = 320;
    private static final List<MimeCategory> VISIBLE_MIME_CATEGORIES = List.of(
            MimeCategory.HTML,
            MimeCategory.JAVASCRIPT,
            MimeCategory.CSS,
            MimeCategory.IMAGE,
            MimeCategory.JSON,
            MimeCategory.TEXT,
            MimeCategory.OTHER
    );
    private final MontoyaApi api;
    private final List<HttpExchange> contextExchanges;
    private final List<SelectionScope> contextScopes;
    private final RepeaterCache repeaterCache;
    private final Predicate<List<HttpRequestResponse>> extractHandler;
    private final SearchEngine searchEngine = new SearchEngine();
    private final JTextField queryField = new JTextField(24);
    private final JComboBox<SearchMode> modeCombo = new JComboBox<>(SearchMode.values());
    private final JToggleButton regexCheck = textToggleButton(".*", "Regex");
    private final JToggleButton caseCheck = textToggleButton("Cc", "Case sensitive");
    private final JTextField negativeFilterField = new JTextField(18);
    private final JButton negativeApplyButton = iconButton(new SearchIcon(), "Apply negative match filter");
    private final JCheckBox negativeAutoCheck = new JCheckBox("auto");
    private final JCheckBox requestHeadersCheck = new JCheckBox("Request headers", true);
    private final JCheckBox requestBodyCheck = new JCheckBox("Request body", true);
    private final JCheckBox responseHeadersCheck = new JCheckBox("Response headers", true);
    private final JCheckBox responseBodyCheck = new JCheckBox("Response body", true);
    private final JCheckBox targetSourceCheck = new JCheckBox("Target", true);
    private final JCheckBox proxySourceCheck = new JCheckBox("Proxy", true);
    private final JCheckBox repeaterSourceCheck = new JCheckBox("Repeater", true);
    private final JCheckBox organizerSourceCheck = new JCheckBox("Organizer", true);
    private final JCheckBox status2xxCheck = new JCheckBox("2xx [success]", true);
    private final JCheckBox status3xxCheck = new JCheckBox("3xx [redirection]", true);
    private final JCheckBox status4xxCheck = new JCheckBox("4xx [request error]", true);
    private final JCheckBox status5xxCheck = new JCheckBox("5xx [server error]", true);
    private final JCheckBox showExtensionCheck = new JCheckBox("Show only:");
    private final JCheckBox hideExtensionCheck = new JCheckBox("Hide:");
    private final JTextField showExtensionField = new JTextField(18);
    private final JTextField hideExtensionField = new JTextField(18);
    private final JLabel contextLabel = new JLabel("Context: 0 items");
    private final JButton filterButton = iconButton(new FilterIcon(), "Show filters");
    private final List<JPanel> inlineFilterPanels = new ArrayList<>();
    private final Map<MimeCategory, JCheckBox> mimeChecks = new EnumMap<>(MimeCategory.class);
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"Source", "Host", "Method", "URL", "Status", "MIME", "Length", "Time"},
            0
    ) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(tableModel);
    private final JLabel countLabel = new JLabel("0 results");
    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;
    private final JPanel responseHolder = new JPanel(new BorderLayout());
    private List<SearchResult> currentResults = List.of();
    private List<SearchResult> allResults = List.of();
    private String activeNegativeFilter = "";
    private SwingWorker<Void, SearchResult> currentWorker;

    private SearchPlusDialog(
            Window locationOwner,
            MontoyaApi api,
            List<HttpRequestResponse> contextItems,
            List<SelectionScope> contextScopes,
            RepeaterCache repeaterCache,
            Predicate<List<HttpRequestResponse>> extractHandler
    ) {
        super("Search++");
        this.api = api;
        List<HttpRequestResponse> safeContextItems = contextItems == null ? List.of() : List.copyOf(contextItems);
        this.contextExchanges = HttpExchangeFactory.fromRequestResponses("Context", safeContextItems);
        this.contextScopes = contextScopes == null ? List.of() : List.copyOf(contextScopes);
        this.repeaterCache = repeaterCache;
        this.extractHandler = extractHandler;
        this.requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        this.responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

        initializeMimeChecks();
        configureShrinkableTextFields();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(topPanel(), BorderLayout.NORTH);
        add(resultsAndPreviewPanel(), BorderLayout.CENTER);

        contextLabel.setText("Context: " + contextExchanges.size() + " items");
        configureFilterControls();
        configureTable();
        queryField.addActionListener(event -> runSearch());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                closeOwnedWindows();
            }

            @Override
            public void windowClosing(WindowEvent event) {
                closeOwnedWindows();
            }
        });
        if (this.contextExchanges.isEmpty()) {
            clearPreview();
        } else {
            runSearch();
        }

        setPreferredSize(DIALOG_PREFERRED_SIZE);
        setMinimumSize(DIALOG_MINIMUM_SIZE);
        pack();
        setAlwaysOnTop(false);
        setLocationRelativeTo(locationOwner);
    }

    public static void open(
            MontoyaApi api,
            List<HttpRequestResponse> contextItems,
            RepeaterCache repeaterCache,
            Predicate<List<HttpRequestResponse>> extractHandler
    ) {
        open(api, contextItems, List.of(), repeaterCache, extractHandler);
    }

    public static void open(
            MontoyaApi api,
            List<HttpRequestResponse> contextItems,
            List<SelectionScope> contextScopes,
            RepeaterCache repeaterCache,
            Predicate<List<HttpRequestResponse>> extractHandler
    ) {
        SwingUtilities.invokeLater(() -> {
            Window locationOwner = api.userInterface().swingUtils().suiteFrame();
            SearchPlusDialog dialog = new SearchPlusDialog(locationOwner, api, contextItems, contextScopes, repeaterCache, extractHandler);
            dialog.setVisible(true);
            dialog.toFront();
            dialog.requestFocus();
        });
    }

    private static JToggleButton textToggleButton(String text, String tooltip) {
        JToggleButton button = new JToggleButton(text);
        configureIconButton(button, tooltip);
        return button;
    }

    private static JButton iconButton(Icon icon, String tooltip) {
        JButton button = new JButton(icon);
        configureIconButton(button, tooltip);
        return button;
    }

    private static void configureIconButton(AbstractButton button, String tooltip) {
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new Insets(1, 1, 1, 1));
        button.setPreferredSize(new Dimension(32, 28));
        button.setMinimumSize(new Dimension(32, 28));
    }

    private JPanel topPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 0, 10));
        panel.add(controlGridPanel(), BorderLayout.CENTER);
        return panel;
    }

    private JPanel controlGridPanel() {
        inlineFilterPanels.clear();
        JPanel controls = new JPanel(new ControlGridLayout());
        addControlPanel(controls, searchPanel(), 0, 0, 2, false);
        addControlPanel(controls, locationsPanel(), 2, 0, 1, false);
        addControlPanel(controls, toolsPanel(), 3, 0, 1, false);
        addControlPanel(controls, negativeMatchPanel(), 0, 1, 1, true);
        addControlPanel(controls, extensionPanel(), 1, 1, 1, true);
        addControlPanel(controls, mimePanel(), 2, 1, 1, true);
        addControlPanel(controls, statusPanel(), 3, 1, 1, true);
        return controls;
    }

    private void addControlPanel(JPanel parent, JPanel child, int gridx, int gridy, int gridwidth, boolean inlineFilter) {
        if (inlineFilter) {
            child.setVisible(false);
            inlineFilterPanels.add(child);
        }
        parent.add(child, new ControlCell(gridy, gridx, gridwidth));
    }

    private JPanel searchPanel() {
        JPanel searchPanel = new JPanel(new GridBagLayout());
        searchPanel.setBorder(BorderFactory.createTitledBorder("Search"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;

        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 1;
        constraints.weightx = 1;
        queryField.setColumns(22);
        searchPanel.add(queryField, constraints);

        constraints.gridx = 1;
        constraints.weightx = 0;
        searchPanel.add(modeCombo, constraints);

        constraints.gridx = 2;
        searchPanel.add(regexCheck, constraints);

        constraints.gridx = 3;
        searchPanel.add(caseCheck, constraints);

        JButton searchButton = iconButton(new SearchIcon(), "Search");
        searchButton.addActionListener(event -> runSearch());
        constraints.gridx = 4;
        searchPanel.add(searchButton, constraints);

        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.gridwidth = 4;
        constraints.weightx = 1;
        searchPanel.add(statusLinePanel(), constraints);

        filterButton.addActionListener(event -> toggleInlineFilters());
        constraints.gridx = 4;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        searchPanel.add(filterButton, constraints);
        return searchPanel;
    }

    private JPanel statusLinePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.gridx = 0;
        constraints.weightx = 0;
        panel.add(contextLabel, constraints);

        constraints.gridx = 1;
        constraints.insets = new Insets(0, 48, 0, 0);
        panel.add(countLabel, constraints);

        constraints.gridx = 2;
        constraints.insets = new Insets(0, 0, 0, 0);
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JPanel(), constraints);
        return panel;
    }

    private void configureShrinkableTextFields() {
        allowHorizontalShrink(queryField);
        allowHorizontalShrink(negativeFilterField);
        allowHorizontalShrink(showExtensionField);
        allowHorizontalShrink(hideExtensionField);
    }

    private static void allowHorizontalShrink(JTextField field) {
        Dimension minimum = field.getMinimumSize();
        field.setMinimumSize(new Dimension(0, minimum.height));
    }

    private JPanel locationsPanel() {
        return checkboxGridGroup(
                "Locations",
                new JCheckBox[]{requestHeadersCheck, requestBodyCheck},
                new JCheckBox[]{responseHeadersCheck, responseBodyCheck}
        );
    }

    private JPanel checkboxGridGroup(String title, JCheckBox[]... rows) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        JPanel grid = new JPanel(new GridBagLayout());
        int columns = maxColumns(rows);
        int[] columnWidths = checkboxColumnWidths(columns, rows);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.NONE;
        constraints.weightx = 0;
        constraints.weighty = 0;

        for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
            JCheckBox[] row = rows[rowIndex];
            for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
                constraints.gridx = columnIndex;
                constraints.gridy = rowIndex;
                constraints.insets = new Insets(0, 0, rowIndex == rows.length - 1 ? 0 : 4, columnIndex == columns - 1 ? 0 : 8);
                if (columnIndex < row.length) {
                    grid.add(fixedCheckboxCell(row[columnIndex], columnWidths[columnIndex]), constraints);
                } else {
                    grid.add(fixedEmptyCell(columnWidths[columnIndex]), constraints);
                }
            }
        }

        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = columns;
        filler.gridy = 0;
        filler.gridheight = Math.max(1, rows.length);
        filler.weightx = 1;
        filler.fill = GridBagConstraints.HORIZONTAL;
        grid.add(new JPanel(), filler);

        panel.add(grid, BorderLayout.NORTH);
        return panel;
    }

    private JPanel toolsPanel() {
        return checkboxGridGroup(
                "Tools",
                new JCheckBox[]{targetSourceCheck, proxySourceCheck},
                new JCheckBox[]{repeaterSourceCheck, organizerSourceCheck}
        );
    }

    private JPanel statusPanel() {
        return checkboxGridGroup(
                "Filter by status code",
                new JCheckBox[]{status2xxCheck, status3xxCheck},
                new JCheckBox[]{status4xxCheck, status5xxCheck}
        );
    }

    private JPanel mimePanel() {
        return checkboxGridGroup(
                "Filter by MIME type",
                new JCheckBox[]{
                        mimeChecks.get(MimeCategory.HTML),
                        mimeChecks.get(MimeCategory.JAVASCRIPT),
                        mimeChecks.get(MimeCategory.CSS),
                        mimeChecks.get(MimeCategory.IMAGE)
                },
                new JCheckBox[]{
                        mimeChecks.get(MimeCategory.JSON),
                        mimeChecks.get(MimeCategory.TEXT),
                        mimeChecks.get(MimeCategory.OTHER)
                }
        );
    }

    private JPanel negativeMatchPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Negative match"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 1;
        panel.add(negativeFilterField, constraints);
        constraints.gridx = 1;
        constraints.weightx = 0;
        panel.add(negativeApplyButton, constraints);
        constraints.gridx = 2;
        panel.add(negativeAutoCheck, constraints);
        return panel;
    }

    private int maxColumns(JCheckBox[][] rows) {
        int columns = 0;
        for (JCheckBox[] row : rows) {
            columns = Math.max(columns, row.length);
        }
        return columns;
    }

    private int[] checkboxColumnWidths(int columns, JCheckBox[][] rows) {
        int[] widths = new int[columns];
        for (JCheckBox[] row : rows) {
            for (int columnIndex = 0; columnIndex < row.length; columnIndex++) {
                widths[columnIndex] = Math.max(widths[columnIndex], row[columnIndex].getPreferredSize().width);
            }
        }
        return widths;
    }

    private JPanel fixedCheckboxCell(JCheckBox checkbox, int width) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(checkbox, BorderLayout.WEST);
        panel.setPreferredSize(new Dimension(width, checkbox.getPreferredSize().height));
        return panel;
    }

    private JPanel fixedEmptyCell(int width) {
        JPanel panel = new JPanel();
        panel.setPreferredSize(new Dimension(width, 1));
        return panel;
    }

    private JPanel extensionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Filter by file extension"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 0;
        panel.add(showExtensionCheck, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(showExtensionField, constraints);
        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.weightx = 0;
        panel.add(hideExtensionCheck, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(hideExtensionField, constraints);
        return panel;
    }

    private void initializeMimeChecks() {
        if (!mimeChecks.isEmpty()) {
            return;
        }
        for (MimeCategory category : VISIBLE_MIME_CATEGORIES) {
            boolean selectedByDefault = category != MimeCategory.CSS && category != MimeCategory.IMAGE;
            mimeChecks.put(category, new JCheckBox(mimeCheckboxLabel(category), selectedByDefault));
        }
    }

    private void toggleInlineFilters() {
        boolean showFilters = inlineFilterPanels.stream().noneMatch(JPanel::isVisible);
        for (JPanel panel : inlineFilterPanels) {
            panel.setVisible(showFilters);
        }
        filterButton.setToolTipText(showFilters ? "Hide filters" : "Show filters");
        revalidate();
        repaint();
    }

    private void configureFilterControls() {
        negativeApplyButton.addActionListener(event -> applyNegativeFilter());
        negativeFilterField.addActionListener(event -> applyNegativeFilter());
        negativeAutoCheck.addActionListener(event -> {
            if (negativeAutoCheck.isSelected()) {
                applyNegativeFilter();
            }
        });
        negativeFilterField.getDocument().addDocumentListener(negativeFilterDocumentListener());

        showExtensionField.setEnabled(false);
        hideExtensionField.setEnabled(false);
        showExtensionCheck.addActionListener(event -> {
            showExtensionField.setEnabled(showExtensionCheck.isSelected());
            if (showExtensionCheck.isSelected()) {
                showExtensionField.requestFocusInWindow();
            }
            applyCurrentFilters();
        });
        hideExtensionCheck.addActionListener(event -> {
            hideExtensionField.setEnabled(hideExtensionCheck.isSelected());
            if (hideExtensionCheck.isSelected()) {
                hideExtensionField.requestFocusInWindow();
            }
            applyCurrentFilters();
        });

        showExtensionField.getDocument().addDocumentListener(filterDocumentListener());
        hideExtensionField.getDocument().addDocumentListener(filterDocumentListener());
        for (JCheckBox checkbox : mimeChecks.values()) {
            checkbox.addActionListener(event -> applyCurrentFilters());
        }
        status2xxCheck.addActionListener(event -> applyCurrentFilters());
        status3xxCheck.addActionListener(event -> applyCurrentFilters());
        status4xxCheck.addActionListener(event -> applyCurrentFilters());
        status5xxCheck.addActionListener(event -> applyCurrentFilters());
    }

    private DocumentListener filterDocumentListener() {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyCurrentFilters();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyCurrentFilters();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyCurrentFilters();
            }
        };
    }

    private DocumentListener negativeFilterDocumentListener() {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyIfAuto();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyIfAuto();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyIfAuto();
            }

            private void applyIfAuto() {
                if (negativeAutoCheck.isSelected()) {
                    applyNegativeFilter();
                }
            }
        };
    }

    private void applyNegativeFilter() {
        activeNegativeFilter = negativeFilterField.getText();
        applyCurrentFilters();
    }

    private String mimeCheckboxLabel(MimeCategory category) {
        return switch (category) {
            case HTML -> "Html";
            case JAVASCRIPT -> "Script";
            case CSS -> "Css";
            case IMAGE -> "Image";
            case JSON -> "Json";
            case TEXT -> "Text";
            case OTHER -> "Other";
            default -> category.label();
        };
    }

    private JSplitPane resultsAndPreviewPanel() {
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setContinuousLayout(true);
        splitPane.setResizeWeight(RESULTS_TABLE_INITIAL_RATIO);

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setMinimumSize(new Dimension(0, RESULTS_TABLE_MIN_HEIGHT));
        splitPane.setTopComponent(tableScroll);

        JSplitPane requestResponseSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        requestResponseSplit.setContinuousLayout(true);
        requestResponseSplit.setResizeWeight(0.5);
        requestResponseSplit.setMinimumSize(new Dimension(0, PREVIEW_MIN_HEIGHT));

        JPanel requestPanel = titledPanel("Request", requestEditor.uiComponent());
        JPanel responsePanel = titledPanel("Response", responseHolder);
        requestPanel.setMinimumSize(new Dimension(0, PREVIEW_MIN_HEIGHT));
        responsePanel.setMinimumSize(new Dimension(0, PREVIEW_MIN_HEIGHT));
        requestResponseSplit.setLeftComponent(requestPanel);
        requestResponseSplit.setRightComponent(responsePanel);
        splitPane.setBottomComponent(requestResponseSplit);

        SwingUtilities.invokeLater(() -> {
            splitPane.setDividerLocation(RESULTS_TABLE_INITIAL_RATIO);
            requestResponseSplit.setDividerLocation(0.5);
        });
        return splitPane;
    }

    private JPanel titledPanel(String title, java.awt.Component component) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        panel.add(new JLabel(title), BorderLayout.NORTH);
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private void configureTable() {
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updatePreview();
            }
        });
        table.setComponentPopupMenu(contextMenu());
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.getColumnModel().getColumn(3).setPreferredWidth(520);
        table.getColumnModel().getColumn(4).setPreferredWidth(70);
        table.getColumnModel().getColumn(5).setPreferredWidth(90);
        table.getColumnModel().getColumn(6).setPreferredWidth(80);
        table.getColumnModel().getColumn(7).setPreferredWidth(150);
    }

    private JPopupMenu contextMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem extractSelected = new JMenuItem("Extract selected");
        extractSelected.addActionListener(event -> extractSelected());
        JMenuItem extractAll = new JMenuItem("Extract all");
        extractAll.addActionListener(event -> extractAll());
        JMenuItem copyUrl = new JMenuItem("Copy URL");
        copyUrl.addActionListener(event -> copySelectedUrl());
        JMenuItem sendRepeater = new JMenuItem("Send to Repeater");
        sendRepeater.addActionListener(event -> sendSelectedToRepeater());
        menu.add(extractSelected);
        menu.add(extractAll);
        menu.add(copyUrl);
        menu.add(sendRepeater);
        return menu;
    }

    private void runSearch() {
        SearchOptions options;
        try {
            options = buildQueryOptions();
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, "Invalid search: " + exception.getMessage(), "Search++", JOptionPane.ERROR_MESSAGE);
            return;
        }

        cancelRunningSearch();
        tableModel.setRowCount(0);
        allResults = new ArrayList<>();
        currentResults = new ArrayList<>();
        clearPreview();
        countLabel.setText("Searching... 0 results");

        SwingWorker<Void, SearchResult> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                for (HttpExchange exchange : collectSources(options)) {
                    if (isCancelled()) {
                        break;
                    }
                    try {
                        if (searchEngine.matches(exchange, options)) {
                            publish(searchEngine.toResult(exchange));
                        }
                    } catch (RuntimeException exception) {
                        logSearchWarning("Skipped malformed search item", exception);
                    }
                }
                return null;
            }

            @Override
            protected void process(List<SearchResult> chunks) {
                if (currentWorker == this) {
                    allResults.addAll(chunks);
                    appendResults(filterResults(chunks));
                }
            }

            @Override
            protected void done() {
                if (currentWorker != this) {
                    return;
                }
                boolean cancelled = isCancelled();
                try {
                    get();
                } catch (CancellationException exception) {
                    cancelled = true;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    JOptionPane.showMessageDialog(SearchPlusDialog.this,
                            "Search failed: " + cause.getMessage(),
                            "Search++",
                            JOptionPane.ERROR_MESSAGE);
                } finally {
                    currentWorker = null;
                    if (currentResults.isEmpty()) {
                        clearPreview();
                    }
                    countLabel.setText(currentResults.size() + " results" + (cancelled ? " (cancelled)" : ""));
                }
            }
        };
        currentWorker = worker;
        worker.execute();
    }

    private SearchOptions buildQueryOptions() {
        return new SearchOptions(
                queryField.getText(),
                (SearchMode) modeCombo.getSelectedItem(),
                regexCheck.isSelected(),
                caseCheck.isSelected(),
                false,
                requestHeadersCheck.isSelected(),
                requestBodyCheck.isSelected(),
                responseHeadersCheck.isSelected(),
                responseBodyCheck.isSelected(),
                targetSourceCheck.isSelected(),
                proxySourceCheck.isSelected(),
                repeaterSourceCheck.isSelected(),
                organizerSourceCheck.isSelected(),
                true,
                Set.of(),
                false,
                Set.of(),
                Set.of(),
                Set.of()
        );
    }

    private SearchOptions buildFilterOptions() {
        return new SearchOptions(
                "",
                SearchMode.TEXT,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                statusPatterns(),
                true,
                mimeCategories(),
                showExtensions(),
                hideExtensions()
        );
    }

    private SearchOptions buildNegativeFilterOptions() {
        return new SearchOptions(
                activeNegativeFilter,
                (SearchMode) modeCombo.getSelectedItem(),
                regexCheck.isSelected(),
                caseCheck.isSelected(),
                false,
                requestHeadersCheck.isSelected(),
                requestBodyCheck.isSelected(),
                responseHeadersCheck.isSelected(),
                responseBodyCheck.isSelected(),
                false,
                false,
                false,
                false,
                true,
                Set.of(),
                false,
                Set.of(),
                Set.of(),
                Set.of()
        );
    }

    private List<SearchResult> filterResults(List<SearchResult> results) {
        SearchOptions filterOptions = buildFilterOptions();
        SearchOptions negativeOptions = buildNegativeFilterOptions();
        boolean hideNegativeMatches = !negativeOptions.query().isBlank();
        return results.stream()
                .filter(result -> searchEngine.matchesFilters(result.exchange(), filterOptions))
                .filter(result -> !hideNegativeMatches || !searchEngine.matches(result.exchange(), negativeOptions))
                .toList();
    }

    private void applyCurrentFilters() {
        tableModel.setRowCount(0);
        currentResults = new ArrayList<>();
        appendResults(filterResults(allResults));
        if (currentResults.isEmpty()) {
            clearPreview();
        }
        countLabel.setText(currentResults.size() + " results");
    }

    private Set<String> statusPatterns() {
        Set<String> selected = new java.util.TreeSet<>();
        if (status2xxCheck.isSelected()) {
            selected.add("2xx");
        }
        if (status3xxCheck.isSelected()) {
            selected.add("3xx");
        }
        if (status4xxCheck.isSelected()) {
            selected.add("4xx");
        }
        if (status5xxCheck.isSelected()) {
            selected.add("5xx");
        }
        return selected;
    }

    private Set<MimeCategory> mimeCategories() {
        Set<MimeCategory> selected = EnumSet.noneOf(MimeCategory.class);
        for (Map.Entry<MimeCategory, JCheckBox> entry : mimeChecks.entrySet()) {
            if (entry.getValue().isSelected()) {
                selected.add(entry.getKey());
            }
        }
        if (selected.isEmpty()) {
            return Set.of();
        }
        if (selected.contains(MimeCategory.OTHER)) {
            selected.add(MimeCategory.FONT);
            selected.add(MimeCategory.ARCHIVE);
        }
        return selected;
    }

    private Set<String> showExtensions() {
        return showExtensionCheck.isSelected()
                ? ExtensionFilters.parseExtensions(showExtensionField.getText())
                : Set.of();
    }

    private Set<String> hideExtensions() {
        return hideExtensionCheck.isSelected()
                ? ExtensionFilters.parseExtensions(hideExtensionField.getText())
                : Set.of();
    }

    private List<HttpExchange> collectSources(SearchOptions options) {
        Map<String, HttpExchange> deduped = new LinkedHashMap<>();
        addAll(deduped, contextExchanges);
        if (options.includeTarget()) {
            addSource(deduped, "Target", () -> HttpExchangeFactory.fromRequestResponses("Target", api.siteMap().requestResponses()));
        }
        if (options.includeProxy()) {
            addSource(deduped, "Proxy", () -> HttpExchangeFactory.fromProxyHistory(api.proxy().history()));
        }
        if (options.includeRepeater() && repeaterCache != null) {
            addSource(deduped, "Repeater", () -> HttpExchangeFactory.fromRequestResponses("Repeater", repeaterCache.snapshot()));
        }
        if (options.includeOrganizer()) {
            addSource(deduped, "Organizer", () -> HttpExchangeFactory.fromOrganizerItems(api.organizer().items()));
        }
        return new ArrayList<>(deduped.values());
    }

    private void addSource(Map<String, HttpExchange> deduped, String source, Supplier<List<HttpExchange>> supplier) {
        try {
            addAll(deduped, supplier.get());
        } catch (RuntimeException exception) {
            logSearchWarning("Unable to read Search++ source " + source, exception);
        }
    }

    private void addAll(Map<String, HttpExchange> deduped, List<HttpExchange> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return;
        }
        for (HttpExchange exchange : exchanges) {
            try {
                if (!matchesContextScope(exchange)) {
                    continue;
                }
                deduped.putIfAbsent(exchange.source() + " " + exchange.method() + " " + exchange.url(), exchange);
            } catch (RuntimeException exception) {
                logSearchWarning("Skipped malformed Search++ item", exception);
            }
        }
    }

    private boolean matchesContextScope(HttpExchange exchange) {
        if (contextScopes.isEmpty()) {
            return true;
        }
        for (SelectionScope scope : contextScopes) {
            try {
                if (scope.matchesUrl(exchange.url())) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    private void appendResults(List<SearchResult> results) {
        boolean selectFirstResult = currentResults.isEmpty() && table.getSelectedRow() < 0;
        for (SearchResult result : results) {
            currentResults.add(result);
            HttpExchange exchange = result.exchange();
            tableModel.addRow(new Object[]{
                    exchange.source(),
                    exchange.host(),
                    exchange.method(),
                    exchange.url(),
                    exchange.statusCode() < 0 ? "" : exchange.statusCode(),
                    result.mime(),
                    result.length(),
                    exchange.time() == null ? "" : TIME_FORMAT.format(exchange.time())
            });
        }
        countLabel.setText(currentResults.size() + " results");
        if (selectFirstResult && !currentResults.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private void updatePreview() {
        SearchResult result = selectedResult();
        if (result == null) {
            clearPreview();
            return;
        }

        HttpRequestResponse requestResponse = result.exchange().requestResponse();
        try {
            requestEditor.setRequest(requestResponse.request());
            responseHolder.removeAll();
            if (requestResponse.hasResponse() && requestResponse.response() != null) {
                responseEditor.setResponse(requestResponse.response());
                responseHolder.add(responseEditor.uiComponent(), BorderLayout.CENTER);
            } else {
                responseHolder.add(new JLabel("No response."), BorderLayout.CENTER);
            }
            responseHolder.revalidate();
            responseHolder.repaint();
        } catch (RuntimeException exception) {
            clearPreview();
        }
    }

    private void clearPreview() {
        responseHolder.removeAll();
        responseHolder.add(new JLabel("No response selected."), BorderLayout.CENTER);
        responseHolder.revalidate();
        responseHolder.repaint();
    }

    private SearchResult selectedResult() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return null;
        }
        int modelRow = table.convertRowIndexToModel(row);
        return modelRow >= 0 && modelRow < currentResults.size() ? currentResults.get(modelRow) : null;
    }

    private List<HttpRequestResponse> selectedRequestResponses() {
        int[] rows = table.getSelectedRows();
        List<HttpRequestResponse> selected = new ArrayList<>();
        for (int row : rows) {
            int modelRow = table.convertRowIndexToModel(row);
            if (modelRow >= 0 && modelRow < currentResults.size()) {
                selected.add(currentResults.get(modelRow).exchange().requestResponse());
            }
        }
        return selected;
    }

    private List<HttpRequestResponse> allRequestResponses() {
        return currentResults.stream()
                .map(result -> result.exchange().requestResponse())
                .toList();
    }

    private void extractSelected() {
        extract(selectedRequestResponses());
    }

    private void extractAll() {
        extract(allRequestResponses());
    }

    private void extract(List<HttpRequestResponse> requestResponses) {
        if (requestResponses.isEmpty()) {
            return;
        }
        if (extractHandler != null && extractHandler.test(requestResponses)) {
            countLabel.setText(currentResults.size() + " results");
        }
    }

    private void logSearchWarning(String message, RuntimeException exception) {
        try {
            api.logging().logToError(message + ": " + exception);
        } catch (RuntimeException ignored) {
            // Logging is best-effort only.
        }
    }

    private void cancelRunningSearch() {
        SwingWorker<Void, SearchResult> worker = currentWorker;
        currentWorker = null;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
    }

    private void closeOwnedWindows() {
        cancelRunningSearch();
    }

    private void copySelectedUrl() {
        SearchResult result = selectedResult();
        if (result != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(result.exchange().url()), null);
        }
    }

    private void sendSelectedToRepeater() {
        SearchResult result = selectedResult();
        if (result != null) {
            api.repeater().sendToRepeater(result.exchange().requestResponse().request());
            if (repeaterCache != null) {
                repeaterCache.add(result.exchange().requestResponse());
            }
        }
    }

    private record ControlCell(int row, int column, int span) {
        private ControlCell {
            if (row < 0 || column < 0 || column >= CONTROL_GRID_COLUMNS || span <= 0 || column + span > CONTROL_GRID_COLUMNS) {
                throw new IllegalArgumentException("Invalid Search++ control grid cell");
            }
        }
    }

    private static final class ControlGridLayout implements LayoutManager2 {
        private final Map<Component, ControlCell> cells = new LinkedHashMap<>();

        @Override
        public void addLayoutComponent(Component component, Object constraints) {
            if (constraints instanceof ControlCell cell) {
                cells.put(component, cell);
                return;
            }
            throw new IllegalArgumentException("Search++ control grid requires ControlCell constraints");
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
            cells.put(component, new ControlCell(0, 0, 1));
        }

        @Override
        public void removeLayoutComponent(Component component) {
            cells.remove(component);
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return layoutSize(parent, false);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return layoutSize(parent, true);
        }

        @Override
        public Dimension maximumLayoutSize(Container target) {
            return new Dimension(Integer.MAX_VALUE, layoutSize(target, false).height);
        }

        @Override
        public float getLayoutAlignmentX(Container target) {
            return 0.5F;
        }

        @Override
        public float getLayoutAlignmentY(Container target) {
            return 0.0F;
        }

        @Override
        public void invalidateLayout(Container target) {
            // Bounds are recalculated from current container width on every layout pass.
        }

        @Override
        public void layoutContainer(Container parent) {
            synchronized (parent.getTreeLock()) {
                Insets insets = parent.getInsets();
                int contentWidth = Math.max(0, parent.getWidth() - insets.left - insets.right);
                int gap = contentWidth >= CONTROL_GRID_GAP * (CONTROL_GRID_COLUMNS - 1) ? CONTROL_GRID_GAP : 0;
                int[] widths = columnWidths(contentWidth, gap);
                int[] xs = columnXs(insets.left, widths, gap);
                int topHeight = rowHeight(0, false);
                int filterHeight = rowHeight(1, false);
                int topY = insets.top;
                int filterY = topY + topHeight + (filterHeight > 0 ? CONTROL_GRID_ROW_GAP : 0);

                for (Map.Entry<Component, ControlCell> entry : cells.entrySet()) {
                    Component component = entry.getKey();
                    ControlCell cell = entry.getValue();
                    if (!component.isVisible()) {
                        component.setBounds(0, 0, 0, 0);
                        continue;
                    }
                    int x = xs[cell.column()];
                    int y = cell.row() == 0 ? topY : filterY;
                    int width = cellWidth(widths, gap, cell.column(), cell.span());
                    int height = cell.row() == 0 ? topHeight : filterHeight;
                    component.setBounds(x, y, Math.max(0, width), Math.max(0, height));
                }
            }
        }

        private Dimension layoutSize(Container parent, boolean minimum) {
            synchronized (parent.getTreeLock()) {
                Insets insets = parent.getInsets();
                int topHeight = rowHeight(0, minimum);
                int filterHeight = rowHeight(1, minimum);
                int height = topHeight + (filterHeight > 0 ? CONTROL_GRID_ROW_GAP + filterHeight : 0);
                return new Dimension(
                        CONTROL_GRID_MIN_WIDTH + insets.left + insets.right,
                        height + insets.top + insets.bottom
                );
            }
        }

        private int rowHeight(int row, boolean minimum) {
            int height = 0;
            for (Map.Entry<Component, ControlCell> entry : cells.entrySet()) {
                Component component = entry.getKey();
                ControlCell cell = entry.getValue();
                if (cell.row() == row && component.isVisible()) {
                    Dimension size = minimum ? component.getMinimumSize() : component.getPreferredSize();
                    height = Math.max(height, size.height);
                }
            }
            return height;
        }

        private int[] columnWidths(int contentWidth, int gap) {
            int totalGap = gap * (CONTROL_GRID_COLUMNS - 1);
            int usableWidth = Math.max(0, contentWidth - totalGap);
            int baseWidth = usableWidth / CONTROL_GRID_COLUMNS;
            int remainder = usableWidth % CONTROL_GRID_COLUMNS;
            int[] widths = new int[CONTROL_GRID_COLUMNS];
            for (int index = 0; index < CONTROL_GRID_COLUMNS; index++) {
                widths[index] = baseWidth + (index < remainder ? 1 : 0);
            }
            return widths;
        }

        private int[] columnXs(int startX, int[] widths, int gap) {
            int[] xs = new int[CONTROL_GRID_COLUMNS];
            int x = startX;
            for (int index = 0; index < CONTROL_GRID_COLUMNS; index++) {
                xs[index] = x;
                x += widths[index] + gap;
            }
            return xs;
        }

        private int cellWidth(int[] widths, int gap, int column, int span) {
            int width = 0;
            for (int index = column; index < column + span; index++) {
                width += widths[index];
            }
            return width + gap * (span - 1);
        }
    }

    private static final class SearchIcon implements Icon {
        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(65, 65, 65));
                g.setStroke(new BasicStroke(2.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawOval(x + 2, y + 2, 8, 8);
                g.drawLine(x + 9, y + 9, x + 14, y + 14);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class FilterIcon implements Icon {
        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(65, 65, 65));
                int[] xs = {x + 2, x + 14, x + 9, x + 9, x + 7, x + 7};
                int[] ys = {y + 3, y + 3, y + 8, y + 13, y + 13, y + 8};
                g.fillPolygon(xs, ys, xs.length);
            } finally {
                g.dispose();
            }
        }
    }
}
