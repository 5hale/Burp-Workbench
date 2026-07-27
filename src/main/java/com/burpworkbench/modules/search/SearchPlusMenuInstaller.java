package com.burpworkbench.modules.search;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;

import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;

final class SearchPlusMenuInstaller {
    private static final String BURP_MENU = "Burp";
    private static final String SEARCH_MENU = "Search";
    private static final String SEARCH_PLUS_MENU = "Search++";

    private final MontoyaApi api;
    private final Runnable openSearchPlus;
    private JMenuItem insertedMenuItem;
    private Registration fallbackRegistration;

    public SearchPlusMenuInstaller(MontoyaApi api, Runnable openSearchPlus) {
        this.api = api;
        this.openSearchPlus = openSearchPlus;
    }

    public void install() {
        SwingUtilities.invokeLater(this::installOnEventDispatchThread);
    }

    public void uninstall() {
        SwingUtilities.invokeLater(this::uninstallOnEventDispatchThread);
    }

    private void installOnEventDispatchThread() {
        if (insertIntoBurpMenu()) {
            api.logging().logToOutput("Search++ menu installed under Burp > Search.");
            return;
        }

        registerFallbackTopLevelMenu();
    }

    private boolean insertIntoBurpMenu() {
        JMenuBar menuBar = suiteMenuBar();
        if (menuBar == null) {
            return false;
        }

        JMenu burpMenu = findMenu(menuBar, BURP_MENU);
        if (burpMenu == null || containsMenuItem(burpMenu, SEARCH_PLUS_MENU)) {
            return burpMenu != null;
        }

        JMenuItem searchPlusItem = new JMenuItem(SEARCH_PLUS_MENU);
        searchPlusItem.addActionListener(event -> openSearchPlus.run());

        int insertIndex = searchItemIndex(burpMenu);
        if (insertIndex < 0) {
            burpMenu.add(searchPlusItem);
        } else {
            burpMenu.insert(searchPlusItem, insertIndex + 1);
        }

        insertedMenuItem = searchPlusItem;
        burpMenu.revalidate();
        burpMenu.repaint();
        menuBar.revalidate();
        menuBar.repaint();
        return true;
    }

    private JMenuBar suiteMenuBar() {
        Frame suiteFrame = api.userInterface().swingUtils().suiteFrame();
        if (suiteFrame == null) {
            return null;
        }
        if (suiteFrame instanceof JFrame frame) {
            return frame.getJMenuBar();
        }
        return findMenuBar(suiteFrame);
    }

    private JMenuBar findMenuBar(Component component) {
        if (component instanceof JMenuBar menuBar) {
            return menuBar;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JMenuBar menuBar = findMenuBar(child);
                if (menuBar != null) {
                    return menuBar;
                }
            }
        }
        return null;
    }

    private JMenu findMenu(JMenuBar menuBar, String text) {
        for (int i = 0; i < menuBar.getMenuCount(); i++) {
            JMenu menu = menuBar.getMenu(i);
            if (menu != null && text.equals(normalize(menu.getText()))) {
                return menu;
            }
        }
        return null;
    }

    private boolean containsMenuItem(JMenu menu, String text) {
        for (Component component : menu.getMenuComponents()) {
            if (component instanceof AbstractButton button && text.equals(normalize(button.getText()))) {
                return true;
            }
        }
        return false;
    }

    private int searchItemIndex(JMenu menu) {
        Component[] components = menu.getMenuComponents();
        for (int i = 0; i < components.length; i++) {
            if (components[i] instanceof AbstractButton button
                    && SEARCH_MENU.equals(normalize(button.getText()))) {
                return i;
            }
        }
        return -1;
    }

    private void registerFallbackTopLevelMenu() {
        JMenu menu = new JMenu(SEARCH_PLUS_MENU);
        JMenuItem openItem = new JMenuItem("Open Search++");
        openItem.addActionListener(event -> openSearchPlus.run());
        menu.add(openItem);
        fallbackRegistration = api.userInterface().menuBar().registerMenu(menu);
        api.logging().logToOutput("Search++ menu installed as a top-level menu.");
    }

    private void uninstallOnEventDispatchThread() {
        if (insertedMenuItem != null) {
            Component parent = insertedMenuItem.getParent();
            if (parent instanceof JMenu menu) {
                menu.remove(insertedMenuItem);
                menu.revalidate();
                menu.repaint();
            }
            insertedMenuItem = null;
        }

        if (fallbackRegistration != null && fallbackRegistration.isRegistered()) {
            fallbackRegistration.deregister();
            fallbackRegistration = null;
        }
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace("&", "").trim();
    }
}
