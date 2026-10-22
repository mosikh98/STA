package com.example.screentranslate

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Talks to any OpenAI-compatible /chat/completions endpoint. */
object Api {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Same client but with a hard 30s limit for the whole call (used by test / model list). */
    private val fast = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    private fun endpoint(u: String): String {
        val t = u.trim().trimEnd('/')
        return if (t.endsWith("/chat/completions")) t else "$t/chat/completions"
    }

    private fun post(cfg: Cfg, body: JSONObject, client: OkHttpClient = http): String {
        val req = Request.Builder()
            .url(endpoint(cfg.url))
            .header("Authorization", "Bearer ${cfg.key.trim()}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${txt.take(300)}")
            return JSONObject(txt).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        }
    }

    fun test(cfg: Cfg): Result<String> = runCatching {
        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", 5)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", "Reply with OK")))
        post(cfg, body, fast)
    }

    /** w,h = size of the image being sent (needed for pixel-coordinate mode). */
    fun translate(cfg: Cfg, jpegBase64: String, w: Int, h: Int): Result<String> = runCatching {
        val extra = if (cfg.extra.isNotBlank()) "\nExtra instructions: ${cfg.extra}" else ""
        val system = if (cfg.boxMode == 3) {
            "You are a screen translator. Read all visible text in the screenshot and translate it " +
            "into ${cfg.lang}. Output only the translation, in on-screen order.$extra"
        } else {
            val fmt = when (cfg.boxMode) {
                0 -> "[ymin, xmin, ymax, xmax] normalized to 0-1000"
                1 -> "[xmin, ymin, xmax, ymax] normalized to 0-1000"
                else -> "[xmin, ymin, xmax, ymax] in pixels of the image (${w}x${h})"
            }
            "You are a screen translator. Find every separate piece of text in the screenshot " +
            "(UI labels, quest text, chat, tooltips...) and translate it into ${cfg.lang}. " +
            "Reply with ONLY a JSON array, no markdown fences, in the form " +
            "[{\"text\":\"<translation>\",\"box\":[a,b,c,d]}]. " +
            "box is the tight bounding box of the ORIGINAL text, $fmt. " +
            "Merge a multi-line paragraph into one item. Skip text already in ${cfg.lang} and skip pure numbers.$extra"
        }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Translate the text in this screenshot into ${cfg.lang}."))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64")))
        val body = JSONObject()
            .put("model", cfg.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", content)))
        post(cfg, body)
    }

    /** GET {base}/models -> list of model ids available for this key (OpenAI-compatible shape). */
    fun listModels(cfg: Cfg, geminiOnly: Boolean): Result<List<String>> = runCatching {
        var base = cfg.url.trim().trimEnd('/')
        if (base.endsWith("/chat/completions")) base = base.removeSuffix("/chat/completions")
        val req = Request.Builder()
            .url("$base/models")
            .header("Authorization", "Bearer ${cfg.key.trim()}")
            .get()
            .build()
        fast.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${txt.take(300)}")
            val arr = JSONObject(txt).getJSONArray("data")
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val id = arr.getJSONObject(i).optString("id").removePrefix("models/")
                if (id.isEmpty()) continue
                if (geminiOnly) {
                    val l = id.lowercase()
                    if (!l.contains("gemini")) continue
                    if (listOf("embedding", "tts", "imagen", "veo", "aqa").any { l.contains(it) }) continue
                }
                out.add(id)
            }
            out.sorted()
        }
    }
}
