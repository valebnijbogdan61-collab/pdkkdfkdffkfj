package com.example.gamebutton.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class OcrItem(val text: String, val box: Rect)
data class OcrResult(val lines: List<OcrItem>, val words: List<OcrItem>)

/** Офлайн OCR (ML Kit, латиниця + цифри). Блокуючий — викликати з фону. */
class OcrEngine {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun scan(bmp: Bitmap): OcrResult {
        val t = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)))
        val lines = ArrayList<OcrItem>()
        val words = ArrayList<OcrItem>()
        for (b in t.textBlocks) for (l in b.lines) {
            l.boundingBox?.let { lines.add(OcrItem(l.text, it)) }
            for (el in l.elements) el.boundingBox?.let { words.add(OcrItem(el.text, it)) }
        }
        return OcrResult(lines, words)
    }
}
