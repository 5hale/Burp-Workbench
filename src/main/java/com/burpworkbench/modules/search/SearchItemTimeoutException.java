package com.burpworkbench.modules.search;

final class SearchItemTimeoutException extends RuntimeException {
    SearchItemTimeoutException(long timeoutMillis) {
        super(
                "regular expression item deadline exceeded after "
                        + Math.max(1, timeoutMillis)
                        + " ms",
                null,
                false,
                false
        );
    }
}
