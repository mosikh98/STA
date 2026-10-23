package com.example.screentranslate

import android.content.Context

/** Active, fully-resolved configuration (this is what the service and Api use). */
data class Cfg(
    val url: String,
    val key: String,
    val model: String,
    val lang: String,
    val extra: String,
    val boxMode: Int,            // vision method: 0 [y1,x1,y2,x2]/1000, 1 [x1,y1,x2,y2]/1000, 2 pixels, 3 text panel only
    val effort: String? = null,  // reasoning_effort sent with translation requests (null = not sent)
    val imgWidth: Int = 1024,    // width of the screenshot sent to the model (vision method)
    val opaque: Boolean = true,  // fully opaque labels
    val method: Int = 1,         // 0 = vision model reads the image, 1 = on-phone OCR + text translation
    val ocrScript: Int = 0       // 0 Chinese+Latin, 1 Latin, 2 Japanese+Latin, 3 Korean+Latin
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

    fun effortIdx(c: Context) = p(c).getInt("effortIdx", 0)
    fun imgIdx(c: Context) = p(c).getInt("imgIdx", 1)
    fun opaque(c: Context) = p(c).getBoolean("opaque", true)
    fun methodIdx(c: Context) = p(c).getInt("method", 1)
    fun ocrIdx(c: Context) = p(c).getInt("ocrScript", 0)

    fun profile(c: Context, prov: Int): Profile {
        val s = p(c)
        return if (prov == GEMINI)
            Profile(GEMINI_URL, s.getString("g_key", "")!!, s.getString("g_model", "gemini-3.8-flash")!!)
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

    fun saveShared(
        c: Context, lang: String, extra: String, boxMode: Int,
        effortIdx: Int, imgIdx: Int, opaque: Boolean, method: Int, ocrScript: Int
    ) {
        p(c).edit().putString("lang", lang).putString("extra", extra).putInt("boxMode", boxMode)
            .putInt("effortIdx", effortIdx).putInt("imgIdx", imgIdx).putBoolean("opaque", opaque)
            .putInt("method", method).putInt("ocrScript", ocrScript).apply()
    }

    fun load(c: Context): Cfg {
        val s = p(c)
        val prov = provider(c)
        val pr = profile(c, prov)
        val effort = when (effortIdx(c)) {
            0 -> if (prov == GEMINI) "low" else null   // auto
            2 -> "minimal"
            3 -> "low"
            4 -> "medium"
            else -> null                                // 1 = don't send
        }
        val imgW = intArrayOf(800, 1024, 1280)[imgIdx(c).coerceIn(0, 2)]
        return Cfg(
            pr.url, pr.key, pr.model,
            s.getString("lang", "Persian")!!,
            s.getString("extra", "")!!,
            s.getInt("boxMode", 0),
            effort, imgW, opaque(c),
            methodIdx(c).coerceIn(0, 1), ocrIdx(c).coerceIn(0, 3)
        )
    }
}
