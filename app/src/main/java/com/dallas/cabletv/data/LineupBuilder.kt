package com.dallas.cabletv.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

private val lineupHttp = OkHttpClient.Builder()
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

/**
 * One chat request to OpenRouter with the key and model saved in the lineup. Returns the answer text.
 * The request carries only channel names and categories, never stream addresses or logins.
 */
fun openRouterAsk(key: String, model: String, prompt: String): String {
    require(key.isNotBlank()) { "Add an OpenRouter key first." }
    require(model.isNotBlank()) { "Choose an OpenRouter model first." }
    val body = JSONObject()
        .put("model", model)
        .put("temperature", 0)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        .toString()
    val req = Request.Builder()
        .url(OPENROUTER_URL)
        .header("Authorization", "Bearer $key")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    lineupHttp.newCall(req).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) error("OpenRouter returned HTTP ${resp.code}")
        return JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content").orEmpty()
    }
}
