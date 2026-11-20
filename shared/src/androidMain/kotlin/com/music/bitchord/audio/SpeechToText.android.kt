package com.music.bitchord.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Registers the application context the platform recogniser needs.
 *
 * `SpeechRecognizer` is constructed from a context, and the shared module has
 * no way to reach one from common code, so the app calls this once from its
 * `Application`, exactly as it does for LAN sync. Until it has, [speechToText]
 * reports itself unavailable.
 */
object AndroidVoiceInput {

    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    internal fun context(): Context? = appContext
}

actual fun speechToText(): SpeechToText = AndroidSpeechToText()

private class AndroidSpeechToText : SpeechToText {

    override val isAvailable: Boolean
        get() {
            val context = AndroidVoiceInput.context() ?: return false
            return SpeechRecognizer.isRecognitionAvailable(context)
        }

    override suspend fun listen(timeoutMs: Int): String? {
        val context = AndroidVoiceInput.context() ?: return null
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        // The recogniser must be created and driven on a looper thread; the
        // main thread is the one every Android app uses for it.
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs.toLong()) { recognize(context) }
        }
    }

    private suspend fun recognize(context: Context): String? = suspendCancellableCoroutine { continuation ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val main = Handler(Looper.getMainLooper())
        var finished = false

        fun finish(text: String?) {
            if (finished) return
            finished = true
            if (continuation.isActive) continuation.resume(text)
            main.post { runCatching { recognizer.destroy() } }
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { it.isNotBlank() }
                finish(spoken)
            }

            override fun onError(error: Int) = finish(null)

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        recognizer.startListening(intent)

        continuation.invokeOnCancellation {
            main.post { runCatching { recognizer.cancel() } }
        }
    }
}
