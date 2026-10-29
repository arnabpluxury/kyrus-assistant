package com.kyrus.assistant.runtime

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("kyrus", Context.MODE_PRIVATE)

    fun isSetupComplete(): Boolean = sp.getBoolean("setup_complete", false)

    fun setSetupComplete(v: Boolean) {
        sp.edit().putBoolean("setup_complete", v).apply()
    }

    fun networkSessionAllow(): Boolean? =
        if (!sp.contains("net_session")) null else sp.getBoolean("net_session", false)

    fun setNetworkSessionAllow(v: Boolean?) {
        val e = sp.edit()
        if (v == null) e.remove("net_session") else e.putBoolean("net_session", v)
        e.apply()
    }
}
