package com.btechrelay.plugin;

import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;
import gov.tak.api.plugin.IServiceController;

public class BtechRelayLifecycle extends AbstractPlugin {
    public BtechRelayLifecycle(IServiceController serviceController) {
        super(serviceController,
                new BtechRelayTool(serviceController.getService(
                        PluginContextProvider.class).getPluginContext()),
                new BtechRelayMapComponent());
    }
}
