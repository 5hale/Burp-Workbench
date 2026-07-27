package com.burpworkbench.modules.search;

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
import javax.swing.Timer;
import javax.swing.JToggleButton;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class SearchPlusDialog extends JFrame {
    private static final Dimension DIALOG_PREFERRED_SIZE = new Dimension(1450, 900);
    private static final Dimension DIALOG_MINIMUM_SIZE = new Dimension(1120, 650);
    private static final double RESULTS_TABLE_INITIAL_RATIO = 0.56;
    private static final int RESULTS_TABLE_MIN_HEIGHT = 180;
    private static final int PREVIEW_MIN_HEIGHT = 320;
    private static final int FILTER_DEBOUNCE_MILLIS = 200;
    private static final String PLUS_TAB_TITLE = "+";
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
    private final RepeaterCache repeaterCache;
    private final Predicate<List<HttpRequestResponse>> extractHandler;
    private final SearchEngine searchEngine = new SearchEngine();
    private final SearchSourceScanner sourceScanner;
    private final SearchExecutionCoordinator executionCoordinator;
    private final SearchRunController runController = new SearchRunController();
    private final SearchOptionsMapper optionsMapper = new SearchOptionsMapper();
    private final SearchContextPolicy contextPolicy;
    private final JPanel searchTabs =
            new JPanel(SearchUiSupport.wrapFlowLayout(FlowLayout.LEFT, 4, 2));
    private final List<SearchPlusTabState> tabStates = new ArrayList<>();
    private final JTextField queryField = new JTextField(24);
    private final JButton searchButton = iconButton(SearchUiSupport.searchIcon(), "Search");
    private final JComboBox<SearchMode> modeCombo = new JComboBox<>(SearchMode.values());
    private final JToggleButton regexCheck = textToggleButton(".*", "Regex");
    private final JToggleButton caseCheck = textToggleButton("Cc", "Case sensitive");
    private final JTextField negativeFilterField = new JTextField(18);
    private final JButton negativeApplyButton =
            iconButton(SearchUiSupport.searchIcon(), "Apply negative match filter");
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
    private final JButton filterButton = iconButton(SearchUiSupport.filterIcon(), "Show filters");
    private final List<JPanel> inlineFilterPanels = new ArrayList<>();
    private final Map<MimeCategory, JCheckBox> mimeChecks = new EnumMap<>(MimeCategory.class);
    private final SearchResultTableModel tableModel = new SearchResultTableModel();
    private final JTable table = new JTable(tableModel);
    private final JLabel countLabel = new JLabel("0 results");
    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;
    private final Timer filterDebounceTimer;
    private final JPanel responseHolder = new JPanel(new BorderLayout());
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "burp-workbench-search");
        thread.setDaemon(true);
        return thread;
    });
    private final ThreadPoolExecutor filterExecutor = SearchFilterExecutor.create(runnable -> {
        Thread thread = new Thread(runnable, "burp-workbench-search-filter");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private String activeNegativeFilter = "";
    private volatile SwingWorker<Void, SearchResultUpdate> currentWorker;
    private volatile SearchFilterPlan liveFilterPlan;
    private SearchRunUiState activeSearchRunState;
    private volatile Future<?> currentFilterTask;
    private long nextFilterVersion = 1;
    private boolean filterRefreshInProgress;
    private long filterRefreshVersion;
    private final SearchResultView.IndexBuffer pendingVisibleIndices =
            new SearchResultView.IndexBuffer();
    private int pendingFilterRegexTimeoutItems;
    private int activeFilterRegexTimeoutItems;
    private int activeTabIndex = -1;
    private int nextTabNumber = 1;
    private long nextSearchRunId = 1;
    private boolean lastSearchCancelled;
    private boolean lastSearchFailed;
    private int lastSkippedResults;
    private int lastMalformedItems;
    private int lastScanRegexTimeoutItems;
    private int lastRegexTimeoutItems;
    private boolean restoringTabState;
    private boolean extensionFilterRefreshPending;
    private boolean negativeFilterRefreshPending;

    private SearchPlusDialog(
            Window locationOwner,
            MontoyaApi api,
            List<HttpRequestResponse> contextItems,
            List<SelectionScope> contextScopes,
            RepeaterCache repeaterCache,
            Predicate<List<HttpRequestResponse>> extractHandler,
            SearchExecutionCoordinator executionCoordinator
    ) {
        super("Search++");
        this.api = api;
        List<HttpRequestResponse> safeContextItems = contextItems == null ? List.of() : List.copyOf(contextItems);
        List<HttpExchange> contextExchanges =
                HttpExchangeFactory.fromRequestResponses("Context", safeContextItems);
        List<SelectionScope> safeContextScopes =
                contextScopes == null ? List.of() : List.copyOf(contextScopes);
        this.repeaterCache = repeaterCache;
        this.extractHandler = extractHandler;
        this.executionCoordinator = Objects.requireNonNull(
                executionCoordinator,
                "executionCoordinator"
        );
        this.sourceScanner = new SearchSourceScanner(
                api,
                contextExchanges,
                safeContextScopes,
                repeaterCache
        );
        this.contextPolicy = SearchContextPolicy.from(
                contextExchanges.size(),
                safeContextScopes.size()
        );
        this.requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        this.responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        this.filterDebounceTimer = new Timer(
                FILTER_DEBOUNCE_MILLIS,
                event -> flushScheduledFilterRefresh()
        );
        this.filterDebounceTimer.setRepeats(false);

        configureContextSourceDefaults();
        initializeMimeChecks();
        initializeSearchTabs();
        configureShrinkableTextFields();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(topPanel(), BorderLayout.NORTH);
        add(resultsAndPreviewPanel(), BorderLayout.CENTER);

        contextLabel.setText(contextPolicy.label());
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
        clearPreview();

        setPreferredSize(DIALOG_PREFERRED_SIZE);
        setMinimumSize(DIALOG_MINIMUM_SIZE);
        pack();
        setAlwaysOnTop(false);
        setLocationRelativeTo(locationOwner);
    }

    static void open(
            MontoyaApi api,
            List<HttpRequestResponse> contextItems,
            List<SelectionScope> contextScopes,
            RepeaterCache repeaterCache,
            Predicate<List<HttpRequestResponse>> extractHandler,
            SearchExecutionCoordinator executionCoordinator,
            Consumer<SearchPlusDialog> openedDialog
    ) {
        SwingUtilities.invokeLater(() -> {
            Window locationOwner = api.userInterface().swingUtils().suiteFrame();
            SearchPlusDialog dialog = new SearchPlusDialog(
                    locationOwner,
                    api,
                    contextItems,
                    contextScopes,
                    repeaterCache,
                    extractHandler,
                    executionCoordinator
            );
            openedDialog.accept(dialog);
            if (!dialog.isDisplayable()) {
                return;
            }
            dialog.setVisible(true);
            dialog.toFront();
            dialog.requestFocus();
            dialog.focusSearchFieldLater();
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
        panel.add(tabBarPanel(), BorderLayout.NORTH);
        panel.add(controlGridPanel(), BorderLayout.CENTER);
        return panel;
    }

    private JPanel tabBarPanel() {
        searchTabs.setOpaque(false);
        searchTabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                maybeShowTabMenu(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                maybeShowTabMenu(event);
            }
        });

        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.add(searchTabs, BorderLayout.CENTER);
        return panel;
    }

    private void initializeSearchTabs() {
        SearchPlusTabState state = newSearchTabState(nextTabNumber++);
        captureCurrentUiState(state);
        tabStates.add(state);
        activeTabIndex = 0;
        tableModel.bind(state.visibleResults);
        refreshTabBar();
    }

    private void createSearchTab() {
        preserveScheduledFilterRefreshForActiveTab();
        saveActiveTabState();
        cancelRunningSearch();
        SearchPlusTabState state = newSearchTabState(nextTabNumber++);
        tabStates.add(state);
        int index = tabStates.size() - 1;
        activeTabIndex = index;
        refreshTabBar();
        restoreTabState(state);
        focusSearchFieldLater();
    }

    private void selectSearchTab(int index) {
        if (!isRealTabIndex(index) || index == activeTabIndex) {
            return;
        }
        preserveScheduledFilterRefreshForActiveTab();
        saveActiveTabState();
        cancelRunningSearch();
        activeTabIndex = index;
        refreshTabBar();
        restoreTabState(tabStates.get(index));
        refreshFiltersIfDirty(tabStates.get(index));
        focusSearchFieldLater();
    }

    private void closeTabAt(int index) {
        if (!isRealTabIndex(index)) {
            return;
        }
        if (tabStates.size() == 1) {
            cancelScheduledFilterRefresh();
            cancelRunningSearch();
            SearchPlusTabState resetState = newSearchTabState(1);
            tabStates.set(0, resetState);
            activeTabIndex = 0;
            refreshTabBar();
            restoreTabState(resetState);
            focusSearchFieldLater();
            return;
        }

        if (index == activeTabIndex) {
            preserveScheduledFilterRefreshForActiveTab();
            cancelRunningSearch();
        } else {
            saveActiveTabState();
        }

        tabStates.remove(index);
        refreshTabBar();

        if (index < activeTabIndex) {
            activeTabIndex--;
            refreshTabBar();
            return;
        }
        if (index == activeTabIndex) {
            activeTabIndex = -1;
            int nextIndex = Math.min(index, tabStates.size() - 1);
            selectSearchTab(nextIndex);
        }
    }

    private void configureContextSourceDefaults() {
        SourceSelection defaults = contextPolicy.sourceSelection();
        targetSourceCheck.setSelected(defaults.target());
        proxySourceCheck.setSelected(defaults.proxy());
        repeaterSourceCheck.setSelected(defaults.repeater());
        organizerSourceCheck.setSelected(defaults.organizer());
    }

    private SearchPlusTabState newSearchTabState(int number) {
        SearchPlusTabState state = SearchPlusTabState.initial(number);
        SourceSelection defaults = contextPolicy.sourceSelection();
        state.includeTarget = defaults.target();
        state.includeProxy = defaults.proxy();
        state.includeRepeater = defaults.repeater();
        state.includeOrganizer = defaults.organizer();
        return state;
    }

    private void maybeShowTabMenu(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem newItem = new JMenuItem("New");
        newItem.addActionListener(action -> createSearchTab());
        menu.add(newItem);
        menu.show(searchTabs, event.getX(), event.getY());
    }

    private void refreshTabBar() {
        searchTabs.removeAll();
        for (int index = 0; index < tabStates.size(); index++) {
            searchTabs.add(tabComponent(index));
        }
        searchTabs.add(plusTabComponent());
        searchTabs.revalidate();
        searchTabs.repaint();
    }

    private boolean isRealTabIndex(int index) {
        return index >= 0 && index < tabStates.size();
    }

    private JPanel plusTabComponent() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        panel.setOpaque(true);
        panel.setBackground(searchTabs.getBackground());
        JLabel plus = new JLabel(PLUS_TAB_TITLE);
        plus.setFont(plus.getFont().deriveFont(22f));
        plus.setHorizontalAlignment(JLabel.CENTER);
        plus.setPreferredSize(new Dimension(28, 24));
        plus.setToolTipText("New search tab");
        plus.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    createSearchTab();
                }
            }
        });
        panel.add(plus);
        return panel;
    }

    private JPanel tabComponent(int index) {
        SearchPlusTabState state = tabStates.get(index);

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        panel.setOpaque(true);
        panel.setBackground(index == activeTabIndex ? new Color(232, 240, 254) : searchTabs.getBackground());
        panel.setBorder(BorderFactory.createLineBorder(index == activeTabIndex ? new Color(100, 140, 220) : new Color(210, 210, 210)));
        panel.setToolTipText("Search tab " + state.title);
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    int tabIndex = tabStates.indexOf(state);
                    if (event.getClickCount() == 2) {
                        startInlineRename(tabIndex);
                    } else {
                        selectSearchTab(tabIndex);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                maybeShowTabMenu(event, tabStates.indexOf(state));
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                maybeShowTabMenu(event, tabStates.indexOf(state));
            }
        });
        JLabel label = new JLabel(state.title);
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    int tabIndex = tabStates.indexOf(state);
                    if (event.getClickCount() == 2) {
                        startInlineRename(tabIndex);
                    } else {
                        selectSearchTab(tabIndex);
                    }
                }
            }
        });
        JButton close = new JButton("x");
        close.setFocusable(false);
        close.setMargin(new Insets(0, 3, 0, 3));
        close.setToolTipText("Close search tab");
        close.addActionListener(event -> closeTabAt(tabStates.indexOf(state)));
        panel.add(label);
        panel.add(close);
        return panel;
    }

    private void maybeShowTabMenu(MouseEvent event, int index) {
        if (!event.isPopupTrigger() || !isRealTabIndex(index)) {
            return;
        }
        selectSearchTab(index);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem renameItem = new JMenuItem("Rename");
        renameItem.addActionListener(action -> startInlineRename(index));
        JMenuItem closeItem = new JMenuItem("Close");
        closeItem.addActionListener(action -> closeTabAt(index));
        menu.add(renameItem);
        menu.add(closeItem);
        menu.show((Component) event.getSource(), event.getX(), event.getY());
    }

    private void startInlineRename(int index) {
        if (!isRealTabIndex(index)) {
            return;
        }
        SearchPlusTabState state = tabStates.get(index);
        JTextField editor = new JTextField(state.title);
        editor.setColumns(Math.max(3, state.title.length() + 1));
        final boolean[] finished = {false};

        Runnable commit = () -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            try {
                state.rename(editor.getText());
            } catch (IllegalArgumentException exception) {
                JOptionPane.showMessageDialog(this, exception.getMessage(), "Search++", JOptionPane.ERROR_MESSAGE);
            }
            refreshTabBar();
        };
        Runnable cancel = () -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            refreshTabBar();
        };

        editor.addActionListener(event -> commit.run());
        editor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    cancel.run();
                }
            }
        });
        editor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                commit.run();
            }
        });

        searchTabs.remove(index);
        searchTabs.add(editor, index);
        searchTabs.revalidate();
        searchTabs.repaint();
        SwingUtilities.invokeLater(() -> {
            editor.requestFocusInWindow();
            editor.selectAll();
        });
    }

    private void saveActiveTabState() {
        if (restoringTabState || activeTabIndex < 0 || activeTabIndex >= tabStates.size()) {
            return;
        }
        captureCurrentUiState(tabStates.get(activeTabIndex));
    }

    private void captureCurrentUiState(SearchPlusTabState state) {
        state.query = queryField.getText();
        state.mode = (SearchMode) modeCombo.getSelectedItem();
        state.regex = regexCheck.isSelected();
        state.caseSensitive = caseCheck.isSelected();
        state.negativeFilter = negativeFilterField.getText();
        state.activeNegativeFilter = activeNegativeFilter;
        state.negativeAuto = negativeAutoCheck.isSelected();
        state.requestHeaders = requestHeadersCheck.isSelected();
        state.requestBody = requestBodyCheck.isSelected();
        state.responseHeaders = responseHeadersCheck.isSelected();
        state.responseBody = responseBodyCheck.isSelected();
        state.includeTarget = targetSourceCheck.isSelected();
        state.includeProxy = proxySourceCheck.isSelected();
        state.includeRepeater = repeaterSourceCheck.isSelected();
        state.includeOrganizer = organizerSourceCheck.isSelected();
        state.status2xx = status2xxCheck.isSelected();
        state.status3xx = status3xxCheck.isSelected();
        state.status4xx = status4xxCheck.isSelected();
        state.status5xx = status5xxCheck.isSelected();
        state.showExtension = showExtensionCheck.isSelected();
        state.showExtensionText = showExtensionField.getText();
        state.hideExtension = hideExtensionCheck.isSelected();
        state.hideExtensionText = hideExtensionField.getText();
        state.mimeSelections.clear();
        for (Map.Entry<MimeCategory, JCheckBox> entry : mimeChecks.entrySet()) {
            state.mimeSelections.put(entry.getKey(), entry.getValue().isSelected());
        }
        state.selectedModelRows = selectedModelRows();
        state.countText = countLabel.getText();
        state.searchCancelled = lastSearchCancelled;
        state.searchFailed = lastSearchFailed;
        state.skippedResults = lastSkippedResults;
        state.malformedItems = lastMalformedItems;
        state.scanRegexTimeoutItems = lastScanRegexTimeoutItems;
        state.regexTimeoutItems = lastRegexTimeoutItems;
    }

    private void restoreTabState(SearchPlusTabState state) {
        restoringTabState = true;
        try {
            queryField.setText(state.query);
            modeCombo.setSelectedItem(state.mode);
            regexCheck.setSelected(state.regex);
            caseCheck.setSelected(state.caseSensitive);
            negativeFilterField.setText(state.negativeFilter);
            activeNegativeFilter = state.activeNegativeFilter;
            negativeAutoCheck.setSelected(state.negativeAuto);
            requestHeadersCheck.setSelected(state.requestHeaders);
            requestBodyCheck.setSelected(state.requestBody);
            responseHeadersCheck.setSelected(state.responseHeaders);
            responseBodyCheck.setSelected(state.responseBody);
            targetSourceCheck.setSelected(state.includeTarget);
            proxySourceCheck.setSelected(state.includeProxy);
            repeaterSourceCheck.setSelected(state.includeRepeater);
            organizerSourceCheck.setSelected(state.includeOrganizer);
            status2xxCheck.setSelected(state.status2xx);
            status3xxCheck.setSelected(state.status3xx);
            status4xxCheck.setSelected(state.status4xx);
            status5xxCheck.setSelected(state.status5xx);
            showExtensionCheck.setSelected(state.showExtension);
            showExtensionField.setText(state.showExtensionText);
            hideExtensionCheck.setSelected(state.hideExtension);
            hideExtensionField.setText(state.hideExtensionText);
            for (Map.Entry<MimeCategory, JCheckBox> entry : mimeChecks.entrySet()) {
                entry.getValue().setSelected(state.mimeSelections.getOrDefault(entry.getKey(), false));
            }
            syncExtensionFields();
            tableModel.bind(state.visibleResults);
            restoreSelectedRows(state.selectedModelRows);
            lastSearchCancelled = state.searchCancelled;
            lastSearchFailed = state.searchFailed;
            lastSkippedResults = state.skippedResults;
            lastMalformedItems = state.malformedItems;
            lastScanRegexTimeoutItems = state.scanRegexTimeoutItems;
            lastRegexTimeoutItems = state.regexTimeoutItems;
            activeFilterRegexTimeoutItems = Math.max(
                    0,
                    state.regexTimeoutItems - state.scanRegexTimeoutItems
            );
            countLabel.setText(state.countText);
            applyPreviewSearchExpression();
        } finally {
            restoringTabState = false;
        }
    }

    private void refreshFiltersIfDirty(SearchPlusTabState state) {
        if (state.filtersDirty
                && isRealTabIndex(activeTabIndex)
                && tabStates.get(activeTabIndex) == state) {
            applyCurrentFilters();
        }
    }

    private int[] selectedModelRows() {
        int[] selectedRows = table.getSelectedRows();
        int[] modelRows = new int[selectedRows.length];
        for (int index = 0; index < selectedRows.length; index++) {
            modelRows[index] = table.convertRowIndexToModel(selectedRows[index]);
        }
        return modelRows;
    }

    private void restoreSelectedRows(int[] modelRows) {
        table.clearSelection();
        if (modelRows == null || modelRows.length == 0) {
            updatePreview();
            return;
        }
        for (int modelRow : modelRows) {
            if (modelRow >= 0 && modelRow < tableModel.getRowCount()) {
                int viewRow = table.convertRowIndexToView(modelRow);
                if (viewRow >= 0) {
                    table.addRowSelectionInterval(viewRow, viewRow);
                }
            }
        }
        updatePreview();
    }

    private JPanel controlGridPanel() {
        inlineFilterPanels.clear();
        JPanel controls = new JPanel(SearchUiSupport.controlGridLayout());
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
        parent.add(child, SearchUiSupport.controlCell(gridy, gridx, gridwidth));
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

        searchButton.addActionListener(event -> handleSearchButton());
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

    private void focusSearchFieldLater() {
        SwingUtilities.invokeLater(() -> {
            queryField.requestFocusInWindow();
            queryField.selectAll();
        });
    }

    private void syncExtensionFields() {
        showExtensionField.setEnabled(showExtensionCheck.isSelected());
        hideExtensionField.setEnabled(hideExtensionCheck.isSelected());
    }

    private static void allowHorizontalShrink(JTextField field) {
        Dimension minimum = field.getMinimumSize();
        field.setMinimumSize(new Dimension(0, minimum.height));
    }

    private JPanel locationsPanel() {
        return checkboxGridGroup(
                "Locations",
                new JCheckBox[]{requestHeadersCheck, responseHeadersCheck},
                new JCheckBox[]{requestBodyCheck, responseBodyCheck}
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
        negativeApplyButton.addActionListener(event -> applyNegativeFilter(true));
        negativeFilterField.addActionListener(event -> applyNegativeFilter(true));
        negativeAutoCheck.addActionListener(event -> {
            if (negativeAutoCheck.isSelected()) {
                applyNegativeFilter(false);
            }
            saveActiveTabState();
        });
        negativeFilterField.getDocument().addDocumentListener(negativeFilterDocumentListener());

        syncExtensionFields();
        showExtensionCheck.addActionListener(event -> {
            syncExtensionFields();
            if (showExtensionCheck.isSelected()) {
                showExtensionField.requestFocusInWindow();
            }
            applyFiltersImmediately();
        });
        hideExtensionCheck.addActionListener(event -> {
            syncExtensionFields();
            if (hideExtensionCheck.isSelected()) {
                hideExtensionField.requestFocusInWindow();
            }
            applyFiltersImmediately();
        });

        showExtensionField.getDocument().addDocumentListener(filterDocumentListener());
        hideExtensionField.getDocument().addDocumentListener(filterDocumentListener());
        for (JCheckBox checkbox : mimeChecks.values()) {
            checkbox.addActionListener(event -> applyFiltersImmediately());
        }
        status2xxCheck.addActionListener(event -> applyFiltersImmediately());
        status3xxCheck.addActionListener(event -> applyFiltersImmediately());
        status4xxCheck.addActionListener(event -> applyFiltersImmediately());
        status5xxCheck.addActionListener(event -> applyFiltersImmediately());
    }

    private DocumentListener filterDocumentListener() {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyFilterIfNotRestoring();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyFilterIfNotRestoring();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyFilterIfNotRestoring();
            }

            private void applyFilterIfNotRestoring() {
                if (!restoringTabState) {
                    extensionFilterRefreshPending = true;
                    filterDebounceTimer.restart();
                }
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
                if (!restoringTabState && negativeAutoCheck.isSelected()) {
                    negativeFilterRefreshPending = true;
                    filterDebounceTimer.restart();
                }
            }
        };
    }

    private void applyNegativeFilter(boolean showErrorDialog) {
        if (restoringTabState) {
            return;
        }
        boolean applyExtension = extensionFilterRefreshPending;
        cancelScheduledFilterRefresh();
        if (updateActiveNegativeFilter(showErrorDialog)) {
            applyCurrentFilters();
        } else if (applyExtension) {
            applyCurrentFilters();
        }
    }

    private boolean updateActiveNegativeFilter(boolean showErrorDialog) {
        String candidate = negativeFilterField.getText();
        try {
            searchEngine.prepare(buildNegativeFilterOptions(candidate));
            negativeFilterField.setToolTipText(null);
        } catch (IllegalArgumentException exception) {
            negativeFilterField.setToolTipText(exception.getMessage());
            if (showErrorDialog) {
                JOptionPane.showMessageDialog(
                        this,
                        "Invalid negative filter: " + exception.getMessage(),
                        "Search++",
                        JOptionPane.ERROR_MESSAGE
                );
            }
            return false;
        }
        activeNegativeFilter = candidate;
        return true;
    }

    private void applyFiltersImmediately() {
        boolean applyNegative =
                negativeFilterRefreshPending && negativeAutoCheck.isSelected();
        cancelScheduledFilterRefresh();
        if (applyNegative) {
            updateActiveNegativeFilter(false);
        }
        applyCurrentFilters();
    }

    private void flushScheduledFilterRefresh() {
        if (shutdownRequested.get() || restoringTabState) {
            cancelScheduledFilterRefresh();
            return;
        }
        boolean applyExtension = extensionFilterRefreshPending;
        boolean applyNegative =
                negativeFilterRefreshPending && negativeAutoCheck.isSelected();
        extensionFilterRefreshPending = false;
        negativeFilterRefreshPending = false;
        if (applyNegative && !updateActiveNegativeFilter(false)) {
            if (applyExtension) {
                applyCurrentFilters();
            }
            return;
        }
        if (applyExtension || applyNegative) {
            applyCurrentFilters();
        }
    }

    private void cancelScheduledFilterRefresh() {
        filterDebounceTimer.stop();
        extensionFilterRefreshPending = false;
        negativeFilterRefreshPending = false;
    }

    private void preserveScheduledFilterRefreshForActiveTab() {
        boolean refreshPending =
                extensionFilterRefreshPending || negativeFilterRefreshPending;
        commitScheduledNegativeFilter();
        cancelScheduledFilterRefresh();
        if (refreshPending && isRealTabIndex(activeTabIndex)) {
            tabStates.get(activeTabIndex).filtersDirty = true;
        }
    }

    private void consumeScheduledFilterValuesForNewSearch() {
        commitScheduledNegativeFilter();
        cancelScheduledFilterRefresh();
    }

    private void commitScheduledNegativeFilter() {
        if (negativeFilterRefreshPending && negativeAutoCheck.isSelected()) {
            updateActiveNegativeFilter(false);
        }
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
        if (shutdownRequested.get()) {
            return;
        }
        SwingWorker<Void, SearchResultUpdate> runningWorker = currentWorker;
        if (runningWorker != null && !runningWorker.isDone()) {
            return;
        }
        if (runController.state() != SearchRunController.State.IDLE) {
            countLabel.setText(
                    tableModel.resultCount()
                            + " results (this Search++ window is still cancelling)"
            );
            return;
        }

        SearchOptions options;
        SearchEngine.PreparedSearch preparedSearch;
        try {
            options = buildQueryOptions();
            preparedSearch = searchEngine.prepare(options);
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, "Invalid search: " + exception.getMessage(), "Search++", JOptionPane.ERROR_MESSAGE);
            return;
        }

        long searchRunId = nextSearchRunId++;
        Optional<SearchExecutionCoordinator.Permit> acquiredPermit =
                executionCoordinator.tryAcquire(this, searchRunId);
        if (acquiredPermit.isEmpty()) {
            countLabel.setText(
                    tableModel.resultCount()
                            + " results (another Search++ search is already running)"
            );
            return;
        }
        if (!runController.begin(searchRunId, acquiredPermit.get())) {
            countLabel.setText(
                    tableModel.resultCount()
                            + " results (this Search++ window is still cancelling)"
            );
            return;
        }

        SearchPlusTabState searchTab = tabStates.get(activeTabIndex);
        long previousTabRunId = searchTab.searchRunId;
        List<SearchResult> previousAllResults = searchTab.allResults;
        SearchResultView previousVisibleResults = searchTab.visibleResults;
        boolean previousFiltersDirty = searchTab.filtersDirty;
        boolean workerHandedOff = false;
        try {
        consumeScheduledFilterValuesForNewSearch();
        saveActiveTabState();
        applyPreviewSearchExpression(options);
        SearchFilterPlan initialFilterPlan = createFilterPlan(nextFilterVersion++);
        cancelActiveFilterTask();
        liveFilterPlan = initialFilterPlan;
        searchTab.allResults = new ArrayList<>();
        searchTab.visibleResults =
                new SearchResultView(searchTab.allResults);
        searchTab.filtersDirty = false;
        tableModel.bind(searchTab.visibleResults);
        lastSearchCancelled = false;
        lastSearchFailed = false;
        lastSkippedResults = 0;
        lastMalformedItems = 0;
        lastScanRegexTimeoutItems = 0;
        lastRegexTimeoutItems = 0;
        activeFilterRegexTimeoutItems = 0;
        clearPreview();
        countLabel.setText("Searching... 0 results");
        showSearchButtonRunning();
        searchTab.searchRunId = searchRunId;
        SearchRunUiState searchRunState =
                new SearchRunUiState(searchRunId, searchTab);
        activeSearchRunState = searchRunState;

        SwingWorker<Void, SearchResultUpdate> worker = new SwingWorker<>() {
            private int skippedResults;
            private int malformedItems;
            private int sourceRegexTimeoutItems;
            private long scannedItems;
            private long matchedItems;
            private String terminalPhase = "unknown";
            private long terminalHeapUsedMiB = -1;
            private long terminalHeapCommittedMiB = -1;
            private long terminalHeapMaxMiB = -1;
            private volatile Throwable backgroundFailure;
            private volatile Throwable uiFailure;
            private volatile boolean terminalCancellation;
            private volatile Throwable terminalFailure;
            private final Runnable edtTerminalAction = this::completeTerminal;
            private final Runnable executionTerminalAction = () -> {
                if (SwingUtilities.isEventDispatchThread()) {
                    edtTerminalAction.run();
                } else {
                    SwingUtilities.invokeLater(edtTerminalAction);
                }
            };

            @Override
            protected Void doInBackground() {
                Thread searchThread = Thread.currentThread();
                runController.attachThread(searchRunId, searchThread);
                try {
                    SearchSourceScanner.ScanStatistics scanStatistics = sourceScanner.scan(
                            options,
                            preparedSearch,
                            () -> runController.cancellationRequested(searchRunId),
                            this::publishMatch
                    );
                    scannedItems = scanStatistics.scannedItems();
                    matchedItems = scanStatistics.matchedItems();
                    malformedItems = scanStatistics.malformedItems();
                    sourceRegexTimeoutItems = scanStatistics.regexTimeoutItems();
                    captureTerminalContext();
                    return null;
                } catch (RuntimeException | Error failure) {
                    if (!(failure instanceof CancellationException)) {
                        backgroundFailure = failure;
                    }
                    captureScannerStatistics();
                    captureTerminalContext();
                    throw failure;
                } finally {
                    runController.detachThread(searchRunId, searchThread);
                    Thread.interrupted();
                }
            }

            private void captureScannerStatistics() {
                try {
                    SearchSourceScanner.ScanStatistics scanStatistics =
                            sourceScanner.currentStatistics();
                    scannedItems = scanStatistics.scannedItems();
                    matchedItems = scanStatistics.matchedItems();
                    malformedItems = scanStatistics.malformedItems();
                    sourceRegexTimeoutItems = scanStatistics.regexTimeoutItems();
                } catch (Throwable ignored) {
                    // Preserve the original failure, especially OutOfMemoryError.
                }
            }

            private void captureTerminalContext() {
                try {
                    terminalPhase = sourceScanner.activePhase();
                    Runtime runtime = Runtime.getRuntime();
                    terminalHeapUsedMiB =
                            (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
                    terminalHeapCommittedMiB = runtime.totalMemory() / (1024L * 1024L);
                    terminalHeapMaxMiB = runtime.maxMemory() / (1024L * 1024L);
                } catch (Throwable ignored) {
                    // Preserve the original failure, especially OutOfMemoryError.
                }
            }

            private boolean publishMatch(HttpExchange exchange) {
                if (runController.cancellationRequested(searchRunId)) {
                    return false;
                }
                try {
                    SearchResult result = searchEngine.toResult(exchange);
                    SearchFilterPlan plan = liveFilterPlan;
                    SearchFilterPlan.Evaluation evaluation = plan == null
                            ? new SearchFilterPlan.Evaluation(true, 0)
                            : plan.evaluate(result);
                    publish(new SearchResultUpdate(
                            result,
                            plan == null ? 0 : plan.version(),
                            evaluation
                    ));
                } catch (CancellationException exception) {
                    return false;
                } catch (RuntimeException exception) {
                    skippedResults++;
                    if (skippedResults <= 5) {
                        logSearchWarning("Skipped Search++ result storage", exception);
                    } else if (skippedResults == 6) {
                        logSearchWarning("Further Search++ result-storage warnings suppressed", exception);
                    }
                }
                return true;
            }

            @Override
            protected void process(List<SearchResultUpdate> chunks) {
                if (!shutdownRequested.get()
                        && currentWorker == this
                        && !runController.cancellationRequested(searchRunId)) {
                    try {
                        processSearchUpdates(
                                searchTab,
                                searchRunState,
                                chunks
                        );
                    } catch (RuntimeException | Error failure) {
                        uiFailure = failure;
                        captureTerminalContext();
                        runController.requestCancellation();
                        cancel(true);
                    }
                }
            }

            @Override
            protected void done() {
                boolean cancellationSignal =
                        runController.cancellationRequested(searchRunId);
                Throwable completionFailure = null;
                try {
                    get();
                } catch (CancellationException exception) {
                    cancellationSignal = true;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    cancellationSignal = true;
                } catch (ExecutionException exception) {
                    completionFailure =
                            exception.getCause() == null ? exception : exception.getCause();
                } finally {
                    terminalCancellation = cancellationSignal
                            || runController.cancellationRequested(searchRunId);
                    terminalFailure = completionFailure != null
                            ? completionFailure
                            : backgroundFailure != null
                                    ? backgroundFailure
                                    : uiFailure != null
                                            ? uiFailure
                                            : searchRunState.filterFailure;
                    runController.finish(searchRunId, executionTerminalAction);
                }
            }

            private void completeTerminal() {
                if (shutdownRequested.get()) {
                    return;
                }
                SearchTerminalOutcome terminalOutcome = SearchTerminalOutcome.from(
                        terminalCancellation,
                        terminalFailure
                );
                boolean cancelled = terminalOutcome.cancelled();
                boolean failed = terminalOutcome.failed();
                Throwable failureCause = terminalOutcome.failureCause();
                int regexTimeoutItems =
                        sourceRegexTimeoutItems
                                + searchRunState.filterRegexTimeoutItems;
                if (failed && failureCause != null) {
                    logSearchFailure(
                            failureCause,
                            terminalPhase,
                            scannedItems,
                            matchedItems,
                            skippedResults,
                            malformedItems,
                            regexTimeoutItems,
                            terminalHeapUsedMiB,
                            terminalHeapCommittedMiB,
                            terminalHeapMaxMiB
                    );
                } else if (cancelled
                        || skippedResults > 0
                        || malformedItems > 0
                        || regexTimeoutItems > 0) {
                    logSearchTerminalSummary(
                            cancelled,
                            terminalPhase,
                            scannedItems,
                            matchedItems,
                            skippedResults,
                            malformedItems,
                            regexTimeoutItems,
                            terminalHeapUsedMiB,
                            terminalHeapCommittedMiB,
                            terminalHeapMaxMiB
                    );
                }
                if (searchTab.searchRunId != searchRunId) {
                    return;
                }
                boolean activeSearchTab =
                        isRealTabIndex(activeTabIndex)
                                && tabStates.get(activeTabIndex) == searchTab;
                int resultCount = activeSearchTab
                        ? tableModel.resultCount()
                        : searchTab.visibleResults.size();
                String finalCountText = searchResultCountText(
                        resultCount,
                        cancelled,
                        failed,
                        skippedResults,
                        malformedItems,
                        regexTimeoutItems
                );
                searchTab.searchCancelled = cancelled;
                searchTab.searchFailed = failed;
                searchTab.skippedResults = skippedResults;
                searchTab.malformedItems = malformedItems;
                searchTab.scanRegexTimeoutItems = sourceRegexTimeoutItems;
                searchTab.regexTimeoutItems = regexTimeoutItems;
                searchTab.countText = finalCountText;

                if (currentWorker == this) {
                    currentWorker = null;
                }
                if (activeSearchRunState == searchRunState) {
                    activeSearchRunState = null;
                }
                if (activeSearchTab) {
                    lastSearchCancelled = cancelled;
                    lastSearchFailed = failed;
                    lastSkippedResults = skippedResults;
                    lastMalformedItems = malformedItems;
                    lastScanRegexTimeoutItems = sourceRegexTimeoutItems;
                    lastRegexTimeoutItems = regexTimeoutItems;
                    if (tableModel.resultCount() == 0) {
                        clearPreview();
                    }
                    countLabel.setText(finalCountText);
                    saveActiveTabState();
                }
                // The callback runs only after SearchRunController has observed
                // the real scan thread detach. Do not expose a new-search icon
                // while a cancelled/native Burp call still owns the permit.
                if (currentWorker == null) {
                    showSearchButtonIdle();
                }
                if (failureCause != null
                        && !cancelled
                        && SearchPlusDialog.this.isDisplayable()) {
                    JOptionPane.showMessageDialog(
                            SearchPlusDialog.this,
                            searchFailureMessage(failureCause),
                            "Search++",
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        };
        currentWorker = worker;
        searchExecutor.execute(worker);
        workerHandedOff = true;
        } catch (RuntimeException | OutOfMemoryError startupFailure) {
            runController.finish(searchRunId);
            handleSearchStartupFailure(
                    searchTab,
                    previousTabRunId,
                    previousAllResults,
                    previousVisibleResults,
                    previousFiltersDirty,
                    startupFailure
            );
        } finally {
            if (!workerHandedOff) {
                runController.finish(searchRunId);
            }
        }
    }

    private void handleSearchStartupFailure(
            SearchPlusTabState searchTab,
            long previousTabRunId,
            List<SearchResult> previousAllResults,
            SearchResultView previousVisibleResults,
            boolean previousFiltersDirty,
            Throwable failure
    ) {
        currentWorker = null;
        SearchRunUiState runState = activeSearchRunState;
        if (runState != null && runState.runIdentity == searchTab.searchRunId) {
            activeSearchRunState = null;
        }
        try {
            searchTab.searchRunId = previousTabRunId;
            searchTab.allResults = previousAllResults;
            searchTab.visibleResults = previousVisibleResults;
            searchTab.filtersDirty = previousFiltersDirty;
            if (isRealTabIndex(activeTabIndex)
                    && tabStates.get(activeTabIndex) == searchTab) {
                restoreTabState(searchTab);
            }
            lastSearchCancelled = false;
            lastSearchFailed = true;
            lastSkippedResults = 0;
            lastMalformedItems = 0;
            lastScanRegexTimeoutItems = 0;
            lastRegexTimeoutItems = 0;
            String countText =
                    searchResultCountText(tableModel.resultCount(), false, true, 0, 0, 0);
            countLabel.setText(countText);
            showSearchButtonIdle();
            searchTab.searchCancelled = false;
            searchTab.searchFailed = true;
            searchTab.countText = countText;
        } catch (Throwable ignored) {
            // Cleanup and diagnostics below must still run after an OOM.
        }

        long usedMiB = -1;
        long committedMiB = -1;
        long maxMiB = -1;
        try {
            Runtime runtime = Runtime.getRuntime();
            usedMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
            committedMiB = runtime.totalMemory() / (1024L * 1024L);
            maxMiB = runtime.maxMemory() / (1024L * 1024L);
        } catch (Throwable ignored) {
            // Best-effort diagnostics only.
        }
        logSearchFailure(
                failure,
                "startup",
                0,
                0,
                0,
                0,
                0,
                usedMiB,
                committedMiB,
                maxMiB
        );
        try {
            if (isDisplayable()) {
                JOptionPane.showMessageDialog(
                        this,
                        searchFailureMessage(failure),
                        "Search++",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        } catch (Throwable ignored) {
            // The permit is released by runSearch's allocation-free finally.
        }
    }

    private void handleSearchButton() {
        SwingWorker<Void, SearchResultUpdate> worker = currentWorker;
        if (worker != null && !worker.isDone()) {
            requestSearchCancellation();
        } else {
            runSearch();
        }
    }

    private void requestSearchCancellation() {
        runController.requestCancellation();
        SwingWorker<Void, SearchResultUpdate> worker = currentWorker;
        if (worker != null) {
            worker.cancel(true);
        }
        searchButton.setIcon(SearchUiSupport.cancelIcon());
        searchButton.setEnabled(false);
        searchButton.setToolTipText("Cancelling search...");
        countLabel.setText("Cancelling... " + tableModel.resultCount() + " results");
    }

    private void showSearchButtonRunning() {
        searchButton.setIcon(SearchUiSupport.cancelIcon());
        searchButton.setToolTipText("Cancel search");
        searchButton.setEnabled(true);
    }

    private void showSearchButtonIdle() {
        searchButton.setIcon(SearchUiSupport.searchIcon());
        searchButton.setToolTipText("Search");
        searchButton.setEnabled(true);
    }

    private SearchOptions buildQueryOptions() {
        return optionsMapper.queryOptions(captureOptionsSnapshot());
    }

    private SearchOptions buildNegativeFilterOptions(String query) {
        return optionsMapper.negativeFilterOptions(captureOptionsSnapshot(), query);
    }

    private SearchOptionsSnapshot captureOptionsSnapshot() {
        return new SearchOptionsSnapshot(
                queryField.getText(),
                (SearchMode) modeCombo.getSelectedItem(),
                regexCheck.isSelected(),
                caseCheck.isSelected(),
                requestHeadersCheck.isSelected(),
                requestBodyCheck.isSelected(),
                responseHeadersCheck.isSelected(),
                responseBodyCheck.isSelected(),
                targetSourceCheck.isSelected(),
                proxySourceCheck.isSelected(),
                repeaterSourceCheck.isSelected(),
                organizerSourceCheck.isSelected(),
                statusPatterns(),
                mimeCategories(),
                showExtensions(),
                hideExtensions()
        );
    }

    private void applyCurrentFilters() {
        if (shutdownRequested.get()
                || restoringTabState
                || !isRealTabIndex(activeTabIndex)) {
            return;
        }

        SearchPlusTabState filterTab = tabStates.get(activeTabIndex);
        try {
            SearchOptionsSnapshot snapshot = captureOptionsSnapshot();
            String negativeFilter = activeNegativeFilter;
            long filterVersion = nextFilterVersion++;

            cancelActiveFilterTask();
            // PreparedSearch owns a mutable encoding cache. Keep one plan for
            // the source worker and a separate plan for the filter executor.
            SearchFilterPlan sourcePlan =
                    createFilterPlan(filterVersion, snapshot, negativeFilter);
            SearchFilterPlan backgroundPlan =
                     createFilterPlan(filterVersion, snapshot, negativeFilter);
            List<SearchResult> resultSnapshot = List.copyOf(filterTab.allResults);

            liveFilterPlan = sourcePlan;
            filterRefreshInProgress = true;
            filterRefreshVersion = filterVersion;
            filterTab.filtersDirty = true;
            pendingVisibleIndices.clear();
            pendingFilterRegexTimeoutItems = 0;
            activeFilterRegexTimeoutItems = 0;
            SearchRunUiState runState = activeSearchRunState;
            if (runState != null && runState.searchTab == filterTab) {
                runState.filterRegexTimeoutItems = 0;
            }
            countLabel.setText(
                    "Filtering... " + tableModel.resultCount() + " results"
            );

            currentFilterTask = filterExecutor.submit(() -> {
                try {
                    SearchFilterPlan.Outcome outcome =
                            backgroundPlan.evaluateAll(resultSnapshot);
                    SwingUtilities.invokeLater(() ->
                            completeFilterRefresh(filterTab, filterVersion, outcome));
                } catch (CancellationException ignored) {
                    // Replaced by a newer criteria version or dialog shutdown.
                } catch (RuntimeException | Error failure) {
                    SwingUtilities.invokeLater(() ->
                            handleFilterFailure(filterTab, filterVersion, failure));
                }
            });
        } catch (RuntimeException | Error failure) {
            handleFilterFailure(filterTab, -1, failure);
        }
    }

    private SearchFilterPlan createFilterPlan(long version) {
        return createFilterPlan(
                version,
                captureOptionsSnapshot(),
                activeNegativeFilter
        );
    }

    private SearchFilterPlan createFilterPlan(
            long version,
            SearchOptionsSnapshot snapshot,
            String negativeFilter
    ) {
        SearchOptions negativeOptions =
                negativeFilter == null || negativeFilter.isBlank()
                        ? null
                        : optionsMapper.negativeFilterOptions(
                                snapshot,
                                negativeFilter
                        );
        return new SearchFilterPlan(
                version,
                searchEngine,
                optionsMapper.filterOptions(snapshot),
                negativeOptions
        );
    }

    private void processSearchUpdates(
            SearchPlusTabState searchTab,
            SearchRunUiState searchRunState,
            List<SearchResultUpdate> updates
    ) {
        if (updates == null || updates.isEmpty()) {
            return;
        }

        SearchFilterPlan currentPlan = liveFilterPlan;
        long currentVersion = currentPlan == null ? -1 : currentPlan.version();
        boolean staleEvaluation = false;
        SearchResultView.IndexBuffer visibleIndices =
                new SearchResultView.IndexBuffer();
        int regexTimeoutItems = 0;
        for (SearchResultUpdate update : updates) {
            int canonicalIndex = searchTab.allResults.size();
            searchTab.allResults.add(update.result());
            if (update.filterVersion() != currentVersion) {
                staleEvaluation = true;
                continue;
            }
            if (update.evaluation().visible()) {
                visibleIndices.add(canonicalIndex);
            }
            regexTimeoutItems += update.evaluation().regexTimeoutItems();
        }

        if (staleEvaluation) {
            // These results were published just before a filter version swap
            // and are not present in that task's snapshot. A new full snapshot
            // is bounded to this short hand-off race and preserves source order.
            applyCurrentFilters();
            return;
        }

        if (filterRefreshInProgress
                && filterRefreshVersion == currentVersion) {
            pendingVisibleIndices.addAll(visibleIndices.toArray());
            pendingFilterRegexTimeoutItems += regexTimeoutItems;
            searchRunState.filterRegexTimeoutItems =
                    activeFilterRegexTimeoutItems
                            + pendingFilterRegexTimeoutItems;
            return;
        }

        activeFilterRegexTimeoutItems += regexTimeoutItems;
        searchRunState.filterRegexTimeoutItems =
                activeFilterRegexTimeoutItems;
        appendVisibleIndices(visibleIndices.toArray());
    }

    private void completeFilterRefresh(
            SearchPlusTabState filterTab,
            long filterVersion,
            SearchFilterPlan.Outcome outcome
    ) {
        if (!isCurrentFilterRefresh(filterTab, filterVersion)) {
            return;
        }

        currentFilterTask = null;
        filterRefreshInProgress = false;
        filterTab.filtersDirty = false;
        table.clearSelection();
        tableModel.replaceVisibleIndices(outcome.visibleIndices());
        tableModel.addVisibleIndices(pendingVisibleIndices.toArray());
        activeFilterRegexTimeoutItems =
                outcome.regexTimeoutItems()
                        + pendingFilterRegexTimeoutItems;
        SearchRunUiState runState = activeSearchRunState;
        if (runState != null && runState.searchTab == filterTab) {
            runState.filterRegexTimeoutItems =
                    activeFilterRegexTimeoutItems;
        }
        pendingVisibleIndices.clear();
        pendingFilterRegexTimeoutItems = 0;
        lastRegexTimeoutItems =
                lastScanRegexTimeoutItems + activeFilterRegexTimeoutItems;
        filterTab.regexTimeoutItems = lastRegexTimeoutItems;
        if (tableModel.resultCount() == 0) {
            clearPreview();
        } else {
            table.setRowSelectionInterval(0, 0);
        }
        countLabel.setText(activeResultCountText());
        saveActiveTabState();
    }

    private boolean isCurrentFilterRefresh(
            SearchPlusTabState filterTab,
            long filterVersion
    ) {
        SearchFilterPlan plan = liveFilterPlan;
        return !shutdownRequested.get()
                && isDisplayable()
                && isRealTabIndex(activeTabIndex)
                && tabStates.get(activeTabIndex) == filterTab
                && plan != null
                && plan.version() == filterVersion
                && filterRefreshInProgress
                && filterRefreshVersion == filterVersion;
    }

    private void handleFilterFailure(
            SearchPlusTabState filterTab,
            long filterVersion,
            Throwable failure
    ) {
        if (failure instanceof CancellationException) {
            return;
        }
        if (shutdownRequested.get()) {
            return;
        }
        SearchFilterPlan plan = liveFilterPlan;
        if (filterVersion >= 0
                && (plan == null || plan.version() != filterVersion)) {
            return;
        }
        if (!isRealTabIndex(activeTabIndex)
                || tabStates.get(activeTabIndex) != filterTab) {
            return;
        }

        filterTab.filtersDirty = true;
        cancelActiveFilterTask(true);
        SwingWorker<Void, SearchResultUpdate> worker = currentWorker;
        SearchRunUiState runState = activeSearchRunState;
        if (worker != null
                && runState != null
                && runState.searchTab == filterTab) {
            runState.filterFailure = failure;
            runController.requestCancellation();
            worker.cancel(true);
            searchButton.setEnabled(false);
            searchButton.setToolTipText("Stopping failed search...");
            countLabel.setText(
                    "Stopping... " + tableModel.resultCount() + " results"
            );
            return;
        }

        lastSearchCancelled = false;
        lastSearchFailed = true;
        filterTab.searchCancelled = false;
        filterTab.searchFailed = true;
        String failureCountText = searchResultCountText(
                tableModel.resultCount(),
                false,
                true,
                lastSkippedResults,
                lastMalformedItems,
                lastRegexTimeoutItems
        );
        countLabel.setText(failureCountText);
        filterTab.countText = failureCountText;
        logSearchFailure(
                failure,
                "filter",
                filterTab.allResults.size(),
                filterTab.allResults.size(),
                lastSkippedResults,
                lastMalformedItems,
                lastRegexTimeoutItems,
                heapUsedMiB(),
                heapCommittedMiB(),
                heapMaxMiB()
        );
        if (isDisplayable()) {
            try {
                JOptionPane.showMessageDialog(
                        this,
                        searchFailureMessage(failure),
                        "Search++",
                        JOptionPane.ERROR_MESSAGE
                );
            } catch (Throwable ignored) {
                // Preserve the original filtering failure.
            }
        }
    }

    private void cancelActiveFilterTask() {
        cancelActiveFilterTask(false);
    }

    private void cancelActiveFilterTask(boolean markActiveTabDirty) {
        if (markActiveTabDirty
                && filterRefreshInProgress
                && isRealTabIndex(activeTabIndex)) {
            tabStates.get(activeTabIndex).filtersDirty = true;
        }
        Future<?> task = currentFilterTask;
        currentFilterTask = null;
        SearchFilterExecutor.cancelAndPurge(filterExecutor, task);
        filterRefreshInProgress = false;
        filterRefreshVersion = 0;
        pendingVisibleIndices.clear();
        pendingFilterRegexTimeoutItems = 0;
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

    private void appendVisibleIndices(int[] indices) {
        appendVisibleIndices(indices, true);
    }

    private void appendVisibleIndices(
            int[] indices,
            boolean selectFirstResultWhenEmpty
    ) {
        boolean selectFirstResult = tableModel.resultCount() == 0 && table.getSelectedRow() < 0;
        tableModel.addVisibleIndices(indices);
        countLabel.setText(activeResultCountText());
        if (selectFirstResultWhenEmpty && selectFirstResult && tableModel.resultCount() > 0) {
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
                applyPreviewSearchExpression();
                responseHolder.add(responseEditor.uiComponent(), BorderLayout.CENTER);
            } else {
                applyPreviewSearchExpression();
                responseHolder.add(new JLabel("No response."), BorderLayout.CENTER);
            }
            responseHolder.revalidate();
            responseHolder.repaint();
        } catch (RuntimeException exception) {
            clearPreview();
        }
    }

    private void clearPreview() {
        applyPreviewSearchExpression();
        responseHolder.removeAll();
        responseHolder.add(new JLabel("No response selected."), BorderLayout.CENTER);
        responseHolder.revalidate();
        responseHolder.repaint();
    }

    private void applyPreviewSearchExpression() {
        applyPreviewSearchExpression(buildPreviewSearchExpression());
    }

    private void applyPreviewSearchExpression(SearchOptions options) {
        applyPreviewSearchExpression(SearchPlusTabState.previewSearchExpression(options.mode(), options.query()));
    }

    private String buildPreviewSearchExpression() {
        return SearchPlusTabState.previewSearchExpression((SearchMode) modeCombo.getSelectedItem(), queryField.getText());
    }

    private void applyPreviewSearchExpression(String expression) {
        String safeExpression = expression == null ? "" : expression;
        try {
            requestEditor.setSearchExpression(safeExpression);
            responseEditor.setSearchExpression(safeExpression);
        } catch (RuntimeException ignored) {
            // Editor search expression support is best-effort across Burp runtime versions.
        }
    }

    private SearchResult selectedResult() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return null;
        }
        int modelRow = table.convertRowIndexToModel(row);
        return tableModel.resultAt(modelRow);
    }

    private List<HttpRequestResponse> selectedRequestResponses() {
        int[] rows = table.getSelectedRows();
        List<HttpRequestResponse> selected = new ArrayList<>();
        for (int row : rows) {
            int modelRow = table.convertRowIndexToModel(row);
            SearchResult result = tableModel.resultAt(modelRow);
            if (result != null) {
                selected.add(result.exchange().requestResponse());
            }
        }
        return selected;
    }

    private List<HttpRequestResponse> allRequestResponses() {
        return tableModel.results().stream()
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
            countLabel.setText(activeResultCountText());
        }
    }

    private void logSearchWarning(String message, RuntimeException exception) {
        try {
            api.logging().logToError(message + ": " + exception);
        } catch (RuntimeException ignored) {
            // Logging is best-effort only.
        }
    }

    private void logSearchFailure(
            Throwable failure,
            String phase,
            long scannedItems,
            long matchedItems,
            int skippedResults,
            int malformedItems,
            int regexTimeoutItems,
            long usedMiB,
            long committedMiB,
            long maxMiB
    ) {
        try {
            api.logging().logToError(
                    "Search++ failed phase=" + phase
                            + " type=" + failure.getClass().getName()
                            + " message=" + failure.getMessage()
                            + " scanned=" + scannedItems
                            + " matched=" + matchedItems
                            + " storageSkipped=" + skippedResults
                            + " malformed=" + malformedItems
                            + " regexTimeouts=" + regexTimeoutItems
                            + " heapUsedMiB=" + usedMiB
                            + " heapCommittedMiB=" + committedMiB
                            + " heapMaxMiB=" + maxMiB
            );
        } catch (Throwable ignored) {
            // Preserve the original failure, including OutOfMemoryError.
        }
    }

    private long heapUsedMiB() {
        try {
            Runtime runtime = Runtime.getRuntime();
            return (runtime.totalMemory() - runtime.freeMemory())
                    / (1024L * 1024L);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private long heapCommittedMiB() {
        try {
            return Runtime.getRuntime().totalMemory() / (1024L * 1024L);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private long heapMaxMiB() {
        try {
            return Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void logSearchTerminalSummary(
            boolean cancelled,
            String phase,
            long scannedItems,
            long matchedItems,
            int skippedResults,
            int malformedItems,
            int regexTimeoutItems,
            long usedMiB,
            long committedMiB,
            long maxMiB
    ) {
        try {
            api.logging().logToError(
                    "Search++ terminal status=" + (cancelled ? "cancelled" : "incomplete")
                            + " phase=" + phase
                            + " scanned=" + scannedItems
                            + " matched=" + matchedItems
                            + " storageSkipped=" + skippedResults
                            + " malformed=" + malformedItems
                            + " regexTimeouts=" + regexTimeoutItems
                            + " heapUsedMiB=" + usedMiB
                            + " heapCommittedMiB=" + committedMiB
                            + " heapMaxMiB=" + maxMiB
            );
        } catch (Throwable ignored) {
            // Terminal diagnostics must not alter the search result.
        }
    }

    private String searchFailureMessage(Throwable failure) {
        if (failure instanceof OutOfMemoryError) {
            return "Search stopped because the Java heap was exhausted. "
                    + "Results found before the failure were preserved. "
                    + "Search++ did not retry automatically.";
        }
        String message = failure.getMessage();
        return "Search failed"
                + (message == null || message.isBlank() ? "." : ": " + message)
                + " Results found before the failure were preserved.";
    }

    private String searchResultCountText(
            int resultCount,
            boolean cancelled,
            boolean failed,
            int skippedResults,
            int malformedItems,
            int regexTimeoutItems
    ) {
        List<String> notes = new ArrayList<>(6);
        if (failed) {
            notes.add("failed");
        }
        if (cancelled) {
            notes.add("cancelled");
        }
        if (skippedResults > 0) {
            notes.add(skippedResults + " storage skipped");
        }
        if (malformedItems > 0) {
            notes.add(malformedItems + " malformed checks skipped");
        }
        if (regexTimeoutItems > 0) {
            notes.add("incomplete");
            notes.add(regexTimeoutItems + " regex timeouts");
        }
        return resultCount + " results" + (notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")");
    }

    private String activeResultCountText() {
        if (currentWorker == null) {
            return searchResultCountText(
                    tableModel.resultCount(),
                    lastSearchCancelled,
                    lastSearchFailed,
                    lastSkippedResults,
                    lastMalformedItems,
                    lastRegexTimeoutItems
            );
        }
        return (runController.state() == SearchRunController.State.CANCELLING
                ? "Cancelling... "
                : "Searching... ")
                + tableModel.resultCount()
                + " results";
    }

    private void cancelRunningSearch() {
        SwingWorker<Void, SearchResultUpdate> worker = currentWorker;
        currentWorker = null;
        activeSearchRunState = null;
        cancelActiveFilterTask(true);
        liveFilterPlan = null;
        if (worker != null) {
            runController.requestCancellation();
            worker.cancel(true);
            searchButton.setIcon(SearchUiSupport.cancelIcon());
            searchButton.setEnabled(false);
            searchButton.setToolTipText("Cancelling search...");
        } else if (runController.state() == SearchRunController.State.IDLE) {
            showSearchButtonIdle();
        }
    }

    private void closeOwnedWindows() {
        requestShutdown();
    }

    void requestShutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            return;
        }
        SwingWorker<Void, SearchResultUpdate> worker = currentWorker;
        currentWorker = null;
        activeSearchRunState = null;
        liveFilterPlan = null;
        cancelScheduledFilterRefresh();
        Future<?> filterTask = currentFilterTask;
        currentFilterTask = null;
        SearchFilterExecutor.cancelAndPurge(filterExecutor, filterTask);
        runController.close();
        sourceScanner.clearRetainedContext();
        if (worker != null) {
            worker.cancel(true);
        }
        filterExecutor.shutdownNow();
        searchExecutor.shutdownNow();
        Runnable clearUiReferences = this::clearUiReferencesAfterShutdown;
        if (SwingUtilities.isEventDispatchThread()) {
            clearUiReferences.run();
        } else {
            SwingUtilities.invokeLater(clearUiReferences);
        }
    }

    private void clearUiReferencesAfterShutdown() {
        for (SearchPlusTabState state : tabStates) {
            state.visibleResults.clearView();
            state.allResults.clear();
        }
        tabStates.clear();
        activeTabIndex = -1;
        pendingVisibleIndices.clear();
        pendingFilterRegexTimeoutItems = 0;
        table.clearSelection();
        tableModel.bind(new SearchResultView(new ArrayList<>()));
        searchTabs.removeAll();
        searchTabs.revalidate();
        searchTabs.repaint();
        responseHolder.removeAll();
        responseHolder.revalidate();
        responseHolder.repaint();
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

    private record SearchResultUpdate(
            SearchResult result,
            long filterVersion,
            SearchFilterPlan.Evaluation evaluation
    ) {
    }

    private static final class SearchRunUiState {
        private final long runIdentity;
        private final SearchPlusTabState searchTab;
        private int filterRegexTimeoutItems;
        private Throwable filterFailure;

        private SearchRunUiState(
                long runIdentity,
                SearchPlusTabState searchTab
        ) {
            this.runIdentity = runIdentity;
            this.searchTab = searchTab;
        }
    }

    private record SourceSelection(
            boolean target,
            boolean proxy,
            boolean repeater,
            boolean organizer
    ) {
    }

    private record SearchContextPolicy(SourceSelection sourceSelection, String label) {
        private static SearchContextPolicy from(int itemCount, int scopeCount) {
            if (scopeCount > 0) {
                return new SearchContextPolicy(
                        new SourceSelection(true, false, false, false),
                        "Scope: " + scopeCount + (scopeCount == 1 ? " selection" : " selections")
                );
            }
            if (itemCount > 0) {
                return new SearchContextPolicy(
                        new SourceSelection(false, false, false, false),
                        "Context: " + itemCount + (itemCount == 1 ? " item" : " items")
                );
            }
            return new SearchContextPolicy(
                    new SourceSelection(true, true, true, true),
                    "Context: 0 items"
            );
        }
    }

}
