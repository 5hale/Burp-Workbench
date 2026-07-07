package com.burpworkbench;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.burpworkbench.modules.extractor.ExtractorModule;
import com.burpworkbench.modules.search.SearchPlusModule;
import com.burpworkbench.platform.ModuleRegistry;

public final class BurpWorkbenchExtension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Burp Workbench");
        ModuleRegistry registry = new ModuleRegistry(api);
        registry.register(new ExtractorModule());
        registry.register(new SearchPlusModule());
        registry.start();
        api.logging().logToOutput("Burp Workbench loaded. Modules: Extractor, Search++.");
    }
}
