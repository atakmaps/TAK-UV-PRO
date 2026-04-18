package com.btechrelay.plugin;

import android.content.Context;

import com.atak.plugins.impl.AbstractPluginTool;

public class BtechRelayTool extends AbstractPluginTool {
    public BtechRelayTool(Context context) {
        super(context,
                context.getString(context.getResources().getIdentifier(
                        "app_name", "string", context.getPackageName())),
                context.getString(context.getResources().getIdentifier(
                        "plugin_description", "string", context.getPackageName())),
                context.getResources().getDrawable(context.getResources().getIdentifier(
                        "ic_btechrelay", "drawable", context.getPackageName()), null),
                BtechRelayDropDownReceiver.SHOW_PLUGIN);
    }

    public void dispose() {
    }
}
