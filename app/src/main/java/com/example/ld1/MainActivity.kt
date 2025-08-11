package com.example.ld1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ld1.databinding.ActivityMainBinding
import com.example.ld1.model.ChatMessage
import com.example.ld1.net.PiRespond
import com.example.ld1.ui.ChatAdapter
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.audio.AudioAttributes
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

import android.os.Handler
import android.os.Looper
import java.io.IOException

import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat



class MainActivity : AppCompatActivity() {

    // ONE endpoint only:
    private val LD1_ENDPOINT = "http://192.168.1.39:8080/respond"

    // Derive /health from /respond (lazy avoids init order issues)
    private val HEALTH_URL by lazy { LD1_ENDPOINT.replace(Regex("/respond$"), "/health") }

    private val healthHandler = Handler(Looper.getMainLooper())
    private val healthTicker = object : Runnable {
        override fun run() {
            pingServer()
            healthHandler.postDelayed(this, 15_000L) // every 15s
        }
    }

    private lateinit var binding: ActivityMainBinding
    private val adapter = ChatAdapter()
    private val messages = mutableListOf<ChatMessage>()
    private var isConnectedToLD1 = false


    // ---- Networking ----
    private val http = OkHttpClient()
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val piRespondAdapter = moshi.adapter(PiRespond::class.java)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ---- Audio player ----
    private var player: ExoPlayer? = null

    // ---- Push-to-talk STT ----
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var pendingAutoSendText: String? = null

