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

actual fun speechToText(): SpeechToText = AndroidSpeechToText

private object AndroidSpeechToText : SpeechToText {

    /** The recogniser currently listening, so [cancel] can reach it. */
    private var active: SpeechRecognizer? = null

    override val isAvailable: Boolean
        get() {
            val context = AndroidVoiceInput.context() ?: return false
            return SpeechRecognizer.isRecognitionAvailable(context)
        }

    override fun cancel() {
        val recognizer = active ?: return
        active = null
        runCatching { recognizer.cancel() }
        runCatching { recognizer.destroy() }
    }

    override suspend fun listen(
        timeoutMs: Int,
        onPartial: (String) -> Unit,
        onRms: (Float) -> Unit,
    ): String? {
        val context = AndroidVoiceInput.context() ?: return null
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        // The recogniser must be created and driven on a looper thread; the
        // main thread is the one every Android app uses for it.
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs.toLong()) { recognize(context, onPartial, onRms) }
        }
    }

    private suspend fun recognize(
        context: Context,
        onPartial: (String) -> Unit,
        onRms: (Float) -> Unit,
    ): String? = suspendCancellableCoroutine { continuation ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        active = recognizer
        val main = Handler(Looper.getMainLooper())
        var finished = false

        fun release() {
            if (active === recognizer) active = null
            main.post {
                // Both, in this order: cancel ends the session, destroy frees
                // the recogniser and with it the microphone.
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
        }

        fun finish(text: String?) {
            if (finished) return
            finished = true
            if (continuation.isActive) continuation.resume(text)
            release()
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { it.isNotBlank() }
                finish(spoken)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val spoken = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { it.isNotBlank() }
                if (!spoken.isNullOrBlank()) onPartial(spoken)
            }

            override fun onError(error: Int) = finish(null)

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit

            /**
             * The platform's own level meter, in decibels, roughly -2 (silence)
             * to 10 (loud). Mapped onto 0..1 so the sheet's bars have a scale
             * that does not depend on the device's calibration.
             */
            override fun onRmsChanged(rmsdB: Float) {
                onRms(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // The words appear as they are spoken, which is what the sheet's
            // live transcript shows while the orb reacts.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        recognizer.startListening(intent)

        continuation.invokeOnCancellation { release() }
    }
}
