package com.burpworkbench.modules.extractor;

import burp.api.montoya.core.Registration;
import burp.api.montoya.http.message.HttpRequestResponse;
import com.burpworkbench.platform.ExtractionHandler;
import com.burpworkbench.platform.ModuleContext;
import com.burpworkbench.platform.ModuleLifetime;
import com.burpworkbench.platform.WorkbenchModule;

import java.util.List;

public final class ExtractorModule implements WorkbenchModule {
    private volatile ExtractorContextMenuProvider activeProvider;
    private final ExtractionHandler extractionHandler = this::extract;

    public ExtractionHandler extractionHandler() {
        return extractionHandler;
    }

    @Override
    public String name() {
        return "Extractor";
    }

    @Override
    public void initialize(ModuleContext context, ModuleLifetime lifetime) {
        if (activeProvider != null) {
            throw new IllegalStateException("Extractor module is already initialized");
        }

        ExtractorContextMenuProvider provider = new ExtractorContextMenuProvider(context.api());
        activeProvider = provider;
        try {
            Registration registration =
                    context.api().userInterface().registerContextMenuItemsProvider(provider);
            lifetime.own(registration);
            lifetime.own(provider);
            lifetime.onClose(() -> {
                if (activeProvider == provider) {
                    activeProvider = null;
                }
            });
        } catch (RuntimeException | Error failure) {
            activeProvider = null;
            try {
                provider.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    private boolean extract(List<HttpRequestResponse> requestResponses) {
        ExtractorContextMenuProvider provider = activeProvider;
        return provider != null && provider.chooseDirectoryAndExportSelection(requestResponses);
    }
}
