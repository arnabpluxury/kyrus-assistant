package com.kyrus.assistant.runtime.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * High-end voice: Android SpeechRecognizer + TTS (no Termux).
 */
class VoiceController(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    @Volatile
    var ttsReady = false
        private set

    fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    fun speak(text: String) {
        if (!ttsReady) initTts()
        val clip = text.take(500)
        tts?.speak(clip, TextToSpeech.QUEUE_FLUSH, null, "kyrus")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun canListen(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(
        activity: Activity,
        onPartial: (String) -> Unit = {},
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!canListen()) {
            onError("Speech recognition not available on this device")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    onError("STT error $error")
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    onResult(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (text.isNotBlank()) onPartial(text)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        recognizer?.startListening(intent)
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
