package com.burpworkbench.platform;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import burp.api.montoya.extension.Extension;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.logging.Logging;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleRegistryTest {
    @Test
    void centralUnloadClosesModulesInReverseOrderAndDeregistersItself() {
        List<String> events = new ArrayList<>();
        ApiFixture fixture = new ApiFixture(events);
        ModuleRegistry registry = new ModuleRegistry(fixture.api());
        registry.register(module("Extractor", events));
        registry.register(module("Search++", events));

        registry.start();
        fixture.unloadingHandler().extensionUnloaded();
        registry.close();

        assertEquals(
                List.of(
                        "start:Extractor",
                        "loaded:Burp Workbench module loaded: Extractor",
                        "start:Search++",
                        "loaded:Burp Workbench module loaded: Search++",
                        "close:Search++",
                        "close:Extractor",
                        "unload-registration:deregister"
                ),
                events
        );
        assertFalse(fixture.unloadingRegistration.isRegistered());
        assertEquals(1, fixture.unloadingRegistration.deregisterCalls.get());
    }

    @Test
    void initializeFailureRollsBackFailedAndStartedModules() {
        List<String> events = new ArrayList<>();
        ApiFixture fixture = new ApiFixture(events);
        ModuleRegistry registry = new ModuleRegistry(fixture.api());
        registry.register(module("first", events));
        registry.register(new WorkbenchModule() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void initialize(ModuleContext context, ModuleLifetime lifetime) {
                events.add("start:broken");
                lifetime.onClose(() -> events.add("close:broken:first"));
                lifetime.onClose(() -> {
                    events.add("close:broken:failing");
                    throw new IllegalStateException("cleanup failed");
                });
                throw new IllegalArgumentException("startup failed");
            }
        });

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, registry::start);

        assertEquals("startup failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(
                List.of(
                        "start:first",
                        "loaded:Burp Workbench module loaded: first",
                        "start:broken",
                        "close:broken:failing",
                        "close:broken:first",
                        "close:first"
                ),
                events
        );
        assertThrows(IllegalStateException.class, registry::start);
        assertThrows(IllegalStateException.class, () -> registry.register(module("late", events)));
    }

    @Test
    void oneModuleCloseFailureDoesNotBlockEarlierModules() {
        List<String> events = new ArrayList<>();
        ApiFixture fixture = new ApiFixture(events);
        ModuleRegistry registry = new ModuleRegistry(fixture.api());
        registry.register(module("first", events));
        registry.register(new WorkbenchModule() {
            @Override
            public String name() {
                return "failing-close";
            }

            @Override
            public void initialize(ModuleContext context, ModuleLifetime lifetime) {
                events.add("start:failing-close");
                lifetime.onClose(() -> {
                    events.add("close:failing-close");
                    throw new IllegalStateException("boom");
                });
            }
        });

        registry.start();
        registry.close();

        assertTrue(events.contains("close:failing-close"));
        assertTrue(events.contains("close:first"));
        assertTrue(events.stream().anyMatch(event -> event.startsWith("error:Burp Workbench cleanup failed")));
    }

    @Test
    void loggingFailureAfterInitializeRollsBackTheInitializedModule() {
        List<String> events = new ArrayList<>();
        ApiFixture fixture = new ApiFixture(events);
        fixture.outputFailure.set(new IllegalStateException("logging unavailable"));
        ModuleRegistry registry = new ModuleRegistry(fixture.api());
        AtomicInteger nameCalls = new AtomicInteger();
        registry.register(new WorkbenchModule() {
            @Override
            public String name() {
                nameCalls.incrementAndGet();
                return "module";
            }

            @Override
            public void initialize(ModuleContext context, ModuleLifetime lifetime) {
                events.add("start:module");
                lifetime.onClose(() -> events.add("close:module"));
            }
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class, registry::start);

        assertEquals("logging unavailable", failure.getMessage());
        assertEquals(1, nameCalls.get());
        assertEquals(List.of("start:module", "close:module"), events);
        assertThrows(IllegalStateException.class, registry::start);
    }

    @Test
    void cleanupLoggingErrorDoesNotInterruptRemainingModuleCleanup() {
        List<String> events = new ArrayList<>();
        ApiFixture fixture = new ApiFixture(events);
        fixture.errorFailure.set(new AssertionError("logging failed"));
        ModuleRegistry registry = new ModuleRegistry(fixture.api());
        registry.register(module("first", events));
        registry.register(new WorkbenchModule() {
            @Override
            public String name() {
                return "failing-close";
            }

            @Override
            public void initialize(ModuleContext context, ModuleLifetime lifetime) {
                events.add("start:failing-close");
                lifetime.onClose(() -> {
                    events.add("close:failing-close");
                    throw new IllegalStateException("cleanup failed");
                });
            }
        });

        registry.start();
        registry.close();

        assertTrue(events.contains("close:failing-close"));
        assertTrue(events.contains("close:first"));
        assertFalse(fixture.unloadingRegistration.isRegistered());
    }

    private static WorkbenchModule module(String name, List<String> events) {
        return new WorkbenchModule() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void initialize(ModuleContext context, ModuleLifetime lifetime) {
                events.add("start:" + name);
                lifetime.onClose(() -> events.add("close:" + name));
            }
        };
    }

    private static final class ApiFixture {
        private final List<String> events;
        private final AtomicReference<ExtensionUnloadingHandler> unloadingHandler = new AtomicReference<>();
        private final AtomicReference<RuntimeException> outputFailure = new AtomicReference<>();
        private final AtomicReference<Error> errorFailure = new AtomicReference<>();
        private final TestRegistration unloadingRegistration;
        private final MontoyaApi api;

        private ApiFixture(List<String> events) {
            this.events = events;
            this.unloadingRegistration = new TestRegistration("unload-registration", events);
            Logging logging = proxy(Logging.class, (method, args) -> {
                if ("logToOutput".equals(method.getName())) {
                    if (outputFailure.get() != null) {
                        throw outputFailure.get();
                    }
                    events.add("loaded:" + args[0]);
                } else if ("logToError".equals(method.getName())) {
                    if (errorFailure.get() != null) {
                        throw errorFailure.get();
                    }
                    events.add("error:" + args[0]);
                }
                return defaultValue(method.getReturnType());
            });
            Extension extension = proxy(Extension.class, (method, args) -> {
                if ("registerUnloadingHandler".equals(method.getName())) {
                    unloadingHandler.set((ExtensionUnloadingHandler) args[0]);
                    return unloadingRegistration;
                }
                return defaultValue(method.getReturnType());
            });
            this.api = proxy(MontoyaApi.class, (method, args) -> switch (method.getName()) {
                case "extension" -> extension;
                case "logging" -> logging;
                default -> defaultValue(method.getReturnType());
            });
        }

        private MontoyaApi api() {
            return api;
        }

        private ExtensionUnloadingHandler unloadingHandler() {
            return unloadingHandler.get();
        }
    }

    private static final class TestRegistration implements Registration {
        private final String name;
        private final List<String> events;
        private final AtomicInteger deregisterCalls = new AtomicInteger();
        private boolean registered = true;

        private TestRegistration(String name, List<String> events) {
            this.name = name;
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
            events.add(name + ":deregister");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> invocation.invoke(method, args == null ? new Object[0] : args)
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
