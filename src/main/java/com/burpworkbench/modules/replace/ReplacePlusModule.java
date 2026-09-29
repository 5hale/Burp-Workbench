package com.burpworkbench.modules.replace;

import com.burpworkbench.platform.ModuleContext;
import com.burpworkbench.platform.ModuleLifetime;
import com.burpworkbench.platform.WorkbenchModule;

/** Optional Proxy UI integration; a startup failure must not disable the other modules. */
public final class ReplacePlusModule implements WorkbenchModule {
    @Override public String name() { return "Replace++"; }

    @Override public void initialize(ModuleContext context, ModuleLifetime lifetime) {
        ReplaceRuntime runtime = lifetime.own(new ReplaceRuntime(context.api()));
        try {
            runtime.start();
        } catch (RuntimeException | LinkageError failure) {
            try {
                runtime.close();
            } catch (RuntimeException | LinkageError cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            try {
                context.api().logging().logToError("Replace++ startup failed; Extractor and Search++ remain available.", failure);
            } catch (RuntimeException | LinkageError ignored) {
                // Optional module startup must remain isolated even if host logging is unavailable.
            }
        }
    }
}
