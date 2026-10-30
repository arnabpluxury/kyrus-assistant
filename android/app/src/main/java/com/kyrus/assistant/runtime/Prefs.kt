package com.kyrus.assistant.runtime

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kyrus.assistant.runtime.llm.LlmBackend

class Prefs(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("kyrus", Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy {
        try {
            val master = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                "kyrus_secure",
                master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            app.getSharedPreferences("kyrus_secure_fallback", Context.MODE_PRIVATE)
        }
    }

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

    fun sessionAllow(kind: String): Boolean? =
        if (!sp.contains("sess_$kind")) null else sp.getBoolean("sess_$kind", false)

    fun setSessionAllow(kind: String, v: Boolean?) {
        val e = sp.edit()
        val key = "sess_$kind"
        if (v == null) e.remove(key) else e.putBoolean(key, v)
        e.apply()
    }

    fun voiceReplies(): Boolean = sp.getBoolean("voice_replies", false)

    fun setVoiceReplies(v: Boolean) {
        sp.edit().putBoolean("voice_replies", v).apply()
    }

    fun preferLlm(): Boolean = sp.getBoolean("prefer_llm", true)

    fun setPreferLlm(v: Boolean) {
        sp.edit().putBoolean("prefer_llm", v).apply()
    }

    fun llmBackend(): LlmBackend {
        val raw = sp.getString("llm_backend", LlmBackend.Local.name) ?: LlmBackend.Local.name
        return runCatching { LlmBackend.valueOf(raw) }.getOrDefault(LlmBackend.Local)
    }

    fun setLlmBackend(b: LlmBackend) {
        sp.edit().putString("llm_backend", b.name).apply()
    }

    fun cloudModel(): String = sp.getString("cloud_model", "") ?: ""

    fun setCloudModel(m: String) {
        sp.edit().putString("cloud_model", m).apply()
    }

    fun customBaseUrl(): String = sp.getString("custom_base_url", "") ?: ""

    fun setCustomBaseUrl(u: String) {
        sp.edit().putString("custom_base_url", u).apply()
    }

    fun apiKey(provider: String): String? = secure.getString("key_$provider", null)

    fun setApiKey(provider: String, key: String?) {
        val e = secure.edit()
        if (key.isNullOrBlank()) e.remove("key_$provider") else e.putString("key_$provider", key.trim())
        e.apply()
    }

    fun activeConversationId(): String? = sp.getString("active_conversation", null)

    fun setActiveConversationId(id: String?) {
        val e = sp.edit()
        if (id == null) e.remove("active_conversation") else e.putString("active_conversation", id)
        e.apply()
    }

    fun dlpStrict(): Boolean = sp.getBoolean("dlp_strict", true)

    fun setDlpStrict(v: Boolean) {
        sp.edit().putBoolean("dlp_strict", v).apply()
    }

    /** Persisted SAF tree URIs (comma-separated). */
    fun mountedTrees(): List<String> =
        sp.getString("saf_trees", "")?.split("|")?.filter { it.isNotBlank() }.orEmpty()

    fun setMountedTrees(uris: List<String>) {
        sp.edit().putString("saf_trees", uris.joinToString("|")).apply()
    }
}
