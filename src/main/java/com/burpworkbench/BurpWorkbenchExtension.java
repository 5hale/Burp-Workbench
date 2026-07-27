package com.burpworkbench;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.burpworkbench.modules.extractor.ExtractorModule;
import com.burpworkbench.modules.search.SearchPlusModule;
import com.burpworkbench.platform.ModuleRegistry;

public final class BurpWorkbenchExtension implements BurpExtension {
    private ModuleRegistry registry;

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Burp Workbench");
        ExtractorModule extractor = new ExtractorModule();
        ModuleRegistry newRegistry = new ModuleRegistry(api);
        newRegistry.register(extractor);
        newRegistry.register(new SearchPlusModule(extractor.extractionHandler()));
        registry = newRegistry;
        newRegistry.start();
        api.logging().logToOutput("Burp Workbench loaded. Modules: Extractor, Search++.");
    }
}
