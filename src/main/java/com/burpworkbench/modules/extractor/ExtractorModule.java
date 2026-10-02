package com.burpworkbench.modules.extractor;

import burp.api.montoya.core.Registration;
import burp.api.montoya.http.message.HttpRequestResponse;
import com.burpworkbench.platform.ExtractionHandler;
import com.burpworkbench.platform.ModuleContext;
import com.burpworkbench.platform.ModuleLifetime;
import com.burpworkbench.platform.WorkbenchModule;

import java.util.List;

public final class ExtractorModule implements WorkbenchModule {
    private volatile ExtractorContextMenuProvider activeProvider;
    private final ExtractionHandler extractionHandler = this::extract;

    public ExtractionHandler extractionHandler() {
        return extractionHandler;
    }

    @Override
    public String name() {
        return "Extractor";
    }

    @Override
    public void initialize(ModuleContext context, ModuleLifetime lifetime) {
        if (activeProvider != null) {
            throw new IllegalStateException("Extractor module is already initialized");
        }

        com.burpworkbench.modules.extractor.filter.RulesPanel[] holder = new com.burpworkbench.modules.extractor.filter.RulesPanel[1];
        Runnable create = () -> {
            holder[0] = new com.burpworkbench.modules.extractor.filter.RulesPanel(context.api());
            lifetime.onClose(() -> {
                if(javax.swing.SwingUtilities.isEventDispatchThread())holder[0].close();
                else try{javax.swing.SwingUtilities.invokeAndWait(holder[0]::close);}
                catch(InterruptedException e){Thread.currentThread().interrupt();javax.swing.SwingUtilities.invokeLater(holder[0]::close);}
                catch(java.lang.reflect.InvocationTargetException e){throw new IllegalStateException(e.getCause());}
            });
            context.api().userInterface().applyThemeToComponent(holder[0]);
            holder[0].applyFont();
            lifetime.own(context.api().userInterface().registerSuiteTab("Extractor", holder[0]));
        };
        if (javax.swing.SwingUtilities.isEventDispatchThread()) create.run();
        else try { javax.swing.SwingUtilities.invokeAndWait(create); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        catch (java.lang.reflect.InvocationTargetException e) { throw new IllegalStateException(e.getCause()); }
        ExtractorContextMenuProvider provider = new ExtractorContextMenuProvider(context.api(), holder[0]::snapshot);
        activeProvider = provider;
        try {
            Registration registration =
                    context.api().userInterface().registerContextMenuItemsProvider(provider);
            lifetime.own(registration);
            lifetime.own(provider);
            lifetime.onClose(() -> {
                if (activeProvider == provider) {
                    activeProvider = null;
                }
            });
        } catch (RuntimeException | Error failure) {
            activeProvider = null;
            try {
                provider.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    private boolean extract(List<HttpRequestResponse> requestResponses) {
        ExtractorContextMenuProvider provider = activeProvider;
        return provider != null && provider.chooseDirectoryAndExportSelection(requestResponses);
    }
}
