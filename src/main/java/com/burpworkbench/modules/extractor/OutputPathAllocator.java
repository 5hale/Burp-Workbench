package com.burpworkbench.modules.extractor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class OutputPathAllocator {
    private final Set<Path> exactPaths = new HashSet<>();
    private final Map<Path, PrefixNode> roots = new HashMap<>();
    private final Map<SuffixSequence, Integer> nextSuffixes = new HashMap<>();

    Path allocate(Path requestedPath) {
        Path normalized = requestedPath.normalize();
        int conflictComponent = conflictComponent(normalized);
        if (conflictComponent < 0) {
            reserve(normalized);
            return normalized;
        }

        SuffixSequence sequence = new SuffixSequence(normalized, conflictComponent);
        int suffix = nextSuffixes.getOrDefault(sequence, 2);
        for (; ; suffix = nextSuffix(suffix)) {
            Path candidate = suffixComponent(normalized, conflictComponent, suffix);
            if (conflictComponent(candidate) < 0) {
                reserve(candidate);
                nextSuffixes.put(sequence, nextSuffix(suffix));
                return candidate;
            }
        }
    }

    /**
     * Every allocated path represents a file. Exact equality and either ancestor direction are
     * therefore collisions: a file cannot also be a directory for another output.
     */
    private int conflictComponent(Path candidate) {
        int lastComponent = Math.max(candidate.getNameCount() - 1, 0);
        if (exactPaths.contains(candidate)) {
            return lastComponent;
        }

        PrefixNode node = roots.get(candidate.getRoot());
        if (node == null) {
            return -1;
        }
        for (int index = 0; index < candidate.getNameCount(); index++) {
            node = node.children.get(candidate.getName(index));
            if (node == null) {
                return -1;
            }
            if (node.terminal && index < candidate.getNameCount() - 1) {
                return index;
            }
        }
        if (node.subtreeTerminals > 0) {
            return lastComponent;
        }
        return -1;
    }

    private void reserve(Path path) {
        exactPaths.add(path);
        PrefixNode node = roots.computeIfAbsent(path.getRoot(), ignored -> new PrefixNode());
        node.subtreeTerminals++;
        for (int index = 0; index < path.getNameCount(); index++) {
            node = node.children.computeIfAbsent(path.getName(index), ignored -> new PrefixNode());
            node.subtreeTerminals++;
        }
        node.terminal = true;
    }

    private int nextSuffix(int suffix) {
        if (suffix == Integer.MAX_VALUE) {
            throw new IllegalStateException("output path suffix space exhausted");
        }
        return suffix + 1;
    }

    private Path suffixComponent(Path path, int componentIndex, int suffix) {
        Path result = path.getRoot();
        if (result == null) {
            result = path.getFileSystem().getPath("");
        }

        int lastComponent = path.getNameCount() - 1;
        for (int index = 0; index < path.getNameCount(); index++) {
            String name = path.getName(index).toString();
            if (index == componentIndex) {
                name = suffixedName(name, suffix, index == lastComponent);
            }
            result = result.resolve(name);
        }
        return result;
    }

    private String suffixedName(String name, int suffix, boolean preserveExtension) {
        if (!preserveExtension) {
            return name + "__" + suffix;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        return stem + "__" + suffix + extension;
    }

    private record SuffixSequence(Path path, int componentIndex) {
    }

    private static final class PrefixNode {
        private final Map<Path, PrefixNode> children = new HashMap<>();
        private int subtreeTerminals;
        private boolean terminal;
    }
}
