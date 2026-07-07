package com.burpworkbench.tests;

import com.burpworkbench.core.util.JsonLines;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonLinesTest {
    @Test
    void escapesStringsAndWritesPrimitiveValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("text", "line\r\n\"quoted\"");
        values.put("count", 3);
        values.put("ok", true);
        values.put("none", null);

        assertEquals(
                "{\"text\":\"line\\r\\n\\\"quoted\\\"\",\"count\":3,\"ok\":true,\"none\":null}",
                JsonLines.toJsonObject(values)
        );
    }
}

