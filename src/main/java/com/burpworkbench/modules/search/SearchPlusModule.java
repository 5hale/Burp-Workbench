package com.burpworkbench.modules.search;

import com.burpworkbench.platform.ExtractionHandler;
import com.burpworkbench.platform.ModuleContext;
import com.burpworkbench.platform.WorkbenchModule;

public final class SearchPlusModule implements WorkbenchModule {
    @Override
    public String name() {
        return "Search++";
    }

    @Override
    public void initialize(ModuleContext context) {
        RepeaterCache repeaterCache = new RepeaterCache();
        ExtractionHandler extractionHandler = context.extractionHandler();
        SearchPlusContextMenuProvider provider = new SearchPlusContextMenuProvider(context.api(), repeaterCache, extractionHandler);
        SearchPlusMenuInstaller installer = new SearchPlusMenuInstaller(context.api(), provider::openSearchPlus);
        context.api().userInterface().registerContextMenuItemsProvider(provider);
        installer.install();
        context.api().extension().registerUnloadingHandler(installer::uninstall);
    }
}
