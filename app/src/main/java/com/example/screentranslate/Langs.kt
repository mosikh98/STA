package com.example.screentranslate

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

data class Lang(val code: String, val fa: String, val en: String)

/** Language list (ML Kit's translation languages) with Persian + English display names. */
object Langs {
    private val faLocale = Locale.forLanguageTag("fa")

    /** Languages the on-device OCR can read: Chinese, Japanese, Korean and Latin-script languages. */
    private val ocrCodes = setOf(
        "zh", "ja", "ko",
        "af", "sq", "ca", "hr", "cs", "da", "nl", "en", "eo", "et", "fi", "fr", "gl", "de", "ht", "hu",
        "is", "id", "ga", "it", "lv", "lt", "ms", "mt", "no", "pl", "pt", "ro", "sk", "sl", "es", "sw",
        "sv", "tl", "tr", "vi", "cy"
    )

    private fun mk(code: String): Lang {
        val loc = Locale.forLanguageTag(code)
        return Lang(code, loc.getDisplayLanguage(faLocale), loc.getDisplayLanguage(Locale.ENGLISH))
    }

    val all: List<Lang> by lazy { TranslateLanguage.getAllLanguages().map { mk(it) }.sortedBy { it.en } }

    val ocrInput: List<Lang> by lazy { all.filter { it.code in ocrCodes } }

    fun find(code: String): Lang = all.firstOrNull { it.code == code } ?: mk(code)

    fun label(code: String): String =
        if (code == "auto") "تشخیص خودکار (Auto)" else find(code).let { "${it.fa} — ${it.en}" }

    /** English name, used inside prompts. */
    fun enName(code: String): String = find(code).en

    /** 0 Chinese+Latin, 1 Latin, 2 Japanese+Latin, 3 Korean+Latin */
    fun ocrScript(src: String): Int = when (src) {
        "ja" -> 2
        "ko" -> 3
        "auto", "zh" -> 0
        else -> 1
    }
}
