package com.burpworkbench.platform;

import burp.api.montoya.MontoyaApi;

public final class ModuleContext {
    private final MontoyaApi api;

    ModuleContext(MontoyaApi api) {
        this.api = api;
    }

    public MontoyaApi api() {
        return api;
    }
}