    // Permission launcher for mic
    private val requestAudioPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            createRecognizer()
            toast("Mic ready — press & hold to talk")
        } else {
            toast("Microphone permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topAppBar)
        updateStatusUI()

        binding.chatRecycler.layoutManager = LinearLayoutManager(this)
        binding.chatRecycler.adapter = adapter

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(com.google.android.exoplayer2.C.USAGE_MEDIA)
                    .setContentType(com.google.android.exoplayer2.C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
        }

        binding.sendBtn.setOnClickListener {
            val text = binding.input.text?.toString().orEmpty().trim()
            if (text.isNotEmpty()) {
                addMessage(text, fromUser = true)
                binding.input.setText("")
                sendToLd1(text)
            }
        }

        // Push & hold mic: start on DOWN, stop on UP/CANCEL (auto-send on final result)
        binding.micBtn.setOnTouchListener { _: View, ev: MotionEvent ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (ensureAudioPermission()) {
                        startListening()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isListening) stopListening()
                    true
                }
                else -> false
            }
        }

        // Prepare recognizer if permission already granted
        if (hasMicPermission()) createRecognizer()
    }

    // ---- STT helpers ----

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun ensureAudioPermission(): Boolean {
        return if (hasMicPermission()) {
            true
        } else {
            // User will need to press & hold again after granting
            requestAudioPerm.launch(Manifest.permission.RECORD_AUDIO)
            false
        }
    }

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    binding.micBtn.isActivated = true
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    binding.micBtn.isActivated = false
                }
                override fun onError(error: Int) {
                    isListening = false
                    binding.micBtn.isActivated = false
                    Log.w("LD1Chat", "STT error: $error")
                    // No auto-send on error
                }
                override fun onResults(results: Bundle) {
                    isListening = false
                    binding.micBtn.isActivated = false
                    val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val finalText = texts?.firstOrNull()?.trim().orEmpty()
                    if (finalText.isNotEmpty()) {
                        pendingAutoSendText = finalText
                        // auto-fill and auto-send
                        binding.input.setText(finalText)
                        binding.input.setSelection(finalText.length)
                        addMessage(finalText, fromUser = true)
                        binding.input.setText("")
                        sendToLd1(finalText)
                    }
                }
                override fun onPartialResults(partialResults: Bundle) {
                    val partial = partialResults
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!partial.isNullOrBlank()) {
                        binding.input.setText(partial)
                        binding.input.setSelection(partial.length)
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (recognizer == null) createRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("LD1Chat", "startListening failed", e)
            toast("Couldn’t start mic")
        }
    }

    private fun stopListening() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
    }

    // ---- Chat flow ----

    override fun onStart() {
        super.onStart()
        updateStatusUI()                 // show initial state
        pingServer()                     // immediate
        healthHandler.post(healthTicker) // then every 15s
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        healthHandler.removeCallbacks(healthTicker)
    }

    private fun pingServer() {
        Log.d("LD1Chat", "PING $HEALTH_URL")
        val req = Request.Builder().url(HEALTH_URL).get().build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.d("LD1Chat", "PING fail: ${e.message}")
                if (isConnectedToLD1) {
                    isConnectedToLD1 = false
                    runOnUiThread { updateStatusUI() }
                }
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                val ok = response.isSuccessful
                val body = response.body?.string().orEmpty()
                Log.d("LD1Chat", "PING ${response.code}: $body")
                response.close()
                if (isConnectedToLD1 != ok) {
                    isConnectedToLD1 = ok
                    runOnUiThread { updateStatusUI() }
                }
            }
        })
    }


    private fun sendToLd1(userText: String) {
        val payload = """{"text": ${userText.toJsonString()}, "speak": true}"""
        Log.d("LD1Chat", "POST $LD1_ENDPOINT payload=$payload")
        val req = Request.Builder()
            .url(LD1_ENDPOINT)
            .post(payload.toRequestBody(jsonMedia))
            .build()

        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e("LD1Chat", "HTTP fail: ${e.message}", e)
                runOnUiThread { toast("Network error: ${e.message}") }
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                val code = response.code
                val body = response.body?.string().orEmpty()
                Log.d("LD1Chat", "HTTP $code body=$body")
                response.close()
                if (code !in 200..299) {
                    runOnUiThread { toast("Server error: $code") }
                    return
                }
                val parsed = runCatching { piRespondAdapter.fromJson(body) }.getOrNull()
                runOnUiThread {
                    if (parsed == null) {
                        toast("Bad JSON from server")
                    } else {
                        showReplyAndMaybeSpeak(
                            parsed.text,
                            null,
                            parsed.audio_wav_b64 to "audio/wav"
                        )
                        parsed.warning?.let { w -> toast(w) }
                    }
                }
            }
        })
    }

    private fun showReplyAndMaybeSpeak(
        text: String,
        audioUrl: String?,
        audioBytes: Pair<String?, String?>?
    ) {
        addMessage(text, fromUser = false)

        val b64  = audioBytes?.first
        val mime = audioBytes?.second

        when {
            !audioUrl.isNullOrBlank() -> playFromUrl(audioUrl)
            !b64.isNullOrBlank()      -> playFromBase64(b64, mime)
            else -> { /* silent */ }
        }
    }

    // ---- Audio playback ----

    private fun playFromUrl(url: String) {
        try {
            val mediaItem = MediaItem.fromUri(Uri.parse(url))
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
        } catch (e: Exception) {
            Log.e("LD1Chat", "playFromUrl failed", e)
            toast("Audio play error")
        }
    }

    private fun playFromBase64(base64: String, mime: String?) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val ext = when (mime) {
                "audio/mpeg" -> ".mp3"
                "audio/ogg"  -> ".ogg"
                "audio/wav", "audio/x-wav" -> ".wav"
                else -> ".dat"
            }
            val f = File.createTempFile("ld1_", ext, cacheDir).apply { deleteOnExit() }
            FileOutputStream(f).use { it.write(bytes) }
            val mediaItem = MediaItem.fromUri(Uri.fromFile(f))
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
        } catch (e: Exception) {
            Log.e("LD1Chat", "playFromBase64 failed", e)
            toast("Audio play error")
        }
    }

    // ---- Recycler helpers ----

    private fun addMessage(text: String, fromUser: Boolean) {
        messages += ChatMessage(text = text, fromUser = fromUser)
        adapter.submitList(messages.toList())
        binding.chatRecycler.scrollToPosition(messages.lastIndex)
    }

    private fun updateStatusUI() {
        // Text cue in the bar
        binding.topAppBar.subtitle = if (isConnectedToLD1) "online" else "offline"

        // Icon cue in the menu, without toolbar tint
        val item = binding.topAppBar.menu?.findItem(R.id.action_status) ?: return
        val iconRes = if (isConnectedToLD1) R.drawable.status_dot_online else R.drawable.status_dot_offline
        AppCompatResources.getDrawable(this, iconRes)?.let { raw ->
            val d = raw.mutate()
            DrawableCompat.setTintList(d, null)   // kill automatic tinting
            item.icon = d
        }
        item.title = if (isConnectedToLD1) "LD-1: online" else "LD-1: offline"
    }



    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()


    override fun onDestroy() {
        player?.release()
        player = null
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}

// --- tiny helpers ---

private fun String.toJsonString(): String =
    buildString {
        append('"')
        for (c in this@toJsonString) {
            when (c) {
                '\\' -> append("\\\\")
                '"'  -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }
