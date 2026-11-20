package com.example.gamebutton.input

import android.graphics.PointF
import com.example.gamebutton.ocr.OcrItem
import com.example.gamebutton.ocr.OcrResult

/** Вирішує, що саме натиснути, за результатом OCR. Правила, 0 мережі. */
class ActionPlanner {
    data class Plan(val label: String, val points: List<PointF>)

    fun plan(r: OcrResult): Plan? = keypad(r) ?: dial(r) ?: prompt(r)

    private fun OcrItem.center() = PointF(box.exactCenterX(), box.exactCenterY())

    private fun digitRatio(s: String): Float {
        val t = s.filter { !it.isWhitespace() }
        return if (t.isEmpty()) 0f else t.count { it.isDigit() }.toFloat() / t.length
    }

    /** Код-підказка: рядок переважно з цифр, або рядок зі словом code/pin/pass. 4–8 цифр. */
    private fun findCode(r: OcrResult): Pair<String, OcrItem>? {
        for (l in r.lines) {
            val t = l.text.lowercase()
            val hinted = HINTS.any { it in t }
            if (!hinted && digitRatio(l.text) < 0.6f) continue
            val digits = l.text.filter { it.isDigit() }
            // ряд клавіш ("1234", "567890") — це не код
            if (!hinted && (ROW.contains(digits) || ROW_Z.contains(digits))) continue
            if (digits.length in 4..8) return digits to l
        }
        return null
    }

    /** Розкладає "123" / "1234567890" на окремі цифри вздовж рамки. */
    private fun spread(w: OcrItem, keys: HashMap<Char, PointF>) {
        val b = w.box
        val step = b.width() / w.text.length.toFloat()
        w.text.forEachIndexed { i, c ->
            keys.putIfAbsent(c, PointF(b.left + step * (i + 0.5f), b.exactCenterY()))
        }
    }

    /** Код на екрані + циферблат/клавіатура будь-де -> тапаємо цифри коду по черзі (+ OK). */
    private fun keypad(r: OcrResult): Plan? {
        val (code, line) = findCode(r) ?: return null
        val keys = HashMap<Char, PointF>()
        // слова самого коду не рахуємо клавішами
        val cands = r.words.filter { !line.box.contains(it.box.centerX(), it.box.centerY()) }
        for (w in cands) {
            val t = w.text
            when {
                t.length == 1 && t[0].isDigit() -> keys.putIfAbsent(t[0], w.center())
                t.length in 2..10 && t.all { it.isDigit() } -> spread(w, keys)
            }
        }
        // OCR-плутанина: O->0, I/l/| -> 1 (лише для відсутніх цифр)
        for (w in cands) when (w.text) {
            "O", "o" -> keys.putIfAbsent('0', w.center())
            "I", "l", "|" -> keys.putIfAbsent('1', w.center())
        }
        if (keys.size < 7) return null
        val pts = ArrayList<PointF>()
        for (c in code) pts.add(keys[c] ?: return null)
        r.lines.firstOrNull { it.text.trim().lowercase() in CONFIRM }?.let { pts.add(it.center()) }
        return Plan("code:$code", pts)
    }

    /** Циферблат без коду: усі знайдені цифри (0-9). Сервіс тисне їх по одній, по колу. */
    private fun dial(r: OcrResult): Plan? {
        val keys = HashMap<Char, PointF>()
        for (w in r.words) {
            val t = w.text
            when {
                t.length == 1 && t[0].isDigit() -> keys.putIfAbsent(t[0], w.center())
                t.length in 2..10 && t.all { it.isDigit() } -> spread(w, keys)
            }
        }
        if (keys.size < 7) return null
        return Plan("dial", "0123456789".mapNotNull { keys[it] })
    }

    /** Підказки типу "Press E", "Interact", "Open" -> тап по тексту. */
    private fun prompt(r: OcrResult): Plan? {
        val hit = r.lines.firstOrNull { l ->
            l.text.length <= 40 && l.text.lowercase().let { t -> PROMPTS.any { it in t } }
        } ?: return null
        return Plan("prompt:${hit.text}", listOf(hit.center()))
    }

    private companion object {
        val HINTS = listOf("code", "pin", "pass")
        const val ROW = "1234567890"
        const val ROW_Z = "0123456789"
        val CONFIRM = setOf("ok", "enter", "confirm", "submit", "✓")
        val PROMPTS = listOf(
            "press", "interact", "pick up", "take", "open", "examine",
            "grab", "tap to", "[e]", "(e)", "talk", "use "
        )
    }
}
