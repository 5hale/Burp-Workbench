package com.burpworkbench.platform;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ModuleRegistry implements AutoCloseable {
    private final MontoyaApi api;
    private final List<WorkbenchModule> modules = new ArrayList<>();
    private final List<StartedModule> startedModules = new ArrayList<>();
    private State state = State.NEW;
    private Registration unloadingRegistration;

    public ModuleRegistry(MontoyaApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    public synchronized void register(WorkbenchModule module) {
        if (state != State.NEW) {
            throw new IllegalStateException("Modules can only be registered before startup");
        }
        modules.add(Objects.requireNonNull(module, "module"));
    }

    public synchronized void start() {
        if (state != State.NEW) {
            throw new IllegalStateException("Module registry has already been started or closed");
        }
        state = State.STARTING;
        ModuleContext context = new ModuleContext(api);

        try {
            for (WorkbenchModule module : modules) {
                String moduleName = module.name();
                ModuleLifetime lifetime = new ModuleLifetime(moduleName);
                boolean tracked = false;
                try {
                    module.initialize(context, lifetime);
                    startedModules.add(new StartedModule(moduleName, lifetime));
                    tracked = true;
                    api.logging().logToOutput("Burp Workbench module loaded: " + moduleName);
                } catch (RuntimeException | Error failure) {
                    if (!tracked) {
                        closeAfterFailure(lifetime, failure);
                    }
                    throw failure;
                }
            }
            unloadingRegistration = api.extension().registerUnloadingHandler(this::close);
            state = State.STARTED;
        } catch (RuntimeException | Error failure) {
            rollbackStartedModules(failure);
            state = State.CLOSED;
            modules.clear();
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;

        for (int index = startedModules.size() - 1; index >= 0; index--) {
            StartedModule startedModule = startedModules.get(index);
            try {
                startedModule.lifetime().close();
            } catch (Throwable failure) {
                logCleanupFailure("module " + startedModule.name(), failure);
            }
        }
        startedModules.clear();
        modules.clear();

        Registration registration = unloadingRegistration;
        unloadingRegistration = null;
        if (registration != null) {
            try {
                if (registration.isRegistered()) {
                    registration.deregister();
                }
            } catch (Throwable failure) {
                logCleanupFailure("extension unload registration", failure);
            }
        }
    }

    private void rollbackStartedModules(Throwable startupFailure) {
        for (int index = startedModules.size() - 1; index >= 0; index--) {
            try {
                startedModules.get(index).lifetime().close();
            } catch (Throwable cleanupFailure) {
                addSuppressed(startupFailure, cleanupFailure);
            }
        }
        startedModules.clear();
    }

    private void closeAfterFailure(ModuleLifetime lifetime, Throwable startupFailure) {
        try {
            lifetime.close();
        } catch (Throwable cleanupFailure) {
            addSuppressed(startupFailure, cleanupFailure);
        }
    }

    private void addSuppressed(Throwable failure, Throwable suppressed) {
        if (failure != suppressed) {
            failure.addSuppressed(suppressed);
        }
    }

    private void logCleanupFailure(String resource, Throwable failure) {
        try {
            api.logging().logToError(
                    "Burp Workbench cleanup failed for " + resource + ": " + failure,
                    failure
            );
        } catch (Throwable ignored) {
            // Extension unload must continue even if logging is unavailable.
        }
    }

    private enum State {
        NEW,
        STARTING,
        STARTED,
        CLOSED
    }

    private record StartedModule(String name, ModuleLifetime lifetime) {
    }
}
