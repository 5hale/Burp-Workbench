package com.burpworkbench.modules.search;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTerminalOutcomeTest {
    @Test
    void realFailureWinsOverAConcurrentCancellationSignal() {
        OutOfMemoryError concurrentFailure = new OutOfMemoryError("late failure");

        SearchTerminalOutcome outcome =
                SearchTerminalOutcome.from(true, concurrentFailure);

        assertFalse(outcome.cancelled());
        assertTrue(outcome.failed());
        assertSame(concurrentFailure, outcome.failureCause());
    }

    @Test
    void cancellationExceptionIsNotReportedAsFailure() {
        SearchTerminalOutcome outcome =
                SearchTerminalOutcome.from(false, new CancellationException());

        assertTrue(outcome.cancelled());
        assertFalse(outcome.failed());
        assertNull(outcome.failureCause());
    }

    @Test
    void uncancelledFailureRemainsFatal() {
        OutOfMemoryError failure = new OutOfMemoryError("heap");

        SearchTerminalOutcome outcome =
                SearchTerminalOutcome.from(false, failure);

        assertFalse(outcome.cancelled());
        assertTrue(outcome.failed());
        assertSame(failure, outcome.failureCause());
    }
}
