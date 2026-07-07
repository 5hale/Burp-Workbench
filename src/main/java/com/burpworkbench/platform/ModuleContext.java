package com.burpworkbench.platform;

import burp.api.montoya.MontoyaApi;

public final class ModuleContext {
    private final MontoyaApi api;
    private final ModuleRegistry registry;

    ModuleContext(MontoyaApi api, ModuleRegistry registry) {
        this.api = api;
        this.registry = registry;
    }

    public MontoyaApi api() {
        return api;
    }

    public void registerExtractionHandler(ExtractionHandler extractionHandler) {
        registry.registerExtractionHandler(extractionHandler);
    }

    public ExtractionHandler extractionHandler() {
        return registry.extractionHandler();
    }
}
