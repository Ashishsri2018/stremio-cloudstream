package com.cs3addon.StreamioLink

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StremioPlugin : Plugin() {

    init {
        this.openSettings = { context ->
            StremioSettingsDialog.show(context)
        }
    }

    override fun load(context: Context) {
        // Register the provider once; it reads the settings on every request,
        // so changing addons does not require reloading the plugin.
        registerMainAPI(StremioProvider())
    }
}
