package com.burpworkbench.platform;

import burp.api.montoya.MontoyaApi;

import java.util.ArrayList;
import java.util.List;

public final class ModuleRegistry {
    private final MontoyaApi api;
    private final List<WorkbenchModule> modules = new ArrayList<>();
    private ExtractionHandler extractionHandler;

    public ModuleRegistry(MontoyaApi api) {
        this.api = api;
    }

    public void register(WorkbenchModule module) {
        modules.add(module);
    }

    public void start() {
        ModuleContext context = new ModuleContext(api, this);
        for (WorkbenchModule module : modules) {
            module.initialize(context);
            api.logging().logToOutput("Burp Workbench module loaded: " + module.name());
        }
    }

    void registerExtractionHandler(ExtractionHandler extractionHandler) {
        this.extractionHandler = extractionHandler;
    }

    ExtractionHandler extractionHandler() {
        return extractionHandler;
    }
}
