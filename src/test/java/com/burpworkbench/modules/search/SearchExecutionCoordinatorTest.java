package com.burpworkbench.modules.search;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchExecutionCoordinatorTest {
    @Test
    void doesNotQueueOrCancelASecondOwner() {
        SearchExecutionCoordinator coordinator = new SearchExecutionCoordinator();
        Object firstOwner = new Object();
        Object secondOwner = new Object();

        SearchExecutionCoordinator.Permit first =
                coordinator.tryAcquire(firstOwner, 1).orElseThrow();
        Optional<SearchExecutionCoordinator.Permit> rejected =
                coordinator.tryAcquire(secondOwner, 2);

        assertTrue(rejected.isEmpty());
        assertFalse(first.isReleased());
        assertEquals(firstOwner, first.owner());
        assertEquals(1, first.runIdentity());

        first.close();
        assertTrue(coordinator.tryAcquire(secondOwner, 2).isPresent());
    }

    @Test
    void terminalAndWindowClosePathsReleaseThePermit() {
        SearchExecutionCoordinator coordinator = new SearchExecutionCoordinator();
        SearchRunController controller = new SearchRunController();
        Object owner = new Object();

        assertTrue(controller.begin(7, coordinator.tryAcquire(owner, 7).orElseThrow()));
        assertTrue(controller.requestCancellation());
        assertEquals(SearchRunController.State.CANCELLING, controller.state());
        assertTrue(controller.finish(7));
        assertEquals(SearchRunController.State.IDLE, controller.state());
        assertTrue(coordinator.tryAcquire(new Object(), 8).isPresent());

        SearchExecutionCoordinator secondCoordinator = new SearchExecutionCoordinator();
        SearchRunController closingController = new SearchRunController();
        assertTrue(closingController.begin(
                9,
                secondCoordinator.tryAcquire(owner, 9).orElseThrow()
        ));
        closingController.close();

        assertTrue(secondCoordinator.tryAcquire(new Object(), 10).isPresent());
    }

    @Test
    void closedCoordinatorRejectsNewRuns() {
        SearchExecutionCoordinator coordinator = new SearchExecutionCoordinator();

        coordinator.close();

        assertTrue(coordinator.tryAcquire(new Object(), 1).isEmpty());
    }

    @Test
    void closingCoordinatorDoesNotPretendAnActiveRunHasTerminated() {
        SearchExecutionCoordinator coordinator = new SearchExecutionCoordinator();
        SearchExecutionCoordinator.Permit permit =
                coordinator.tryAcquire(new Object(), 1).orElseThrow();

        coordinator.close();

        assertTrue(coordinator.isBusy());
        assertFalse(permit.isReleased());
        assertTrue(coordinator.tryAcquire(new Object(), 2).isEmpty());

        permit.close();
        assertFalse(coordinator.isBusy());
    }

    @Test
    void staleTerminalCallbackCannotReleaseANewerRun() {
        SearchExecutionCoordinator coordinator = new SearchExecutionCoordinator();
        SearchRunController controller = new SearchRunController();
        Object owner = new Object();

        assertTrue(controller.begin(1, coordinator.tryAcquire(owner, 1).orElseThrow()));
        assertTrue(controller.finish(1));
        assertTrue(controller.begin(2, coordinator.tryAcquire(owner, 2).orElseThrow()));

        assertFalse(controller.finish(1));
        assertTrue(coordinator.isBusy());
        assertTrue(coordinator.tryAcquire(new Object(), 3).isEmpty());

        assertTrue(controller.finish(2));
        assertFalse(coordinator.isBusy());
    }

    @Test
    void closeDoesNotReleasePermitUntilTheExecutingThreadLeaves() {
        SearchExecutionCoordinator coordinator = new SearchExecutionCoordinator();
        SearchRunController controller = new SearchRunController();
        Thread executingThread = new Thread(() -> {
        });

        assertTrue(controller.begin(
                11,
                coordinator.tryAcquire(new Object(), 11).orElseThrow()
        ));
        controller.attachThread(11, executingThread);

        controller.close();

        assertTrue(executingThread.isInterrupted());
        assertTrue(coordinator.isBusy());
        assertTrue(coordinator.tryAcquire(new Object(), 12).isEmpty());

        controller.detachThread(11, executingThread);

        assertFalse(coordinator.isBusy());
        assertTrue(coordinator.tryAcquire(new Object(), 12).isPresent());
    }

    @Test
    void terminalUiCallbackWaitsForTheExecutingThreadToLeave() {
        SearchExecutionCoordinator coordinator = new SearchExecutionCoordinator();
        SearchRunController controller = new SearchRunController();
        Thread executingThread = new Thread(() -> {
        });
        AtomicInteger terminalCallbacks = new AtomicInteger();

        assertTrue(controller.begin(
                21,
                coordinator.tryAcquire(new Object(), 21).orElseThrow()
        ));
        controller.attachThread(21, executingThread);

        assertTrue(controller.finish(21, terminalCallbacks::incrementAndGet));
        assertEquals(0, terminalCallbacks.get());
        assertTrue(coordinator.isBusy());

        controller.detachThread(21, executingThread);

        assertEquals(1, terminalCallbacks.get());
        assertFalse(coordinator.isBusy());
    }
}
