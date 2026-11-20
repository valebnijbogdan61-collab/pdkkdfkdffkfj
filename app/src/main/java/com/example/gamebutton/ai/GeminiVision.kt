package com.example.gamebutton.ai

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Base64
import com.example.gamebutton.input.ActionPlanner
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Vision через Gemini API (безкоштовний ключ з Google AI Studio). Блокуючий — викликати з фону. */
class GeminiVision(private val key: String, private val model: String) {

    class RateLimited : Exception()

    fun ask(bmp: Bitmap): List<ActionPlanner.Plan> {
        val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
        val small = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        else bmp
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

        val parts = JSONArray()
            .put(JSONObject().put("text", PROMPT))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
        val cfg = JSONObject().put("responseMimeType", "application/json").put("temperature", 0)
        // без "роздумів" відповідь приходить помітно швидше
        if (model.contains("2.5-flash")) cfg.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", cfg)

        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = c.responseCode
        if (code == 429) throw RateLimited()
        if (code !in 200..299) throw IOException("HTTP $code (перевір ключ/модель)")
        val resp = c.inputStream.bufferedReader().use { it.readText() }

        val text = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val j = JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        val arr = j.optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<ActionPlanner.Plan>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optDouble("confidence", 1.0) < 0.5) continue // тільки те, що справді схоже на натискне
            out.add(
                ActionPlanner.Plan(
                    "ai:" + o.optString("kind") + ":" + o.optString("label"),
                    listOf(
                        PointF(
                            (o.getDouble("x") / 1000.0 * bmp.width).toFloat(),
                            (o.getDouble("y") / 1000.0 * bmp.height).toFloat()
                        )
                    )
                )
            )
        }
        return out
    }

    private companion object {
        const val SYSTEM = "You are the vision module of an accessibility overlay for mobile games. " +
            "You see one screenshot. Find UI elements the player can actually press and give precise tap points. " +
            "Be strict: list only things that look interactive (buttons, keypad keys, action prompts, icons with tap affordance). " +
            "Ignore decorations, HUD text, scenery and the small translucent gray hand-icon overlay button itself. " +
            "Never read, guess or solve codes."
        const val PROMPT = "List up to 12 pressable elements. Per element: kind (button|key|prompt|icon), " +
            "label (max 4 words), x and y = exact CENTER of the element, normalized 0-1000 to image width/height, " +
            "confidence 0-1 that pressing it does something. Order: most likely intended interaction first " +
            "(highlighted, near screen center/crosshair). If a keypad/dial is visible, list its keys as separate " +
            "items left-to-right, top-to-bottom. Reply ONLY JSON: " +
            "{\"elements\":[{\"kind\":\"\",\"label\":\"\",\"x\":0,\"y\":0,\"confidence\":0.0}]}. " +
            "Nothing tappable: {\"elements\":[]}."
    }
}
