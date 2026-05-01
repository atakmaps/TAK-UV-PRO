package com.uvpro.plugin;

import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;
import gov.tak.api.plugin.IServiceController;

public class UVProLifecycle extends AbstractPlugin {
    public UVProLifecycle(IServiceController serviceController) {
        super(serviceController,
                new UVProTool(serviceController.getService(
                        PluginContextProvider.class).getPluginContext()),
                new UVProMapComponent());
    }
}
