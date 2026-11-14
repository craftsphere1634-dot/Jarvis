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

    private val MODEL = "claude-haiku-5-5"
    private val SYS = "You are Jarvis, a Tony Stark style voice assistant. Address the user as 'sir'. " +
        "Your replies are spoken aloud: 1-2 short sentences, no markdown, no emojis. " +
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
        engine.language = if (t.any { it in '\u0900'..'\u097F' }) Locale("hi", "IN") else Locale("en", "IN")
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onDone(u: String?) { handler.post { done() } }
            override fun onError(u: String?) { handler.post { done() } }
        })
        engine.speak(t, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }
