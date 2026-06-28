package com.pockettranspose.script

import android.content.Context

class InjectedScriptProvider(private val context: Context) {
    fun pocketTransposeHook(): String =
        context.assets.open("pocket_transpose_probe.js").bufferedReader().use { it.readText() }
}
