package com.example.screentranslate

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch

/** One recognised text block; box coordinates are fractions (0..1) of the screenshot. */
data class OcrBlock(val text: String, val l: Float, val t: Float, val r: Float, val b: Float)

/** On-device OCR (ML Kit, models bundled in the APK). */
object Ocr {
    private val cache = HashMap<Int, TextRecognizer>()

    private fun client(script: Int): TextRecognizer = synchronized(cache) {
        cache.getOrPut(script) {
            when (script) {
                1 -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                2 -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                3 -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            }
        }
    }

    /** Blocking: call from a background thread. */
    fun recognize(bmp: Bitmap, script: Int): List<OcrBlock> {
        val latch = CountDownLatch(1)
        var result: com.google.mlkit.vision.text.Text? = null
        var error: Exception? = null
        client(script).process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result = it; latch.countDown() }
            .addOnFailureListener { error = it; latch.countDown() }
        latch.await()
        error?.let { throw it }

        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val out = ArrayList<OcrBlock>()
        for (b in result?.textBlocks.orEmpty()) {
            val s = b.lines.joinToString(" ") { it.text }.trim()
            val box = b.boundingBox ?: continue
            if (s.none { it.isLetter() }) continue          // skip pure numbers / symbols
            out.add(OcrBlock(
                s,
                (box.left / w).coerceIn(0f, 1f), (box.top / h).coerceIn(0f, 1f),
                (box.right / w).coerceIn(0f, 1f), (box.bottom / h).coerceIn(0f, 1f)
            ))
            if (out.size >= 60) break
        }
        return out
    }
}
