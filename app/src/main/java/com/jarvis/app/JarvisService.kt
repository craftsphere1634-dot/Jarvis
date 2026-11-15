package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
        "Your replies are spoken aloud: answer in 6-7 clear sentences with useful detail, no markdown, no emojis. " +
        "If the user speaks Hindi or Hinglish, reply in Hinglish written in Roman letters; if English, reply in English. " +
        "Use tools to open apps, websites or WhatsApp when asked. You cannot send WhatsApp messages by yourself: " +
        "you prepare the message and sir must tap Send. For Chrome or Google searches use open_url."

    private val handler = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var commandMode = false
    private var busy = false
    private val history = JSONArray()
    private val wake = Regex("jarvis|jarvish|jarvas|jarvice|जार्विस|जारविस|जार्विश")

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
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
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
        val key = getSharedPreferences("jarvis", MODE_PRIVATE).getString("key", "") ?: ""
        if (key.isBlank()) return "Sir, pehle app mein Gemini API key daaliye."
        if (history.length() > 20) clearHistory()
        history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", q))))

        repeat(5) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYS))))
                .put("contents", history)
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools())))
                                .put("generationConfig", JSONObject().put("maxOutputTokens", 800))
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
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)

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
            JSONObject().put("url", str("Full https URL")), listOf("url")))
        .put(tool("open_app", "Open an installed app by its name, e.g. Gmail, Drive, WhatsApp, YouTube",
            JSONObject().put("name", str("App name")), listOf("name")))
        .put(tool("whatsapp_message", "Open WhatsApp with a prefilled message. Sir must tap Send.",
            JSONObject().put("phone", str("Phone with country code, digits only. Empty to choose contact"))
                .put("message", str("Message text")), listOf("message")))

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
            else -> "Unknown tool"
        }
    } catch (e: Exception) {
        "Error: " + e.message
    }
}
