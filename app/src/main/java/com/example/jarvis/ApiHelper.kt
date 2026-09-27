package com.example.jarvis

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class ApiHelper {
    private val client = OkHttpClient()

    fun askGeminiWithSearch(prompt: String, apiKey: String): String {
        if (apiKey.isBlank()) return "Please save your Gemini API key first, Sir."
        return try {
            val bodyJson = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                        }
                    )
                )
                put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent")
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("content-type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) return "Error ${response.code}: $body"
                val json = JSONObject(body)
                val candidates = json.getJSONArray("candidates")
                val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                parts.getJSONObject(0).getString("text")
            }
        } catch (e: Exception) {
            "I couldn't reach the search service, Sir: ${e.message}"
        }
    }

    fun searchYoutubeVideoId(query: String, apiKey: String): String? {
        if (apiKey.isBlank()) return null
        return try {
            val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=1&q=" +
                URLEncoder.encode(query, "UTF-8") + "&key=$apiKey"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) return null
                val json = JSONObject(body)
                val items = json.getJSONArray("items")
                if (items.length() == 0) return null
                items.getJSONObject(0).getJSONObject("id").getString("videoId")
            }
        } catch (e: Exception) {
            null
        }
    }
}
