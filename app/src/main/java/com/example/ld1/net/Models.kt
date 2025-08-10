package com.example.ld1.net

data class PiRespond(
    val text: String,
    val audio_wav_b64: String? = null,
    val warning: String? = null
)