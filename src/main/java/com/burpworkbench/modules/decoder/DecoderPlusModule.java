package com.burpworkbench.modules.decoder;

import com.burpworkbench.platform.ModuleContext;
import com.burpworkbench.platform.ModuleLifetime;
import com.burpworkbench.platform.WorkbenchModule;

public final class DecoderPlusModule implements WorkbenchModule {
    @Override public String name(){return "Decoder++";}
    @Override public void initialize(ModuleContext context,ModuleLifetime lifetime){
        DecoderRuntime runtime=lifetime.own(new DecoderRuntime(context.api()));
        lifetime.own(context.menus().register(com.burpworkbench.platform.WorkbenchMenus.Command.DECODE,source->runtime.fromMenu(0,source)));
        lifetime.own(context.menus().register(com.burpworkbench.platform.WorkbenchMenus.Command.ADVANCED,source->runtime.fromMenu(1,source)));
        try{runtime.start();}
        catch(RuntimeException|LinkageError failure){
            runtime.close();
            try{context.api().logging().logToError("Decoder++ startup failed; other modules remain available.",failure);}catch(RuntimeException|LinkageError ignored){}
        }
    }
}
