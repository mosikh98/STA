package com.example.screentranslate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import java.util.concurrent.CountDownLatch

/** Fully on-device translation (ML Kit). Language models are downloaded once, then work offline. */
object Offline {
    private val translators = HashMap<String, Translator>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> await(task: Task<T>): T {
        val latch = CountDownLatch(1)
        var res: T? = null
        var err: Exception? = null
        task.addOnSuccessListener { res = it; latch.countDown() }
            .addOnFailureListener { err = it; latch.countDown() }
        latch.await()
        err?.let { throw it }
        return res as T
    }

    /** "Persian" / "fa" -> "fa" (null if ML Kit has no such language). */
    fun targetCode(lang: String): String? {
        val q = lang.trim().lowercase()
        return TranslateLanguage.getAllLanguages().firstOrNull { code ->
            code == q || Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).lowercase() == q
        }
    }

    private fun translator(src: String, dst: String): Translator = synchronized(translators) {
        translators.getOrPut("$src>$dst") {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()
            )
        }
    }

    /** Script-based guess; returns null for plain Latin text (then the language identifier decides). */
    private fun guessCjk(t: String): String? = when {
        t.any { it in '\u3040'..'\u30FF' } -> TranslateLanguage.JAPANESE
        t.any { it in '\uAC00'..'\uD7AF' } -> TranslateLanguage.KOREAN
        t.any { it in '\u4E00'..'\u9FFF' } -> TranslateLanguage.CHINESE
        else -> null
    }

    /** Blocking: call from a background thread. onResult(index, translation) is called per block. */
    fun translate(
        lang: String, blocks: List<OcrBlock>,
        isCancelled: () -> Boolean, onStatus: (String) -> Unit, onResult: (Int, String) -> Unit
    ) {
        val dst = targetCode(lang) ?: throw IllegalArgumentException(
            "زبان مقصد «$lang» برای ترجمه آفلاین پشتیبانی نمی‌شود (نام انگلیسی زبان را بنویسید، مثلاً Persian)"
        )
        val idClient = LanguageIdentification.getClient()
        val ready = HashSet<String>()
        for ((i, b) in blocks.withIndex()) {
            if (isCancelled()) return
            val src = guessCjk(b.text) ?: run {
                val tag = runCatching { await(idClient.identifyLanguage(b.text)) }.getOrDefault("und")
                if (tag == "und") TranslateLanguage.ENGLISH
                else TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.ENGLISH
            }
            if (src == dst) continue
            val tr = translator(src, dst)
            if (ready.add(src)) {
                onStatus("در حال آماده‌سازی مدل ترجمه ($src→$dst)؛ بار اول دانلود می‌شود")
                await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            }
            if (isCancelled()) return
            val out = await(tr.translate(b.text))
            if (out.isNotBlank()) onResult(i, out)
        }
    }
}
