package com.uvpro.plugin;

import android.content.Context;

import com.atak.plugins.impl.AbstractPluginTool;

public class UVProTool extends AbstractPluginTool {
    public UVProTool(Context context) {
        super(context,
                context.getString(context.getResources().getIdentifier(
                        "app_name", "string", context.getPackageName())),
                context.getString(context.getResources().getIdentifier(
                        "plugin_description", "string", context.getPackageName())),
                context.getResources().getDrawable(context.getResources().getIdentifier(
                        "ic_uvpro", "drawable", context.getPackageName()), null),
                UVProDropDownReceiver.SHOW_PLUGIN);
    }

    public void dispose() {
    }
}
