package com.uvpro.plugin.contacts;

import com.atakmap.android.contact.Connector;
import com.uvpro.plugin.R;

/**
 * Contact-card action to share GRG Builder settings JSON over RF.
 */
public final class MeshGrgShareConnector extends Connector {

    public static final String CONNECTOR_TYPE = "connector.uvpro.grg_share";
    private static final String PACKAGE = "com.uvpro.plugin";

    @Override
    public String getConnectionString() {
        return CONNECTOR_TYPE;
    }

    @Override
    public String getConnectionType() {
        return CONNECTOR_TYPE;
    }

    @Override
    public String getConnectionLabel() {
        return "Share GRG";
    }

    @Override
    public String getIconUri() {
        String cached = ContactConnectorIcons.getUvproRadioIconUri(null);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return "android.resource://" + PACKAGE + "/" + R.drawable.ic_uvpro;
    }
}
