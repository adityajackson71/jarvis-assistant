package com.example.jarvis

import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class LgTvHelper(private val prefs: SharedPreferences) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private fun manifest(): JSONObject {
        val permissions = JSONArray(
            listOf(
                "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "CONTROL_AUDIO",
                "CONTROL_DISPLAY", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV",
                "CONTROL_POWER", "READ_APP_STATUS", "READ_CURRENT_CHANNEL",
                "READ_RUNNING_APPS", "READ_POWER_STATE"
            )
        )
        return JSONObject().apply {
            put("manifestVersion", 1)
            put("pairingType", "PROMPT")
            put("permissions", permissions)
        }
    }

    fun pairAndConnect(ip: String, onResult: (Boolean, String) -> Unit) {
        val existingKey = prefs.getString("tv_client_key", null)
        val request = Request.Builder().url("ws://$ip:3000").build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val payload = JSONObject().apply {
                    put("forcePairing", false)
                    put("manifest", manifest())
                    if (existingKey != null) put("client-key", existingKey)
                }
                ws.send(
                    JSONObject().apply {
                        put("type", "register")
                        put("id", "register_1")
                        put("payload", payload)
                    }.toString()
                )
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "registered" -> {
                        val key = json.getJSONObject("payload").getString("client-key")
                        prefs.edit().putString("tv_client_key", key).apply()
                        onResult(true, "Paired successfully, Sir.")
                        ws.close(1000, "done")
                    }
                    "error" -> {
                        onResult(false, json.optString("error", "Pairing failed."))
                        ws.close(1000, "done")
                    }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                onResult(false, t.message ?: "Couldn't reach the TV, Sir.")
            }
        })
    }

    fun sendCommand(ip: String, uri: String, payload: JSONObject? = null, onResult: ((Boolean, String) -> Unit)? = null) {
        val key = prefs.getString("tv_client_key", null)
        if (key == null) {
            onResult?.invoke(false, "TV not paired yet, Sir. Say 'pair tv' first.")
            return
        }
        val request = Request.Builder().url("ws://$ip:3000").build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val registerPayload = JSONObject().apply {
                    put("forcePairing", false)
                    put("client-key", key)
                    put("manifest", manifest())
                }
                ws.send(
                    JSONObject().apply {
                        put("type", "register")
                        put("id", "register_cmd")
                        put("payload", registerPayload)
                    }.toString()
                )
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val json = JSONObject(text)
                if (json.optString("type") == "registered") {
                    val command = JSONObject().apply {
                        put("type", "request")
                        put("id", "cmd_1")
                        put("uri", uri)
                        if (payload != null) put("payload", payload)
                    }
                    ws.send(command.toString())
                    onResult?.invoke(true, "Command sent.")
                    ws.close(1000, "done")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                onResult?.invoke(false, t.message ?: "Couldn't reach the TV, Sir.")
            }
        })
    }

    fun wakeOnLan(macAddress: String): Boolean {
        return try {
            val macBytes = macAddress.split(Regex("[:\\-]")).map { it.toInt(16).toByte() }.toByteArray()
            if (macBytes.size != 6) return false
            val bytes = ByteArray(6 + 16 * 6)
            for (i in 0 until 6) bytes[i] = 0xFF.toByte()
            for (i in 0 until 16) System.arraycopy(macBytes, 0, bytes, 6 + i * 6, 6)
            val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), 9)
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(packet)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
