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

    /** Same client but with a hard limit for the whole call (used by test / model list). */
    private val fast = http.newBuilder().callTimeout(90, TimeUnit.SECONDS).build()

    private fun endpoint(u: String): String {
        val t = u.trim().trimEnd('/')
        return if (t.endsWith("/chat/completions")) t else "$t/chat/completions"
    }

    private fun buildReq(cfg: Cfg, body: JSONObject): Request =
        Request.Builder()
            .url(endpoint(cfg.url))
            .header("Authorization", "Bearer ${cfg.key.trim()}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

    private fun post(cfg: Cfg, body: JSONObject, client: OkHttpClient = http): String {
        client.newCall(buildReq(cfg, body)).execute().use { r ->
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

    private fun extraOf(cfg: Cfg) =
        if (cfg.extra.isNotBlank()) "\nExtra instructions: ${cfg.extra}" else ""

    /** Retries a few times on 503/429 if nothing was received yet. */
    private fun runStream(
        cfg: Cfg, body: JSONObject, isCancelled: () -> Boolean, onDelta: (String) -> Unit
    ): String {
        var attempt = 0
        var result: String? = null
        while (result == null) {
            var emitted = false
            try {
                result = stream(cfg, body, isCancelled) { emitted = true; onDelta(it) }
            } catch (e: IOException) {
                val m = e.message.orEmpty()
                val retry = !emitted && attempt < 2 && !isCancelled() &&
                    (m.startsWith("HTTP 503") || m.startsWith("HTTP 429"))
                if (!retry) throw e
                attempt++
                Thread.sleep(1500L * attempt)
            }
        }
        return result!!
    }

    /**
     * Vision method: the model reads the screenshot itself. Replies one item per line,
     * "a,b,c,d|translation" (compact, so fewer tokens are generated), streamed.
     */
    fun translate(
        cfg: Cfg, jpegBase64: String, w: Int, h: Int,
        isCancelled: () -> Boolean, onDelta: (String) -> Unit
    ): Result<String> = runCatching {
        val extra = extraOf(cfg)
        val system = if (cfg.boxMode == 3) {
            "You are a screen translator. Read all visible text in the screenshot and translate it " +
            "into ${cfg.lang}. Output only the translation, in on-screen order.$extra"
        } else {
            val fmt = when (cfg.boxMode) {
                0 -> "[ymin, xmin, ymax, xmax] as integers normalized to 0-1000"
                1 -> "[xmin, ymin, xmax, ymax] as integers normalized to 0-1000"
                else -> "[xmin, ymin, xmax, ymax] in pixels of the image (${w}x${h})"
            }
            "You are a screen translator. Find every separate piece of text in the screenshot " +
            "(UI labels, quest text, chat, tooltips...) and translate it into ${cfg.lang}. " +
            "Reply with ONLY lines, one per text item, in the exact form a,b,c,d|translation " +
            "where a,b,c,d is the tight bounding box of the ORIGINAL text, $fmt. " +
            "No JSON, no markdown, no numbering, no other text. Merge a multi-line paragraph into one line. " +
            "Skip text already in ${cfg.lang} and skip pure numbers.$extra"
        }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Translate the text in this screenshot into ${cfg.lang}."))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64")))
        val body = JSONObject()
            .put("model", cfg.model)
            .put("stream", true)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", content)))
        cfg.effort?.let { body.put("reasoning_effort", it) }
        runStream(cfg, body, isCancelled, onDelta)
    }

    /**
     * OCR method: only text is sent (no image). Input lines "N|source", reply lines "N|translation".
     */
    fun translateText(
        cfg: Cfg, numberedLines: String,
        isCancelled: () -> Boolean, onDelta: (String) -> Unit
    ): Result<String> = runCatching {
        val system = "You are a screen translator for a phone game / app UI. Translate each numbered line " +
            "into ${cfg.lang}. Reply with exactly one line per input line, in the form N|translation, " +
            "using the same numbers and the same order. No extra text, no markdown. Keep translations short. " +
            "Leave numbers, symbols and untranslatable names as they are.${extraOf(cfg)}"
        val body = JSONObject()
            .put("model", cfg.model)
            .put("stream", true)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", numberedLines)))
        cfg.effort?.let { body.put("reasoning_effort", it) }
        runStream(cfg, body, isCancelled, onDelta)
    }

    private fun stream(
        cfg: Cfg, body: JSONObject, isCancelled: () -> Boolean, onDelta: (String) -> Unit
    ): String {
        val call = http.newCall(buildReq(cfg, body))
        call.execute().use { r ->
            if (!r.isSuccessful) {
                val txt = r.body?.string().orEmpty()
                throw IOException("HTTP ${r.code}: ${txt.take(300)}")
            }
            val src = r.body!!.source()
            val full = StringBuilder()
            if (r.header("Content-Type").orEmpty().contains("event-stream")) {
                while (true) {
                    val line = src.readUtf8Line() ?: break
                    if (isCancelled()) { call.cancel(); throw IOException("cancelled") }
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue
                    val j = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    if (j.has("error")) throw IOException("API: ${j.opt("error").toString().take(300)}")
                    val d = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    if (d != null && !d.isNull("content")) {
                        val s = d.optString("content")
                        if (s.isNotEmpty()) { full.append(s); onDelta(s) }
                    }
                }
            } else {
                // server ignored stream=true: plain JSON body
                val txt = src.readUtf8()
                val c = JSONObject(txt).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                full.append(c); onDelta(c)
            }
            return full.toString().trim()
        }
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
