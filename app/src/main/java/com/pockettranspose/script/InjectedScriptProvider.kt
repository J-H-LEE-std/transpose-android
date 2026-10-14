package com.pockettranspose.script

import android.content.Context

enum class InjectionMode(val assetName: String) {
    /** Phase 1: detect <video>/<audio> only, never touches Web Audio. */
    PROBE("pocket_transpose_probe.js"),

    /** Phase 2+: route media through the Web Audio graph (gain, speed, pitch). */
    HOOK("pocket_transpose_hook.js")
}

class InjectedScriptProvider(private val context: Context) {
    private val cache = mutableMapOf<InjectionMode, String>()

    fun script(mode: InjectionMode): String = cache.getOrPut(mode) {
        context.assets.open(mode.assetName).bufferedReader().use { it.readText() }
    }
}
