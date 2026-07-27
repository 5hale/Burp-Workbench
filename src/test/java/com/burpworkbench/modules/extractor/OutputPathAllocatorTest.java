package com.burpworkbench.modules.extractor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class OutputPathAllocatorTest {
    @Test
    void suffixesExactFileCollisionsDeterministically() {
        OutputPathAllocator allocator = new OutputPathAllocator();
        Path requested = Path.of("example.com", "file.txt");

        assertEquals(requested, allocator.allocate(requested));
        assertEquals(Path.of("example.com", "file__2.txt"), allocator.allocate(requested));
        assertEquals(Path.of("example.com", "file__3.txt"), allocator.allocate(requested));
    }

    @Test
    void suffixesAncestorComponentWhenExistingFileWouldNeedToBecomeDirectory() {
        OutputPathAllocator allocator = new OutputPathAllocator();

        assertEquals(
                Path.of("example.com", "asset"),
                allocator.allocate(Path.of("example.com", "asset"))
        );
        assertEquals(
                Path.of("example.com", "asset__2", "child.js"),
                allocator.allocate(Path.of("example.com", "asset", "child.js"))
        );
    }

    @Test
    void suffixesNewFileWhenItWouldReplaceAnExistingDirectoryTree() {
        OutputPathAllocator allocator = new OutputPathAllocator();

        assertEquals(
                Path.of("example.com", "asset", "child.js"),
                allocator.allocate(Path.of("example.com", "asset", "child.js"))
        );
        assertEquals(
                Path.of("example.com", "asset__2"),
                allocator.allocate(Path.of("example.com", "asset"))
        );
    }

    @Test
    void handlesInterleavedAncestorAndDescendantCollisions() {
        OutputPathAllocator allocator = new OutputPathAllocator();

        assertEquals(
                Path.of("example.com", "asset"),
                allocator.allocate(Path.of("example.com", "asset"))
        );
        assertEquals(
                Path.of("example.com", "asset__2", "child.js"),
                allocator.allocate(Path.of("example.com", "asset", "child.js"))
        );
        assertEquals(
                Path.of("example.com", "asset__3", "child.js"),
                allocator.allocate(Path.of("example.com", "asset", "child.js"))
        );
        assertEquals(
                Path.of("example.com", "asset__4"),
                allocator.allocate(Path.of("example.com", "asset"))
        );
    }

    @Test
    void allocatesLargeCollisionSequenceWithoutRestartingSuffixScan() {
        assertTimeout(Duration.ofSeconds(3), () -> {
            OutputPathAllocator allocator = new OutputPathAllocator();
            Path requested = Path.of("example.com", "file.txt");
            Path allocated = allocator.allocate(requested);

            for (int suffix = 2; suffix <= 10_000; suffix++) {
                allocated = allocator.allocate(requested);
            }

            assertEquals(Path.of("example.com", "file__10000.txt"), allocated);
        });
    }

    @Test
    void preservesCaseDistinctPathsOnCaseSensitiveProviders() {
        assumeFalse(Path.of("A").equals(Path.of("a")));
        OutputPathAllocator allocator = new OutputPathAllocator();

        assertEquals(Path.of("example.com", "A.js"), allocator.allocate(Path.of("example.com", "A.js")));
        assertEquals(Path.of("example.com", "a.js"), allocator.allocate(Path.of("example.com", "a.js")));
    }
}
