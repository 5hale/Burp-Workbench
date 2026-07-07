package com.burpworkbench.core.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Dialog.ModalityType;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.util.List;

public final class RequestResponseDetailDialog extends JDialog {
    private final MontoyaApi api;
    private final List<HttpRequestResponse> requestResponses;
    private final JButton previousButton = new JButton("Previous");
    private final JButton nextButton = new JButton("Next");
    private final JButton actionButton = new JButton("Action");
    private final JLabel requestTitle = new JLabel("Request");
    private final JLabel responseTitle = new JLabel("Response");
    private final JPanel responseHolder = new JPanel(new BorderLayout());
    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;
    private int currentIndex;

    private RequestResponseDetailDialog(Window owner, MontoyaApi api, List<HttpRequestResponse> requestResponses, int selectedIndex) {
        super((Window) null, "", ModalityType.MODELESS);
        this.api = api;
        this.requestResponses = requestResponses == null ? List.of() : List.copyOf(requestResponses);
        this.currentIndex = Math.max(0, Math.min(selectedIndex, this.requestResponses.size() - 1));
        this.requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        this.responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(toolbar(), BorderLayout.NORTH);
        add(editorPanel(), BorderLayout.CENTER);

        previousButton.addActionListener(event -> showIndex(currentIndex - 1));
        nextButton.addActionListener(event -> showIndex(currentIndex + 1));
        actionButton.addActionListener(event -> showActionMenu());

        showIndex(currentIndex);
        setPreferredSize(new Dimension(1400, 820));
        pack();
        setLocationRelativeTo(owner);
    }

    public static void open(Window owner, MontoyaApi api, List<HttpRequestResponse> requestResponses, int selectedIndex) {
        if (api == null || requestResponses == null || requestResponses.isEmpty()) {
            return;
        }
        RequestResponseDetailDialog dialog = new RequestResponseDetailDialog(owner, api, requestResponses, selectedIndex);
        dialog.setVisible(true);
    }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        panel.add(previousButton);
        panel.add(nextButton);
        panel.add(actionButton);
        return panel;
    }

    private JPanel editorPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(0, 8, 8, 8));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.5);
        splitPane.setLeftComponent(titledEditor(requestTitle, requestEditor.uiComponent()));
        splitPane.setRightComponent(titledEditor(responseTitle, responseHolder));
        panel.add(splitPane, BorderLayout.CENTER);

        api.userInterface().applyThemeToComponent(panel);
        return panel;
    }

    private JPanel titledEditor(JLabel title, java.awt.Component editor) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(title, BorderLayout.NORTH);
        panel.add(editor, BorderLayout.CENTER);
        return panel;
    }

    private void showIndex(int index) {
        if (index < 0 || index >= requestResponses.size()) {
            return;
        }

        currentIndex = index;
        HttpRequestResponse requestResponse = requestResponses.get(currentIndex);
        setTitle(title(requestResponse));
        requestTitle.setText("Request");
        responseTitle.setText("Response");
        previousButton.setEnabled(currentIndex > 0);
        nextButton.setEnabled(currentIndex < requestResponses.size() - 1);

        responseHolder.removeAll();
        if (requestResponse == null) {
            responseHolder.add(messagePanel("Original request/response is not available for this row."), BorderLayout.CENTER);
        } else {
            requestEditor.setRequest(requestResponse.request());
            if (requestResponse.hasResponse() && requestResponse.response() != null) {
                responseEditor.setResponse(requestResponse.response());
                responseHolder.add(responseEditor.uiComponent(), BorderLayout.CENTER);
            } else {
                responseHolder.add(messagePanel("No response."), BorderLayout.CENTER);
            }
        }

        responseHolder.revalidate();
        responseHolder.repaint();
    }

    private void showActionMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyUrl = new JMenuItem("Copy URL");
        copyUrl.addActionListener(event -> copyCurrentUrl());
        menu.add(copyUrl);
        menu.show(actionButton, 0, actionButton.getHeight());
    }

    private void copyCurrentUrl() {
        if (currentIndex < 0 || currentIndex >= requestResponses.size()) {
            return;
        }
        String url = url(requestResponses.get(currentIndex));
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
    }

    private static JPanel messagePanel(String message) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        panel.add(new JLabel(message), BorderLayout.CENTER);
        return panel;
    }

    private static String title(HttpRequestResponse requestResponse) {
        String method = "";
        try {
            method = requestResponse == null ? "" : requestResponse.request().method();
        } catch (RuntimeException ignored) {
            method = "";
        }
        String url = url(requestResponse);
        if (url.length() > 140) {
            url = url.substring(0, 137) + "...";
        }
        return method + " request to " + url;
    }

    private static String url(HttpRequestResponse requestResponse) {
        try {
            return requestResponse == null ? "" : requestResponse.request().url();
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
