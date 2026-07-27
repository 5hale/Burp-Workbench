package com.burpworkbench.platform;

import burp.api.montoya.core.Registration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleLifetimeTest {
    @Test
    void closesResourcesInReverseOrderOnlyOnce() {
        ModuleLifetime lifetime = new ModuleLifetime("test");
        List<String> events = new ArrayList<>();

        lifetime.onClose(() -> events.add("first"));
        lifetime.onClose(() -> events.add("second"));
        lifetime.onClose(() -> events.add("third"));

        lifetime.close();
        lifetime.close();

        assertEquals(List.of("third", "second", "first"), events);
        assertTrue(lifetime.isClosed());
    }

    @Test
    void cleanupFailureDoesNotPreventRemainingActions() {
        ModuleLifetime lifetime = new ModuleLifetime("test");
        List<String> events = new ArrayList<>();
        lifetime.onClose(() -> events.add("first"));
        lifetime.onClose(() -> {
            events.add("failing");
            throw new IllegalStateException("boom");
        });
        lifetime.onClose(() -> events.add("last"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, lifetime::close);

        assertEquals("boom", failure.getMessage());
        assertEquals(List.of("last", "failing", "first"), events);
        assertTrue(lifetime.isClosed());
    }

    @Test
    void ownedRegistrationIsDeregistered() {
        ModuleLifetime lifetime = new ModuleLifetime("test");
        TestRegistration registration = new TestRegistration();

        assertEquals(registration, lifetime.own(registration));
        lifetime.close();

        assertFalse(registration.isRegistered());
        assertEquals(1, registration.deregisterCalls.get());
    }

    @Test
    void resourceAddedAfterCloseIsClosedImmediatelyAndRejected() {
        ModuleLifetime lifetime = new ModuleLifetime("test");
        AtomicInteger closeCalls = new AtomicInteger();
        lifetime.close();

        assertThrows(
                IllegalStateException.class,
                () -> lifetime.own((AutoCloseable) closeCalls::incrementAndGet)
        );
        assertEquals(1, closeCalls.get());
    }

    private static final class TestRegistration implements Registration {
        private final AtomicInteger deregisterCalls = new AtomicInteger();
        private boolean registered = true;

        @Override
        public boolean isRegistered() {
            return registered;
        }

        @Override
        public void deregister() {
            deregisterCalls.incrementAndGet();
            registered = false;
        }
    }
}
