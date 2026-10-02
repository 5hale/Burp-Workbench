package com.burpworkbench.modules.compare;

import com.burpworkbench.platform.ModuleContext;
import com.burpworkbench.platform.ModuleLifetime;
import com.burpworkbench.platform.WorkbenchModule;

/** Read-only comparison, independently owned by the Workbench lifecycle. */
public final class ComparePlusModule implements WorkbenchModule {
    @Override public String name() { return "Compare++"; }

    @Override public void initialize(ModuleContext context, ModuleLifetime lifetime) {
        CompareRuntime runtime = lifetime.own(new CompareRuntime(context.api()));
        lifetime.own(context.menus().register(com.burpworkbench.platform.WorkbenchMenus.Command.COMPARE,runtime::fromMenu));
        try { runtime.start(); }
        catch (RuntimeException | LinkageError failure) {
            runtime.close();
            try { context.api().logging().logToError("Compare++ startup failed; other modules remain available.", failure); }
            catch (RuntimeException | LinkageError ignored) { }
        }
    }
}
