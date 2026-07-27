package com.burpworkbench.modules.search;

import java.util.concurrent.CancellationException;

/**
 * Normalizes SwingWorker completion so cancellation is never also reported as
 * a search failure.
 */
record SearchTerminalOutcome(
        boolean cancelled,
        boolean failed,
        Throwable failureCause
) {
    static SearchTerminalOutcome from(
            boolean cancellationRequested,
            Throwable completionFailure
    ) {
        if (completionFailure != null
                && !(completionFailure instanceof CancellationException)) {
            return new SearchTerminalOutcome(false, true, completionFailure);
        }
        if (cancellationRequested
                || completionFailure instanceof CancellationException) {
            return new SearchTerminalOutcome(true, false, null);
        }
        return new SearchTerminalOutcome(false, false, null);
    }
}
