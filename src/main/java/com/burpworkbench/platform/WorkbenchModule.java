package com.burpworkbench.platform;

public interface WorkbenchModule {
    String name();

    void initialize(ModuleContext context);
}
