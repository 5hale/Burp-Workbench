package com.burpworkbench.tests;

import com.burpworkbench.modules.extractor.UrlPathMapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlPathMapperTest {
    private final UrlPathMapper mapper = new UrlPathMapper();

    @Test
    void mapsRootToHostIndex() {
        assertEquals(Path.of("example.com", "index.html"), mapper.map("GET", "https://Example.COM/"));
    }

    @Test
    void preservesDirectoryStructureAndFileName() {
        assertEquals(
                Path.of("example.com", "static", "app.js"),
                mapper.map("GET", "https://example.com/static/app.js")
        );
    }

    @Test
    void mapsTrailingSlashToIndexFile() {
        assertEquals(
                Path.of("example.com", "docs", "index.html"),
                mapper.map("GET", "https://example.com/docs/")
        );
    }

    @Test
    void addsQueryHashBeforeExtension() {
        Path mapped = mapper.map("GET", "https://example.com/static/app.js?v=1");

        assertEquals(Path.of("example.com", "static"), mapped.getParent());
        assertTrue(mapped.getFileName().toString().matches("app__q_[0-9a-f]{8}\\.js"));
    }

    @Test
    void addsMethodHashForNonGetResponses() {
        Path mapped = mapper.map("POST", "https://example.com/api/export");

        assertEquals(Path.of("example.com", "api"), mapped.getParent());
        assertTrue(mapped.getFileName().toString().matches("export__post_[0-9a-f]{8}"));
    }

    @Test
    void includesNonDefaultPortInHostDirectory() {
        assertEquals(
                Path.of("example.com_8443", "app.js"),
                mapper.map("GET", "https://example.com:8443/app.js")
        );
    }

    @Test
    void sanitizesWindowsUnsafeAndTraversalSegments() {
        assertEquals(
                Path.of("example.com", "_", "_CON.txt"),
                mapper.map("GET", "https://example.com/%2e%2e/CON.txt")
        );
    }

    @Test
    void appendsContentTypeExtensionWhenUrlHasNoExtension() {
        assertEquals(
                Path.of("example.com", "api", "config.json"),
                mapper.map("GET", "https://example.com/api/config", "application/json; charset=utf-8", null)
        );
    }

    @Test
    void keepsExistingExtensionWhenContentTypeIsKnown() {
        assertEquals(
                Path.of("example.com", "static", "app.js"),
                mapper.map("GET", "https://example.com/static/app.js", "text/plain", null)
        );
    }

    @Test
    void usesContentDispositionFilenameBeforeUrlLeaf() {
        assertEquals(
                Path.of("example.com", "download", "report.zip"),
                mapper.map(
                        "GET",
                        "https://example.com/download/file",
                        "application/zip",
                        "attachment; filename=\"report.zip\""
                )
        );
    }

    @Test
    void decodesRfc5987ContentDispositionFilename() {
        assertEquals(
                Path.of("example.com", "report 2026.pdf"),
                mapper.map(
                        "GET",
                        "https://example.com/download",
                        "application/pdf",
                        "attachment; filename*=UTF-8''report%202026.pdf"
                )
        );
    }
}
