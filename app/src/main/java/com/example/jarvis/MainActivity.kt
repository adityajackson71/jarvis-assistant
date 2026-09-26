package com.example.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private lateinit var actionHandler: ActionHandler
    private lateinit var apiHelper: ApiHelper
    private val client = OkHttpClient()
    private val prefs by lazy { getSharedPreferences("jarvis_prefs", MODE_PRIVATE) }
    private val conversation = JSONArray()
    private val clockHandler = Handler(Looper.getMainLooper())

    private var pendingConfirmation: (() -> Unit)? = null
    private var pendingPermissionRetryText: String? = null

    private val clockRunnable = object : Runnable {
        override fun run() {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            binding.clockText.text = fmt.format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

    private val micLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        binding.statusRing.setState(StatusRingView.State.IDLE)
        binding.statusLabel.text = "STANDBY"
        if (result.resultCode == RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.firstOrNull()
            if (!spokenText.isNullOrBlank()) processInput(spokenText)
        }
    }

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceInput()
        else Toast.makeText(this, "Microphone permission is needed for voice input", Toast.LENGTH_SHORT).show()
    }

    private val phonePermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            pendingPermissionRetryText?.let { processInput(it) }
        } else {
            appendToChat("JARVIS", "I need contacts, call, and SMS permissions to do that, Sir.")
        }
        pendingPermissionRetryText = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        actionHandler = ActionHandler(this)
        apiHelper = ApiHelper()

        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    binding.statusRing.setState(StatusRingView.State.SPEAKING)
                    binding.statusLabel.text = "SPEAKING"
                }
            }
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    binding.statusRing.setState(StatusRingView.State.IDLE)
                    binding.statusLabel.text = "STANDBY"
                }
            }
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    binding.statusRing.setState(StatusRingView.State.IDLE)
                    binding.statusLabel.text = "STANDBY"
                }
            }
        })

        binding.apiKeyInput.setText(prefs.getString("api_key", ""))
        binding.cityInput.setText(prefs.getString("city", ""))
        binding.youtubeKeyInput.setText(prefs.getString("youtube_key", ""))
        binding.weatherKeyInput.setText(prefs.getString("weather_key", ""))
        binding.newsKeyInput.setText(prefs.getString("news_key", ""))

        binding.settingsButton.setOnClickListener {
            binding.settingsPanel.visibility =
                if (binding.settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.saveSettingsButton.setOnClickListener {
            prefs.edit()
                .putString("city", binding.cityInput.text.toString().trim())
                .putString("youtube_key", binding.youtubeKeyInput.text.toString().trim())
                .putString("weather_key", binding.weatherKeyInput.text.toString().trim())
                .putString("news_key", binding.newsKeyInput.text.toString().trim())
                .apply()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }

        binding.saveKeyButton.setOnClickListener {
            val key = binding.apiKeyInput.text.toString().trim()
            prefs.edit().putString("api_key", key).apply()
            Toast.makeText(this, "API key saved", Toast.LENGTH_SHORT).show()
        }

        binding.sendButton.setOnClickListener {
            val text = binding.messageInput.text.toString().trim()
            if (text.isNotEmpty()) processInput(text)
        }

        binding.micButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) startVoiceInput()
            else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        binding.stopButton.setOnClickListener {
            tts.stop()
            binding.statusRing.setState(StatusRingView.State.IDLE)
            binding.statusLabel.text = "STANDBY"
        }
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockRunnable)
        super.onPause()
    }

    private fun greetingByTime(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> "Good morning, Sir."
            hour < 17 -> "Good afternoon, Sir."
            hour < 21 -> "Good evening, Sir."
            else -> "Systems online, Sir."
        }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS...")
        }
        try {
            binding.statusRing.setState(StatusRingView.State.LISTENING)
            binding.statusLabel.text = "LISTENING"
            micLauncher.launch(intent)
        } catch (e: Exception) {
            binding.statusRing.setState(StatusRingView.State.IDLE)
            binding.statusLabel.text = "STANDBY"
            Toast.makeText(this, "Voice input isn't available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Command routing ----------

    private fun processInput(text: String) {
        appendToChat("You", text)
        binding.messageInput.setText("")

        val lower = text.trim().lowercase()

        if (pendingConfirmation != null) {
            if (lower.matches(Regex("(yes|yeah|yep|confirm|do it|send it|go ahead).*"))) {
                val action = pendingConfirmation
                pendingConfirmation = null
                action?.invoke()
                return
            } else if (lower.matches(Regex("(no|nope|cancel|stop|don't|dont).*"))) {
                pendingConfirmation = null
                appendToChat("JARVIS", "Cancelled, Sir.")
                speak("Cancelled, Sir.")
                return
            }
            pendingConfirmation = null
        }

        if (!hasPhonePermissions() && (lower.startsWith("call ") || (lower.contains("send") && lower.contains("saying")) || lower.contains("message"))) {
            pendingPermissionRetryText = text
            phonePermissionsLauncher.launch(
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)
            )
            return
        }

        if (tryHandleCommand(text, lower)) return

        sendToClaude(text)
    }

    private fun hasPhonePermissions(): Boolean {
        val perms = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun tryHandleCommand(original: String, lower: String): Boolean {
        // Call
        Regex("^call (.+)$").find(lower)?.let { match ->
            val name = match.groupValues[1].trim()
            val contact = actionHandler.findContact(name)
            if (contact == null) {
                appendToChat("JARVIS", "I couldn't find $name in your contacts, Sir.")
                speak("I couldn't find $name in your contacts, Sir.")
            } else {
                val (foundName, number) = contact
                appendToChat("JARVIS", "I found $foundName. Should I call them?")
                speak("I found $foundName. Should I call them?")
                pendingConfirmation = { actionHandler.makeCall(number) }
            }
            return true
        }

        // Message / SMS
        Regex("^(?:send|message|text) ([a-zA-Z ]+?) (?:a message |a text )?(?:saying|that says) (.+)$").find(lower)?.let { match ->
            val name = match.groupValues[1].trim()
            val messageBody = original.substring(original.length - match.groupValues[2].length).trim()
            val contact = actionHandler.findContact(name)
            if (contact == null) {
                appendToChat("JARVIS", "I couldn't find $name in your contacts, Sir.")
                speak("I couldn't find $name in your contacts, Sir.")
            } else {
                val (foundName, number) = contact
                appendToChat("JARVIS", "Ready to send $foundName: '$messageBody'. Should I send it?")
                speak("Ready to send $foundName. Should I send it?")
                pendingConfirmation = { actionHandler.sendSms(number, messageBody) }
            }
            return true
        }

        // Notifications
        if (Regex("^(read|show|check)( my)? notifications$").matches(lower)) {
            val list = NotificationReaderService.recentNotifications
            if (list.isEmpty()) {
                appendToChat("JARVIS", "No recent notifications, Sir, or notification access isn't enabled yet.")
                speak("No recent notifications, Sir.")
            } else {
                val summary = list.take(5).joinToString("\n")
                appendToChat("JARVIS", "Recent notifications:\n$summary")
                speak("You have ${list.size} recent notifications.")
            }
            return true
        }
        if (lower.contains("enable notification") || lower.contains("notification access")) {
            appendToChat("JARVIS", "Opening notification access settings. Please enable JARVIS there, Sir.")
            speak("Opening notification access settings.")
            actionHandler.openNotificationAccessSettings()
            return true
        }

        // Alarm / reminder
        Regex("(?:remind me to (.+?) )?(?:at|for) (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(lower)?.let { match ->
            if (lower.contains("alarm") || lower.contains("remind")) {
                var hour = match.groupValues[2].toInt()
                val minute = match.groupValues[3].ifBlank { "0" }.toInt()
                val meridiem = match.groupValues[4]
                if (meridiem == "pm" && hour < 12) hour += 12
                if (meridiem == "am" && hour == 12) hour = 0
                val label = match.groupValues[1].ifBlank { "JARVIS reminder" }
                actionHandler.setAlarm(hour, minute, label)
                appendToChat("JARVIS", "Alarm set for ${match.groupValues[2]}:${minute.toString().padStart(2, '0')}, Sir.")
                speak("Alarm set, Sir.")
                return true
            }
        }

        // Music: YouTube (auto-play first result)
        Regex("^play (.+?) (?:on|in) youtube$").find(lower)?.let { match ->
            val query = match.groupValues[1].trim()
            playOnYoutube(query)
            return true
        }
        Regex("^search(?: on)? youtube(?: for)? (.+)$").find(lower)?.let { match ->
            playOnYoutube(match.groupValues[1].trim())
            return true
        }

        // Music: default Spotify
        Regex("^play (.+)$").find(lower)?.let { match ->
            val query = match.groupValues[1].trim()
            actionHandler.playSpotify(query)
            appendToChat("JARVIS", "Opening Spotify for $query, Sir.")
            speak("Opening Spotify.")
            return true
        }

        // Weather
        if (Regex("^(?:what'?s the )?weather(?: today)?$").matches(lower)) {
            val city = prefs.getString("city", "") ?: ""
            val key = prefs.getString("weather_key", "") ?: ""
            if (city.isBlank()) {
                appendToChat("JARVIS", "Please set your city in Settings (⚙) first, Sir.")
                return true
            }
            CoroutineScope(Dispatchers.Main).launch {
                val reply = withContext(Dispatchers.IO) { apiHelper.getWeather(city, key) }
                appendToChat("JARVIS", reply)
                speak(reply)
            }
            return true
        }

        // News
        if (Regex("^(?:check |get |what'?s the )?news$").matches(lower)) {
            val key = prefs.getString("news_key", "") ?: ""
            CoroutineScope(Dispatchers.Main).launch {
                val reply = withContext(Dispatchers.IO) { apiHelper.getNews(key) }
                appendToChat("JARVIS", reply)
                speak("Here are today's top headlines, Sir.")
            }
            return true
        }

        // Time
        if (Regex("^what(?:'s| is) the time$").matches(lower)) {
            val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            val reply = "It's ${fmt.format(Date())}, Sir."
            appendToChat("JARVIS", reply)
            speak(reply)
            return true
        }

        // Distance / directions
        Regex("^(?:distance|directions)(?: from (.+?))? to (.+)$").find(lower)?.let { match ->
            val origin = match.groupValues[1].ifBlank { null }
            val destination = match.groupValues[2].trim()
            actionHandler.openMapsDirections(destination, origin)
            appendToChat("JARVIS", "Opening directions to $destination, Sir.")
            speak("Opening directions in Maps.")
            return true
        }

        // Open website
        Regex("^(?:open website|go to|open) (https?://\\S+|\\S+\\.(?:com|org|net|in)\\S*)$").find(lower)?.let { match ->
            actionHandler.openUrl(match.groupValues[1])
            appendToChat("JARVIS", "Opening ${match.groupValues[1]}.")
            speak("Opening it now.")
            return true
        }

        // Web search
        Regex("^search(?: for)? (.+)$").find(lower)?.let { match ->
            actionHandler.webSearch(match.groupValues[1])
            appendToChat("JARVIS", "Searching for ${match.groupValues[1]}.")
            speak("Searching now.")
            return true
        }

        // Open app
        Regex("^open (.+)$").find(lower)?.let { match ->
            val appName = match.groupValues[1].trim()
            val opened = actionHandler.openApp(appName)
            if (opened) {
                appendToChat("JARVIS", "Opening $appName.")
                speak("Opening $appName.")
            } else {
                appendToChat("JARVIS", "I couldn't find an app called $appName, Sir.")
                speak("I couldn't find that app, Sir.")
            }
            return true
        }

        return false
    }

    private fun playOnYoutube(query: String) {
        val key = prefs.getString("youtube_key", "") ?: ""
        if (key.isBlank()) {
            actionHandler.openYoutubeSearch(query)
            appendToChat("JARVIS", "Opening YouTube search for $query, Sir. Add a YouTube API key in Settings for auto-play.")
            speak("Opening YouTube search.")
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            binding.statusRing.setState(StatusRingView.State.THINKING)
            val videoId = withContext(Dispatchers.IO) { apiHelper.searchYoutubeVideoId(query, key) }
            binding.statusRing.setState(StatusRingView.State.IDLE)
            if (videoId != null) {
                actionHandler.playYoutubeVideo(videoId)
                appendToChat("JARVIS", "Playing $query on YouTube, Sir.")
                speak("Playing it now.")
            } else {
                actionHandler.openYoutubeSearch(query)
                appendToChat("JARVIS", "Couldn't auto-play, opened search results instead, Sir.")
                speak("Opening YouTube search instead.")
            }
        }
    }

    // ---------- LLM chat ----------

    private fun sendToClaude(userText: String) {
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isBlank()) {
            appendToChat("JARVIS", "Please paste and save your Claude API key first.")
            return
        }

        conversation.put(JSONObject().apply { put("role", "user"); put("content", userText) })

        binding.statusRing.setState(StatusRingView.State.THINKING)
        binding.statusLabel.text = "PROCESSING"

        CoroutineScope(Dispatchers.Main).launch {
            val reply = withContext(Dispatchers.IO) { callClaude(apiKey) }
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
                put(
                    "system",
                    "You are JARVIS, a calm, precise, slightly witty personal AI assistant, in the style of " +
                        "Tony Stark's assistant. Address the user as 'Sir' unless told otherwise. Keep spoken " +
                        "replies concise and natural, like a real conversation. You can also thoughtfully " +
                        "discuss business ideas and general advice when asked, since the user's father may " +
                        "use you for that too."
                )
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
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val time = fmt.format(Date())
        binding.chatLog.append("$time  $sender: $message\n\n")
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            tts.setPitch(0.85f)
            tts.setSpeechRate(1.0f)
            val greeting = "${greetingByTime()} Systems online. I am JARVIS."
            appendToChat("JARVIS", greeting)
            speak(greeting)
        }
    }

    override fun onDestroy() {
        clockHandler.removeCallbacks(clockRunnable)
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
