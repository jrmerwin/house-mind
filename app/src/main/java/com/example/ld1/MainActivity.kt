package com.example.ld1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = ChatAdapter()
    private val messages = mutableListOf<ChatMessage>()
    private var isConnectedToLD1 = true

    // ---- Endpoint (keep ONE; pointing to your Pi) ----
    private val LD1_ENDPOINT = "http://192.168.1.39:8080/respond"

    // ---- Networking ----
    private val http = OkHttpClient()
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val piRespondAdapter = moshi.adapter(PiRespond::class.java)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ---- Audio player ----
    private var player: ExoPlayer? = null

    // ---- STT permission + launcher ----
    private val requestAudioPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startSpeechToText() else toast("Microphone permission denied") }

    private val sttLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val results = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val first = results?.firstOrNull()
            if (!first.isNullOrBlank()) {
                binding.input.setText(first)
                binding.input.setSelection(first.length)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topAppBar)
        updateStatusText()

        binding.chatRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
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

        binding.micBtn.setOnClickListener { ensureAudioPermissionAndStart() }
    }

    // ---- Chat flow ----

    private fun sendToLd1(userText: String) {
        val payload = """{"text": ${userText.toJsonString()}, "speak": true}"""
        val req = Request.Builder()
            .url(LD1_ENDPOINT)
            .post(payload.toRequestBody(jsonMedia))
            .build()

        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    toast("Network error: ${e.message}")
                    showReplyAndMaybeSpeak("Echo from LD-1: $userText", null, null)
                }
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread {
                            toast("Server error: ${it.code}")
                            showReplyAndMaybeSpeak("Echo from LD-1: $userText", null, null)
                        }
                        return
                    }
                    val body = it.body?.string().orEmpty()
                    val parsed = runCatching { piRespondAdapter.fromJson(body) }.getOrNull()
                    runOnUiThread {
                        if (parsed == null) {
                            toast("Bad response")
                            showReplyAndMaybeSpeak("Echo from LD-1: $userText", null, null)
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
            }
        })
    }

    private fun showReplyAndMaybeSpeak(
        text: String,
        audioUrl: String?,
        audioBytes: Pair<String?, String?>?
    ) {
        addMessage(text, fromUser = false)
        when {
            !audioUrl.isNullOrBlank() ->
                playFromUrl(audioUrl)
            !audioBytes?.first.isNullOrBlank() ->
                playFromBase64(audioBytes!!.first!!, audioBytes.second)
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

    // ---- STT helpers ----

    private fun ensureAudioPermissionAndStart() {
        val needsRuntime =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED)

        if (needsRuntime) {
            requestAudioPerm.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startSpeechToText()
        }
    }

    private fun startSpeechToText() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Speech recognition not available on this device")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to LD-1…")
        }
        sttLauncher.launch(intent)
    }

    // ---- Recycler helpers ----

    private fun addMessage(text: String, fromUser: Boolean) {
        messages += ChatMessage(text = text, fromUser = fromUser)
        adapter.submitList(messages.toList())
        binding.chatRecycler.scrollToPosition(messages.lastIndex)
    }

    private fun updateStatusText() {
        val item = binding.topAppBar.menu?.findItem(R.id.action_status)
        item?.title = if (isConnectedToLD1) "LD-1: online" else "LD-1: offline"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
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

