package com.burpworkbench.modules.search;

import burp.api.montoya.core.Registration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchPlusModuleTest {
    @Test
    void startupRollbackClosesCreatedResourcesInRequiredOrder() {
        List<String> events = new ArrayList<>();
        TestRegistration registration = new TestRegistration(events);
        SearchPlusModule.SearchModuleResources resources =
                new SearchPlusModule.SearchModuleResources(() -> events.add("provider"));
        resources.ownMenuCleanup(() -> events.add("menu"));
        resources.own(registration);

        resources.close();
        resources.close();

        assertEquals(List.of("provider", "menu", "registration"), events);
        assertEquals(1, registration.deregisterCalls.get());
    }

    @Test
    void registrationFailureStillClosesProviderAndMenu() {
        List<String> events = new ArrayList<>();
        SearchPlusModule.SearchModuleResources resources =
                new SearchPlusModule.SearchModuleResources(() -> events.add("provider"));
        resources.ownMenuCleanup(() -> events.add("menu"));

        resources.close();

        assertEquals(List.of("provider", "menu"), events);
    }

    @Test
    void oneCleanupFailureDoesNotPreventRemainingCleanup() {
        List<String> events = new ArrayList<>();
        TestRegistration registration = new TestRegistration(events);
        SearchPlusModule.SearchModuleResources resources =
                new SearchPlusModule.SearchModuleResources(() -> {
                    events.add("provider");
                    throw new IllegalStateException("provider failed");
                });
        resources.ownMenuCleanup(() -> events.add("menu"));
        resources.own(registration);

        IllegalStateException failure = assertThrows(IllegalStateException.class, resources::close);

        assertEquals("provider failed", failure.getMessage());
        assertEquals(List.of("provider", "menu", "registration"), events);
    }

    private static final class TestRegistration implements Registration {
        private final List<String> events;
        private final AtomicInteger deregisterCalls = new AtomicInteger();
        private boolean registered = true;

        private TestRegistration(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean isRegistered() {
            return registered;
        }

        @Override
        public void deregister() {
            deregisterCalls.incrementAndGet();
            registered = false;
            events.add("registration");
        }
    }
}
