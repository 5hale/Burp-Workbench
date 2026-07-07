package com.burpworkbench.tests;

import com.burpworkbench.modules.extractor.ExportActionType;
import com.burpworkbench.modules.extractor.ExportCandidate;
import com.burpworkbench.modules.extractor.ExportOptions;
import com.burpworkbench.modules.extractor.ExportPlan;
import com.burpworkbench.modules.extractor.ExportPlanner;
import com.burpworkbench.core.util.Hashes;
import com.burpworkbench.core.filter.MimeCategory;
import com.burpworkbench.core.codec.ResponseBodyDecoder;
import com.burpworkbench.modules.extractor.StatusFilterMode;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExportPlannerTest {
    private final ExportPlanner planner = new ExportPlanner();
    private final ResponseBodyDecoder decoder = new ResponseBodyDecoder();

    @Test
    void skipsDuplicateDecodedBodyBySha256() {
        byte[] body = "same".getBytes(StandardCharsets.UTF_8);
        ExportPlan plan = planner.plan(
                java.util.List.of(
                        candidate("https://example.com/a.js", "text/javascript", Path.of("example.com", "a.js"), body),
                        candidate("https://example.com/b.js", "text/javascript", Path.of("example.com", "b.js"), body)
                ),
                ExportOptions.defaults(),
                2
        );

        assertEquals(1, plan.count(ExportActionType.SAVED));
        assertEquals(1, plan.count(ExportActionType.DUPLICATE));
    }

    @Test
    void compressedBodiesWithSameDecodedBytesAreDuplicates() throws Exception {
        byte[] gzipDecoded = decoder.decode(gzip("same-body"), "gzip").bytes();
        byte[] brotliDecoded = decoder.decode(Base64.getDecoder().decode("CwSAc2FtZS1ib2R5Aw=="), "br").bytes();

        ExportPlan plan = planner.plan(
                java.util.List.of(
                        candidate("https://example.com/a.txt", "text/plain", Path.of("example.com", "a.txt"), gzipDecoded),
                        candidate("https://example.com/b.txt", "text/plain", Path.of("example.com", "b.txt"), brotliDecoded)
                ),
                ExportOptions.defaults(),
                2
        );

        assertEquals(1, plan.count(ExportActionType.SAVED));
        assertEquals(1, plan.count(ExportActionType.DUPLICATE));
    }

    @Test
    void appliesStatusMimeAndExtensionFilters() {
        ExportOptions options = new ExportOptions(
                StatusFilterMode.CUSTOM,
                Set.of("200"),
                EnumSet.of(MimeCategory.JAVASCRIPT),
                Set.of(".js")
        );

        ExportPlan plan = planner.plan(
                List.of(
                        candidate("https://example.com/app.js", "text/javascript", Path.of("example.com", "app.js"), "js".getBytes(StandardCharsets.UTF_8)),
                        candidate("https://example.com/not-found.js", 404, "text/javascript", Path.of("example.com", "not-found.js"), new byte[]{1}),
                        candidate("https://example.com/app.css", "text/css", Path.of("example.com", "app.css"), "css".getBytes(StandardCharsets.UTF_8)),
                        candidate("https://example.com/app.txt", "text/javascript", Path.of("example.com", "app.txt"), "txt".getBytes(StandardCharsets.UTF_8))
                ),
                options,
                4
        );

        assertEquals(1, plan.actions().size());
        assertEquals(1, plan.count(ExportActionType.SAVED));
        assertEquals(0, plan.count(ExportActionType.SKIPPED));
        assertEquals(0, plan.filteredCount());
    }

    @Test
    void allStatusIncludesEveryStatus() {
        ExportPlan plan = planner.plan(
                List.of(
                        candidate("https://example.com/ok.txt", 200, "text/plain", Path.of("example.com", "ok.txt"), bytes("ok")),
                        candidate("https://example.com/not-found.txt", 404, "text/plain", Path.of("example.com", "not-found.txt"), bytes("not-found"))
                ),
                new ExportOptions(StatusFilterMode.ALL, Set.of(), EnumSet.allOf(MimeCategory.class), Set.of()),
                2
        );

        assertEquals(2, plan.actions().size());
        assertEquals(2, plan.count(ExportActionType.SAVED));
    }

    @Test
    void customStatusPatternsIncludeExactAndStatusFamilies() {
        ExportOptions options = new ExportOptions(
                StatusFilterMode.CUSTOM,
                ExportOptions.parseStatusPatterns("200,404,3xx"),
                EnumSet.allOf(MimeCategory.class),
                Set.of()
        );

        ExportPlan plan = planner.plan(
                List.of(
                        candidate("https://example.com/ok.txt", 200, "text/plain", Path.of("example.com", "ok.txt"), bytes("ok")),
                        candidate("https://example.com/redirect.txt", 301, "text/plain", Path.of("example.com", "redirect.txt"), bytes("redirect")),
                        candidate("https://example.com/not-found.txt", 404, "text/plain", Path.of("example.com", "not-found.txt"), bytes("not-found")),
                        candidate("https://example.com/error.txt", 500, "text/plain", Path.of("example.com", "error.txt"), bytes("error"))
                ),
                options,
                4
        );

        assertEquals(3, plan.actions().size());
        assertEquals(3, plan.count(ExportActionType.SAVED));
    }

    @Test
    void mimeImageWithJpgExtensionShowsOnlyJpgImages() {
        ExportPlan plan = planner.plan(
                imageCandidates(),
                new ExportOptions(StatusFilterMode.ALL, Set.of(), EnumSet.of(MimeCategory.IMAGE), Set.of(".jpg")),
                4
        );

        assertEquals(1, plan.actions().size());
        assertEquals(Path.of("example.com", "a.jpg"), plan.actions().get(0).candidate().relativePath());
    }

    @Test
    void mimeImageWithJpgAndPngExtensionsShowsBoth() {
        ExportPlan plan = planner.plan(
                imageCandidates(),
                new ExportOptions(StatusFilterMode.ALL, Set.of(), EnumSet.of(MimeCategory.IMAGE), ExportOptions.parseExtensions("jpg,png")),
                4
        );

        assertEquals(2, plan.actions().size());
        assertEquals(2, plan.count(ExportActionType.SAVED));
    }

    @Test
    void emptyMimeWithPngExtensionShowsOnlyPng() {
        ExportPlan plan = planner.plan(
                imageCandidates(),
                new ExportOptions(StatusFilterMode.ALL, Set.of(), Set.of(), Set.of(".png")),
                4
        );

        assertEquals(1, plan.actions().size());
        assertEquals(Path.of("example.com", "b.png"), plan.actions().get(0).candidate().relativePath());
    }

    @Test
    void emptyMimeAndEmptyExtensionsShowEverything() {
        ExportPlan plan = planner.plan(
                imageCandidates(),
                new ExportOptions(StatusFilterMode.ALL, Set.of(), Set.of(), Set.of()),
                4
        );

        assertEquals(4, plan.actions().size());
        assertEquals(4, plan.count(ExportActionType.SAVED));
    }

    @Test
    void noResponsePngVisibleAsSkippedNoResponse() {
        ExportPlan plan = planner.plan(
                List.of(
                        noResponseCandidate("https://example.com/missing.png", Path.of("example.com", "missing.png")),
                        noResponseCandidate("https://example.com/missing.jpg", Path.of("example.com", "missing.jpg"))
                ),
                new ExportOptions(StatusFilterMode.ALL, Set.of(), Set.of(), Set.of(".png")),
                2
        );

        assertEquals(1, plan.actions().size());
        assertEquals(1, plan.count(ExportActionType.SKIPPED));
        assertEquals(1, plan.skippedNoResponseCount());
    }

    @Test
    void identicalPngBodyVisibleAsDuplicate() {
        byte[] body = bytes("same-png");
        ExportPlan plan = planner.plan(
                List.of(
                        candidate("https://example.com/a.png", "image/png", Path.of("example.com", "a.png"), body),
                        candidate("https://example.com/b.png", "image/png", Path.of("example.com", "b.png"), body),
                        candidate("https://example.com/c.js", "text/javascript", Path.of("example.com", "c.js"), body)
                ),
                new ExportOptions(StatusFilterMode.ALL, Set.of(), Set.of(), Set.of(".png")),
                3
        );

        assertEquals(2, plan.actions().size());
        assertEquals(1, plan.count(ExportActionType.SAVED));
        assertEquals(1, plan.count(ExportActionType.DUPLICATE));
    }

    private ExportCandidate candidate(String url, String contentType, Path path, byte[] body) {
        return ExportCandidate.withBody("GET", url, 200, contentType, path, body);
    }

    private ExportCandidate candidate(String url, int statusCode, String contentType, Path path, byte[] body) {
        return ExportCandidate.withBody("GET", url, statusCode, contentType, path, body);
    }

    private ExportCandidate noResponseCandidate(String url, Path path) {
        return new ExportCandidate(
                "GET",
                url,
                false,
                -1,
                "",
                "",
                "",
                path,
                MimeCategory.from("", path),
                new byte[0],
                new byte[0],
                false,
                "no response",
                Hashes.sha256Hex(new byte[0])
        );
    }

    private List<ExportCandidate> imageCandidates() {
        return List.of(
                candidate("https://example.com/a.jpg", "image/jpeg", Path.of("example.com", "a.jpg"), bytes("jpg")),
                candidate("https://example.com/b.png", "image/png", Path.of("example.com", "b.png"), bytes("png")),
                candidate("https://example.com/c.svg", "image/svg+xml", Path.of("example.com", "c.svg"), bytes("svg")),
                candidate("https://example.com/d.js", "text/javascript", Path.of("example.com", "d.js"), bytes("js"))
        );
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] gzip(String value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
