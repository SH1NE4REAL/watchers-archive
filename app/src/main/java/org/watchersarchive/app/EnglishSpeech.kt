package org.watchersarchive.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.io.Closeable
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Speech uses the device's installed TTS engine rather than an untrusted audio URL. */
internal interface EnglishSpeech : Closeable {
    val ready: StateFlow<Boolean>
    fun speak(text: String): Boolean
    fun stop()
}

internal class DeviceEnglishSpeech(context: Context) : EnglishSpeech {
    private val available = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = available
    private var closed = false
    private var speech: TextToSpeech? = null
    private var initialStatus: Int? = null

    init {
        val instance = TextToSpeech(context.applicationContext) { status ->
            initialStatus = status
            configure()
        }
        speech = instance
        configure()
    }

    private fun configure() {
        val current = speech ?: return
        if (closed || initialStatus != TextToSpeech.SUCCESS) return
        runCatching {
            val supported = current.setLanguage(Locale.US)
            if (supported < TextToSpeech.LANG_AVAILABLE) return
            current.voices?.firstOrNull { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                ?.let { current.voice = it }
            current.setSpeechRate(.86f)
            available.value = true
        }
    }

    override fun speak(text: String): Boolean {
        if (closed || !available.value || text.isBlank()) return false
        return runCatching {
            speech?.speak(text.take(300), TextToSpeech.QUEUE_FLUSH, null, "english-assistant") == TextToSpeech.SUCCESS
        }.getOrDefault(false)
    }

    override fun stop() { if (!closed) runCatching { speech?.stop() } }
    override fun close() {
        if (closed) return
        closed = true; available.value = false
        runCatching { speech?.stop() }; runCatching { speech?.shutdown() }
        speech = null
    }
}
