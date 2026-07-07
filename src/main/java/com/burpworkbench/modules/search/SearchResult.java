package com.burpworkbench.modules.search;

import com.burpworkbench.core.http.HttpExchange;

public record SearchResult(
        HttpExchange exchange,
        String mime,
        long length
) {
}
