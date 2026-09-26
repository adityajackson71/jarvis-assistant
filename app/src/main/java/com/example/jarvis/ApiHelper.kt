package com.example.jarvis

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

class ApiHelper {
    private val client = OkHttpClient()

    fun getWeather(city: String, apiKey: String): String {
        if (apiKey.isBlank()) return "Please save a weather API key in Settings first, Sir."
        return try {
            val url = "https://api.openweathermap.org/data/2.5/weather?q=" +
                URLEncoder.encode(city, "UTF-8") + "&appid=$apiKey&units=metric"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) return "Couldn't get weather right now (error ${response.code})."
                val json = JSONObject(body)
                val temp = json.getJSONObject("main").getDouble("temp")
                val desc = json.getJSONArray("weather").getJSONObject(0).getString("description")
                val cityName = json.getString("name")
                "It's currently $temp°C with $desc in $cityName, Sir."
            }
        } catch (e: Exception) {
            "I couldn't reach the weather service, Sir: ${e.message}"
        }
    }

    fun getNews(apiKey: String): String {
        return try {
            val url = "https://newsapi.org/v2/top-headlines?country=in&pageSize=5&apiKey=$apiKey"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) return "Couldn't fetch news right now (error ${response.code})."
                val json = JSONObject(body)
                val articles = json.getJSONArray("articles")
                if (articles.length() == 0) return "No headlines found right now, Sir."
                val builder = StringBuilder("Top headlines, Sir:\n")
                for (i in 0 until minOf(5, articles.length())) {
                    builder.append("${i + 1}. ${articles.getJSONObject(i).getString("title")}\n")
                }
                builder.toString()
            }
        } catch (e: Exception) {
            "I couldn't reach the news service, Sir: ${e.message}"
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
