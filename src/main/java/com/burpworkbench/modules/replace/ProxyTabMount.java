package com.burpworkbench.modules.replace;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Access to existing Swing components. No Burp private reflection. */
final class ProxyTabMount {
    static final String TITLE = "replace ++";
    private static final int MAX_NODES = 20000;
    private static final String OWNER_KEY = "com.burpworkbench.replace.proxyTab";
    private final Consumer<String> log;
    private JTabbedPane host;
    private JPanel owned;
    private Component matchComponent;
    private List<OriginalTab> originals = List.of();

    ProxyTabMount(Consumer<String> log) { this.log = log; }

    record Discovery(List<JTabbedPane> candidates, boolean truncated, int visited) {}
    record OriginalTab(Component component, String title, Icon icon, boolean enabled,
                       String tooltip, int mnemonic, int mnemonicIndex, Component header) {
        static OriginalTab read(JTabbedPane pane, int i) {
            return new OriginalTab(pane.getComponentAt(i), pane.getTitleAt(i), pane.getIconAt(i),
                    pane.isEnabledAt(i), pane.getToolTipTextAt(i), pane.getMnemonicAt(i),
                    pane.getDisplayedMnemonicIndexAt(i), pane.getTabComponentAt(i));
        }
    }

    static Discovery discover(Component root) {
        requireEdt();
        List<JTabbedPane> found = new ArrayList<>();
        Set<Component> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean complete = visit(root, found, visited, 0);
        return new Discovery(List.copyOf(found), !complete, visited.size());
    }

    private static boolean visit(Component component, List<JTabbedPane> found,
                                 Set<Component> visited, int depth) {
        if (component == null || visited.contains(component)) return true;
        if (depth > 100 || visited.size() >= MAX_NODES) return false;
        visited.add(component);
        if (component instanceof JTabbedPane pane && historyIndex(pane) >= 0 && matchIndex(pane) >= 0) {
            found.add(pane);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (!visit(child, found, visited, depth + 1)) return false;
            }
        }
        return true;
    }

    boolean install(Component root, JPanel panel) {
        requireEdt();
        if (owned != null) { log.accept("ALREADY_INSTALLED"); return verify(); }
        Discovery result = discover(root);
        if (result.truncated() || result.candidates().size() != 1) {
            log.accept("NOT_INSERTED candidates=" + result.candidates().size()
                    + " truncated=" + result.truncated() + " visited=" + result.visited());
            return false;
        }
        JTabbedPane candidate = result.candidates().get(0);
        for (int i = 0; i < candidate.getTabCount(); i++) {
            if (TITLE.equalsIgnoreCase(candidate.getTitleAt(i)) || "mt/re ++".equalsIgnoreCase(candidate.getTitleAt(i))
                    || candidate.getComponentAt(i) instanceof JComponent item && item.getClientProperty(OWNER_KEY) != null) {
                log.accept("NOT_INSERTED existing_tab_or_title_collision");
                return false;
            }
        }
        if (panel.getParent() != null) throw new IllegalArgumentException("Replace panel is already attached");
        List<OriginalTab> snapshot = new ArrayList<>();
        for (int i = 0; i < candidate.getTabCount(); i++) snapshot.add(OriginalTab.read(candidate, i));
        int anchor = matchIndex(candidate);
        Component selected = candidate.getSelectedComponent();
        host = candidate;
        owned = panel;
        matchComponent = candidate.getComponentAt(anchor);
        originals = List.copyOf(snapshot);
        panel.putClientProperty(OWNER_KEY, Boolean.TRUE);
        try {
            candidate.insertTab(TITLE, null, panel, "Replace ++ rules for Proxy traffic", anchor + 1);
            if (selected != null && candidate.indexOfComponent(selected) >= 0) candidate.setSelectedComponent(selected);
            if (!verify()) throw new IllegalStateException("Existing tabs changed during insertion");
            candidate.revalidate();
            candidate.repaint();
            log.accept("INSERTED rightOf=Match_and_replace index=" + candidate.indexOfComponent(panel)
                    + " matchIndex=" + candidate.indexOfComponent(matchComponent)
                    + " originalTabs=" + originals.size() + " currentTabs=" + candidate.getTabCount()
                    + " selectionPreserved=" + (candidate.getSelectedComponent() == selected)
                    + " host=" + candidate.getClass().getName());
            return true;
        } catch (RuntimeException failure) {
            remove();
            throw failure;
        }
    }

    boolean verify() {
        requireEdt();
        if (host == null || owned == null) return false;
        int ownIndex = host.indexOfComponent(owned);
        boolean intact = ownIndex >= 0 && host.indexOfComponent(matchComponent) == ownIndex - 1
                && host.getTabCount() == originals.size() + 1 && originalsIntact();
        log.accept("CHECK intact=" + intact + " replaceIndex=" + ownIndex);
        return intact;
    }

    boolean focus() {
        requireEdt();
        if (host == null || host.indexOfComponent(owned) < 0) return false;
        Component child = owned;
        for (Container parent = child.getParent(); parent != null; parent = child.getParent()) {
            if (parent instanceof JTabbedPane pane && pane.indexOfComponent(child) >= 0) {
                pane.setSelectedComponent(child);
            }
            child = parent;
        }
        owned.requestFocusInWindow();
        return true;
    }

    private boolean originalsIntact() {
        int lastIndex = -1;
        for (OriginalTab tab : originals) {
            int index = host.indexOfComponent(tab.component());
            if (index <= lastIndex || !tab.equals(OriginalTab.read(host, index))) return false;
            lastIndex = index;
        }
        return true;
    }

    void remove() {
        requireEdt();
        if (host == null) return;
        JTabbedPane previousHost = host;
        JPanel previousPanel = owned;
        try {
            int index = previousHost.indexOfComponent(previousPanel);
            if (index >= 0) {
                if (previousHost.getSelectedComponent() == previousPanel
                        && previousHost.indexOfComponent(matchComponent) >= 0) {
                    previousHost.setSelectedComponent(matchComponent);
                }
                // Remove only the exact component owned by this module, never by title/index alone.
                previousHost.removeTabAt(previousHost.indexOfComponent(previousPanel));
            }
            log.accept("REMOVED originalTabsIntact=" + originalsIntact()
                    + " remainingTabs=" + previousHost.getTabCount());
            previousHost.revalidate();
            previousHost.repaint();
        } finally {
            previousPanel.putClientProperty(OWNER_KEY, null);
            host = null;
            owned = null;
            matchComponent = null;
            originals = List.of();
        }
    }

    private static int historyIndex(JTabbedPane pane) { return find(pane, "http history"); }
    private static int matchIndex(JTabbedPane pane) {
        int index = find(pane, "match and replace");
        return index >= 0 ? index : find(pane, "match replace");
    }
    private static int find(JTabbedPane pane, String title) {
        for (int i = 0; i < pane.getTabCount(); i++) {
            String actual = pane.getTitleAt(i);
            if (actual != null && actual.replace("&", "").trim().toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ").equals(title)) return i;
        }
        return -1;
    }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Swing EDT required");
    }
}
