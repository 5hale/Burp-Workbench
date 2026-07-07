package com.burpworkbench.tests;

import com.burpworkbench.modules.extractor.ExportIndexRow;
import com.burpworkbench.modules.extractor.ExportIndexWriter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportIndexWriterTest {
    @Test
    void escapesHtmlAndLinksSavedPath() {
        ExportIndexWriter writer = new ExportIndexWriter();
        String html = writer.toHtml(List.of(new ExportIndexRow(
                "saved",
                "GET",
                "https://example.com/a?<x>",
                200,
                "html",
                "text/html",
                12,
                "example.com\\a b.html",
                "",
                "",
                "decoded <ok>"
        )));

        assertTrue(html.contains("href=\"example.com/a%20b.html\""));
        assertTrue(html.contains("https://example.com/a?&lt;x&gt;"));
        assertTrue(html.contains("decoded &lt;ok&gt;"));
    }
}

