package com.example.screentranslate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.CountDownLatch

/** Download / delete / list the on-device translation models (callbacks run on the main thread). */
object Models {
    private val mgr get() = RemoteModelManager.getInstance()

    fun downloaded(cb: (Set<String>) -> Unit) {
        mgr.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { set -> cb(set.map { it.language }.toSet()) }
            .addOnFailureListener { cb(emptySet()) }
    }

    fun download(code: String, wifiOnly: Boolean, onDone: (Throwable?) -> Unit) {
        val model = TranslateRemoteModel.Builder(code).build()
        val cond = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        mgr.download(model, cond)
            .addOnSuccessListener { onDone(null) }
            .addOnFailureListener { onDone(it) }
    }

    fun delete(code: String, onDone: (Throwable?) -> Unit) {
        mgr.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build())
            .addOnSuccessListener { onDone(null) }
            .addOnFailureListener { onDone(it) }
    }
}

/** Fully on-device translation (ML Kit). */
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

    private fun translator(src: String, dst: String): Translator = synchronized(translators) {
        translators.getOrPut("$src>$dst") {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()
            )
        }
    }

    /** Script-based guess; null for plain Latin text (then the language identifier decides). */
    private fun guessCjk(t: String): String? = when {
        t.any { it in '\u3040'..'\u30FF' } -> TranslateLanguage.JAPANESE
        t.any { it in '\uAC00'..'\uD7AF' } -> TranslateLanguage.KOREAN
        t.any { it in '\u4E00'..'\u9FFF' } -> TranslateLanguage.CHINESE
        else -> null
    }

    /**
     * Blocking: call from a background thread. src is a language code or "auto".
     * onResult(index, translation) is called per block as soon as it is translated.
     */
    fun translate(
        src: String, dst: String, blocks: List<OcrBlock>,
        isCancelled: () -> Boolean, onStatus: (String) -> Unit, onResult: (Int, String) -> Unit
    ) {
        val supported = TranslateLanguage.getAllLanguages()
        if (dst !in supported) throw IllegalArgumentException("این زبان خروجی برای ترجمه آفلاین پشتیبانی نمی‌شود: $dst")
        val fixed = if (src == "auto") null else src
        if (fixed != null && fixed !in supported)
            throw IllegalArgumentException("این زبان ورودی برای ترجمه آفلاین پشتیبانی نمی‌شود: $fixed")
        val idClient = if (fixed == null) LanguageIdentification.getClient() else null
        val ready = HashSet<String>()

        for ((i, b) in blocks.withIndex()) {
            if (isCancelled()) return
            val s = fixed ?: guessCjk(b.text) ?: run {
                val tag = runCatching { await(idClient!!.identifyLanguage(b.text)) }.getOrDefault("und")
                if (tag == "und") TranslateLanguage.ENGLISH
                else TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.ENGLISH
            }
            if (s == dst) continue
            val tr = translator(s, dst)
            if (ready.add(s)) {
                // normally a no-op: models are downloaded from the language manager inside the app
                onStatus("در حال آماده‌سازی مدل ترجمه ($s→$dst)؛ اگر دانلود نشده باشد بار اول دانلود می‌شود")
                await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            }
            if (isCancelled()) return
            val out = await(tr.translate(b.text))
            if (out.isNotBlank()) onResult(i, out)
        }
    }
}
