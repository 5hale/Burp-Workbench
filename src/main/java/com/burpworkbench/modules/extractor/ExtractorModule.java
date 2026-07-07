package com.burpworkbench.modules.extractor;

import com.burpworkbench.platform.ModuleContext;
import com.burpworkbench.platform.WorkbenchModule;

public final class ExtractorModule implements WorkbenchModule {
    @Override
    public String name() {
        return "Extractor";
    }

    @Override
    public void initialize(ModuleContext context) {
        ExtractorContextMenuProvider provider = new ExtractorContextMenuProvider(context.api());
        context.api().userInterface().registerContextMenuItemsProvider(provider);
        context.registerExtractionHandler(provider::chooseDirectoryAndExportSelection);
    }
}
