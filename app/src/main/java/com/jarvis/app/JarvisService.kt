package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class JarvisService : Service() {

    private val MODEL = "gemini-flash-lite-latest"
    private val SYS = "You are Jarvis, a Tony Stark style voice assistant. Address the user as 'sir'. " +
        "Your replies are spoken aloud, no markdown, no emojis. Keep replies to 1 short sentence for simple commands like opening apps, flashlight or volume, and 6-7 clear sentences with useful detail only for questions that need explanation. " +
        "Always reply in natural Hinglish like Indians talk: Hindi words in Devanagari script and English words in English letters, even if the user speaks English. " +
        "Use tools to open apps, websites, YouTube searches, WhatsApp, device settings, flashlight and volume when asked. " +
        "When sir says to remember something, use the remember tool. " +
        "You cannot send WhatsApp messages by yourself: you prepare the message and sir must tap Send. " +
        "For Chrome or Google searches use open_url."

    private val handler = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var commandMode = false
    private var busy = false
    private val history = JSONArray()
    private val wake = Regex("jarvis|jarvish|jarvas|jarvice|जार्विस|जारविस|जार्विश")

    private fun prefs() = getSharedPreferences("jarvis", MODE_PRIVATE)

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis sun raha hai")
            .setContentText("\"Jarvis\" bolo")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) handler.post { listen() }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sr?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- listening ----------
    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
        override fun onError(e: Int) {
            commandMode = false
            if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) {
                sr?.destroy(); sr = null
            }
            handler.postDelayed({ if (!busy) listen() }, if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 300)
        }
        override fun onResults(r: Bundle?) {
            val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            onHeard(text)
        }
    }

    private fun listen() {
        try {
            if (sr == null) {
                sr = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
            }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            sr?.startListening(i)
        } catch (e: Exception) {
            sr?.destroy(); sr = null
            handler.postDelayed({ listen() }, 1500)
        }
    }

    private fun onHeard(text: String) {
        if (commandMode) {
            commandMode = false
            if (text.isBlank()) listen() else process(text)
            return
        }
        val low = text.lowercase()
        val m = wake.find(low)
        if (m == null) { listen(); return }
        val rest = low.substring(m.range.last + 1).trim(' ', ',', '.')
        if (rest.length > 2) {
            process(rest)
        } else {
            commandMode = true
            speak("Hello sir") { listen() }
        }
    }

    // ---------- speaking ----------
    private fun speak(t: String, done: () -> Unit) {
        val engine = tts ?: return
        engine.language = if (t.any { it in '\u0900'..'\u097F' }) Locale("hi", "IN") else Locale.UK
        engine.setPitch(0.95f)
        engine.setSpeechRate(1.0f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onDone(u: String?) { handler.post { done() } }
            override fun onError(u: String?) { handler.post { done() } }
        })
        engine.speak(t, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    // ---------- brain (Gemini) ----------
    private fun process(text: String) {
        busy = true
        Thread {
            val reply = try { ask(text) } catch (e: Exception) { "Maaf karna sir, error aaya. " + (e.message ?: "") }
            handler.post { speak(reply) { busy = false; listen() } }
        }.start()
    }

    private fun clearHistory() { while (history.length() > 0) history.remove(0) }

    private fun ask(q: String): String {
        val key = prefs().getString("key", "") ?: ""
        if (key.isBlank()) return "Sir, pehle app mein Gemini API key daaliye."
        if (history.length() > 20) clearHistory()
        history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", q))))

        val facts = prefs().getString("facts", "") ?: ""
        val sysText = if (facts.isBlank()) SYS else SYS + " Things you remember about sir: " + facts.replace("\n", "; ") + "."

        repeat(5) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sysText))))
                .put("contents", history)
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools())))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 1200))
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 40000; c.doOutput = true
            c.setRequestProperty("x-goog-api-key", key)
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
            if (code !in 200..299) {
                clearHistory()
                return when (code) {
                    400, 401, 403 -> "Sir, API key galat lag rahi hai, error $code."
                    429 -> "Sir, free limit khatam ho gayi, thodi der baad try kijiye."
                    else -> "Sir, API error $code."
                }
            }
            val j = JSONObject(txt)
            val cand = j.optJSONArray("candidates")?.optJSONObject(0) ?: return "Sir, jawab nahi mila."
            val content = cand.optJSONObject("content") ?: return "Sir, jawab nahi mila."
            val parts = content.optJSONArray("parts") ?: return "Sir, jawab nahi mila."
            content.put("role", "model")
            history.put(content)

            val responses = JSONArray()
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val fc = p.optJSONObject("functionCall")
                if (fc != null) {
                    val name = fc.getString("name")
                    val result = runTool(name, fc.optJSONObject("args") ?: JSONObject())
                    responses.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject().put("name", name).put("response", JSONObject().put("result", result))
                        )
                    )
                } else if (p.has("text") && !p.optBoolean("thought", false)) {
                    sb.append(p.getString("text"))
                }
            }
            if (responses.length() > 0) {
                history.put(JSONObject().put("role", "user").put("parts", responses))
            } else {
                return sb.toString().ifBlank { "Ji sir." }
            }
        }
        return "Sir, kaam poora nahi ho paaya."
    }

    // ---------- tools ----------
    private fun prop(type: String, d: String) = JSONObject().put("type", type).put("description", d)

    private fun tool(n: String, d: String, props: JSONObject, req: List<String>): JSONObject {
        val o = JSONObject().put("name", n).put("description", d)
        if (props.length() > 0) {
            o.put(
                "parameters",
                JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))
            )
        }
        return o
    }

    private fun tools() = JSONArray()
        .put(tool("get_time", "Get current date and time", JSONObject(), listOf()))
        .put(tool("open_url", "Open a website or Google search in the browser (Chrome)",
            JSONObject().put("url", prop("string", "Full https URL")), listOf("url")))
        .put(tool("open_app", "Open an installed app by its name, e.g. Gmail, Drive, WhatsApp, YouTube",
            JSONObject().put("name", prop("string", "App name")), listOf("name")))
        .put(tool("whatsapp_message", "Open WhatsApp with a prefilled message. Sir must tap Send.",
            JSONObject().put("phone", prop("string", "Phone with country code, digits only. Empty to choose contact"))
                .put("message", prop("string", "Message text")), listOf("message")))
        .put(tool("youtube_search", "Search YouTube for a song, video or topic and show results",
            JSONObject().put("query", prop("string", "Search text")), listOf("query")))
        .put(tool("flashlight", "Turn the phone flashlight on or off",
            JSONObject().put("on", prop("boolean", "true to turn on, false to turn off")), listOf("on")))
        .put(tool("set_volume", "Set the media volume as a percentage from 0 to 100",
            JSONObject().put("percent", prop("integer", "Volume percent 0-100")), listOf("percent")))
        .put(tool("open_settings", "Open a phone settings screen: wifi, bluetooth, display, sound or general",
            JSONObject().put("what", prop("string", "wifi, bluetooth, display, sound or general")), listOf("what")))
        .put(tool("remember", "Save a fact about sir to remember permanently",
            JSONObject().put("fact", prop("string", "The fact to remember")), listOf("fact")))
        .put(tool("forget_all", "Erase everything remembered about sir", JSONObject(), listOf()))

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun runTool(name: String, a: JSONObject): String = try {
        when (name) {
            "get_time" -> SimpleDateFormat("EEEE, d MMMM yyyy, hh:mm a", Locale("en", "IN")).format(Date())
            "open_url" -> { launch(Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url")))); "Opened" }
            "whatsapp_message" -> {
                val phone = a.optString("phone", "").filter { it.isDigit() }
                val msg = Uri.encode(a.optString("message", ""))
                val u = if (phone.isNotEmpty()) "https://wa.me/$phone?text=$msg" else "https://wa.me/?text=$msg"
                launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                "WhatsApp opened with the message; sir needs to tap Send"
            }
            "open_app" -> {
                val want = a.getString("name").lowercase()
                val pm = packageManager
                val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val hit = pm.queryIntentActivities(li, 0).firstOrNull {
                    it.loadLabel(pm).toString().lowercase().contains(want)
                }
                val launchIntent = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                if (launchIntent == null) "App not found" else { launch(launchIntent); "Opened" }
            }
            "youtube_search" -> {
                val qy = Uri.encode(a.optString("query", ""))
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$qy")))
                "YouTube search opened"
            }
            "flashlight" -> {
                val on = a.optBoolean("on", true)
                val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (id == null) "No flashlight found" else {
                    cm.setTorchMode(id, on)
                    if (on) "Flashlight on" else "Flashlight off"
                }
            }
            "set_volume" -> {
                val pct = a.optInt("percent", 50).coerceIn(0, 100)
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, pct * max / 100, AudioManager.FLAG_SHOW_UI)
                "Volume set to $pct percent"
            }
            "open_settings" -> {
                val w = a.optString("what", "").lowercase()
                val action = when {
                    w.contains("wifi") || w.contains("wi-fi") -> Settings.ACTION_WIFI_SETTINGS
                    w.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
                    w.contains("display") || w.contains("brightness") -> Settings.ACTION_DISPLAY_SETTINGS
                    w.contains("sound") -> Settings.ACTION_SOUND_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                launch(Intent(action))
                "Settings opened"
            }
            "remember" -> {
                val f = a.optString("fact", "").trim()
                if (f.isBlank()) "Nothing to save" else {
                    val old = prefs().getString("facts", "") ?: ""
                    prefs().edit().putString("facts", (old + "\n" + f).trim()).apply()
                    "Saved"
                }
            }
            "forget_all" -> { prefs().edit().remove("facts").apply(); "Memory cleared" }
            else -> "Unknown tool"
        }
    } catch (e: Exception) {
        "Error: " + e.message
    }
}
