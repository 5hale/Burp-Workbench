package com.burpworkbench.platform;

import burp.api.montoya.MontoyaApi;

public final class ModuleContext {
    private final MontoyaApi api;
    private final WorkbenchMenus menus;

    ModuleContext(MontoyaApi api) {
        this(api,new WorkbenchMenus());
    }
    ModuleContext(MontoyaApi api,WorkbenchMenus menus) {
        this.api = api;
        this.menus = menus;
    }

    public MontoyaApi api() {
        return api;
    }
    public WorkbenchMenus menus(){return menus;}
}
