package com.burpworkbench.modules.search;

record SearchResult(
        HttpExchange exchange,
        String mime,
        long length
) {
}
