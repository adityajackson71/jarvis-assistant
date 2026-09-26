package com.example.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.jarvis.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private val client = OkHttpClient()
    private val prefs by lazy { getSharedPreferences("jarvis_prefs", MODE_PRIVATE) }
    private val conversation = JSONArray()

    private val micLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                sendMessage(spokenText)
            }
        }
    }

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceInput()
        else Toast.makeText(this, "Microphone permission is needed for voice input", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)

        binding.apiKeyInput.setText(prefs.getString("api_key", ""))

        binding.saveKeyButton.setOnClickListener {
            val key = binding.apiKeyInput.text.toString().trim()
            prefs.edit().putString("api_key", key).apply()
            Toast.makeText(this, "API key saved", Toast.LENGTH_SHORT).show()
        }

        binding.sendButton.setOnClickListener {
            val text = binding.messageInput.text.toString().trim()
            if (text.isNotEmpty()) sendMessage(text)
        }

        binding.micButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) startVoiceInput()
            else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        binding.stopButton.setOnClickListener { tts.stop() }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS...")
        }
        try {
            micLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice input isn't available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMessage(userText: String) {
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isBlank()) {
            appendToChat("JARVIS", "Please paste and save your API key first.")
            return
        }

        appendToChat("You", userText)
        binding.messageInput.setText("")
        conversation.put(JSONObject().apply { put("role", "user"); put("content", userText) })

        CoroutineScope(Dispatchers.Main).launch {
            appendToChat("JARVIS", "Thinking...")
            val reply = withContext(Dispatchers.IO) { callClaude(apiKey) }
            removeLastLine()
            appendToChat("JARVIS", reply)
            conversation.put(JSONObject().apply { put("role", "assistant"); put("content", reply) })
            speak(reply)
        }
    }

    private fun callClaude(apiKey: String): String {
        return try {
            val messagesArray = JSONArray()
            for (i in 0 until conversation.length()) messagesArray.put(conversation.get(i))

            val bodyJson = JSONObject().apply {
                put("model", "claude-sonnet-5")
                put("max_tokens", 1024)
                put("messages", messagesArray)
            }

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) return "Error ${response.code}: $responseBody"
                val content = JSONObject(responseBody).getJSONArray("content")
                content.getJSONObject(0).getString("text")
            }
        } catch (e: Exception) {
            "Error reaching JARVIS's brain: ${e.message}"
        }
    }

    private fun appendToChat(sender: String, message: String) {
        binding.chatLog.append("\n$sender: $message\n")
    }

    private fun removeLastLine() {
        val current = binding.chatLog.text.toString()
        val lastNewline = current.trimEnd('\n').lastIndexOf('\n')
        binding.chatLog.text = if (lastNewline >= 0) current.substring(0, lastNewline) else ""
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.getDefault()
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
