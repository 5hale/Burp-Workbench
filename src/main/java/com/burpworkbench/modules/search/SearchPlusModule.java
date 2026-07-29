package com.burpworkbench.modules.search;

import burp.api.montoya.core.Registration;
import com.burpworkbench.platform.ExtractionHandler;
import com.burpworkbench.platform.ModuleContext;
import com.burpworkbench.platform.ModuleLifetime;
import com.burpworkbench.platform.WorkbenchModule;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SearchPlusModule implements WorkbenchModule {
    static final String MONTOYA_COMPILE_BASELINE = "2025.8";

    private final ExtractionHandler extractionHandler;

    public SearchPlusModule(ExtractionHandler extractionHandler) {
        this.extractionHandler = Objects.requireNonNull(extractionHandler, "extractionHandler");
    }

    @Override
    public String name() {
        return "Search++";
    }

    @Override
    public void initialize(ModuleContext context, ModuleLifetime lifetime) {
        ProxyHistoryPartitioner proxyHistoryPartitioner =
                ProxyHistoryPartitioner.runtime();
        context.api().logging().logToOutput(
                compatibilityLogLine(
                        readBurpVersion(context),
                        proxyHistoryPartitioner.mode()
                )
        );
        RepeaterCache repeaterCache = new RepeaterCache();
        SearchExecutionCoordinator coordinator = lifetime.own(new SearchExecutionCoordinator());
        SearchPlusContextMenuProvider provider = lifetime.own(new SearchPlusContextMenuProvider(
                context.api(),
                repeaterCache,
                extractionHandler,
                coordinator
        ));
        SearchModuleResources resources = lifetime.own(new SearchModuleResources(provider));
        SearchPlusMenuInstaller installer = new SearchPlusMenuInstaller(context.api(), provider::openSearchPlus);
        resources.own(installer);
        Registration registration =
                context.api().userInterface().registerContextMenuItemsProvider(provider);
        resources.own(registration);
        installer.install();
    }

    static String compatibilityLogLine(
            String burpVersion,
            ProxyHistoryPartitioner.Mode mode
    ) {
        String safeBurpVersion =
                burpVersion == null || burpVersion.isBlank() ? "unknown" : burpVersion;
        return "Search++ compatibility: burpVersion=" + safeBurpVersion
                + " proxyPartitionMode=" + mode
                + " montoyaCompileBaseline=" + MONTOYA_COMPILE_BASELINE;
    }

    private static String readBurpVersion(ModuleContext context) {
        try {
            Object version = context.api().burpSuite().version();
            return version == null ? "unknown" : version.toString();
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    /**
     * Keeps startup rollback and normal unload on the same path.
     *
     * <p>The provider is also owned directly by the module lifetime so there is
     * no gap between construction and ownership. Its close operation is
     * idempotent; this guard closes it first, then removes the menu and context
     * registration before the lifetime finally closes the coordinator.</p>
     */
    static final class SearchModuleResources implements AutoCloseable {
        private final AutoCloseable provider;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Runnable uninstallMenu;
        private Registration registration;

        SearchModuleResources(AutoCloseable provider) {
            this.provider = Objects.requireNonNull(provider, "provider");
        }

        void own(SearchPlusMenuInstaller installer) {
            ownMenuCleanup(Objects.requireNonNull(installer, "installer")::uninstall);
        }

        void ownMenuCleanup(Runnable uninstallMenu) {
            this.uninstallMenu = Objects.requireNonNull(uninstallMenu, "uninstallMenu");
        }

        void own(Registration registration) {
            this.registration = Objects.requireNonNull(registration, "registration");
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            Throwable firstFailure = closeProvider();
            firstFailure = runCleanup(firstFailure, () -> {
                if (uninstallMenu != null) {
                    uninstallMenu.run();
                }
            });
            firstFailure = runCleanup(firstFailure, () -> {
                if (registration != null && registration.isRegistered()) {
                    registration.deregister();
                }
            });
            rethrow(firstFailure);
        }

        private Throwable closeProvider() {
            try {
                provider.close();
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        }

        private static Throwable runCleanup(Throwable firstFailure, Runnable cleanup) {
            try {
                cleanup.run();
            } catch (Throwable failure) {
                if (firstFailure == null) {
                    return failure;
                }
                if (failure != firstFailure) {
                    firstFailure.addSuppressed(failure);
                }
            }
            return firstFailure;
        }

        private static void rethrow(Throwable failure) {
            if (failure == null) {
                return;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Search++ cleanup failed", failure);
        }
    }
}
