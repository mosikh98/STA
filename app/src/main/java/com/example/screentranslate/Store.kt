package com.example.screentranslate

import android.content.Context

/** Active, fully-resolved configuration (this is what the service and Api use). */
data class Cfg(
    val url: String,
    val key: String,
    val model: String,
    val lang: String,
    val extra: String,
    val boxMode: Int   // 0: [ymin,xmin,ymax,xmax]/1000, 1: [xmin,ymin,xmax,ymax]/1000, 2: pixels, 3: text panel only
)

/** Per-provider settings: Gemini keeps its own key/model, Custom keeps its own URL/key/model. */
data class Profile(val url: String, val key: String, val model: String)

object Store {
    const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
    const val GEMINI = 0
    const val CUSTOM = 1

    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun provider(c: Context): Int = p(c).getInt("provider", GEMINI)
    fun setProvider(c: Context, v: Int) { p(c).edit().putInt("provider", v).apply() }

    fun profile(c: Context, prov: Int): Profile {
        val s = p(c)
        return if (prov == GEMINI)
            Profile(GEMINI_URL, s.getString("g_key", "")!!, s.getString("g_model", "gemini-2.5-flash")!!)
        else
            Profile(
                s.getString("c_url", "https://api.openai.com/v1")!!,
                s.getString("c_key", "")!!,
                s.getString("c_model", "gpt-4o-mini")!!
            )
    }

    fun saveProfile(c: Context, prov: Int, pr: Profile) {
        val e = p(c).edit()
        if (prov == GEMINI) e.putString("g_key", pr.key).putString("g_model", pr.model)
        else e.putString("c_url", pr.url).putString("c_key", pr.key).putString("c_model", pr.model)
        e.apply()
    }

    fun saveShared(c: Context, lang: String, extra: String, boxMode: Int) {
        p(c).edit().putString("lang", lang).putString("extra", extra).putInt("boxMode", boxMode).apply()
    }

    fun load(c: Context): Cfg {
        val s = p(c)
        val pr = profile(c, provider(c))
        return Cfg(
            pr.url, pr.key, pr.model,
            s.getString("lang", "Persian")!!,
            s.getString("extra", "")!!,
            s.getInt("boxMode", 0)
        )
    }
}
