package com.google.ai.edge.gallery.customtasks.smartsurveillance

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

sealed interface TtsState {
  data object Initializing : TtsState
  data class Ready(val language: String) : TtsState
  data class Unavailable(val reason: String) : TtsState
  data class SpeakQueued(val text: String) : TtsState
  data class Speaking(val utteranceId: String) : TtsState
  data class Done(val utteranceId: String) : TtsState
  data class Error(val reason: String) : TtsState
}

sealed interface TtsSpeakResult {
  data class Queued(val utteranceId: String) : TtsSpeakResult
  data class NotReady(val reason: String) : TtsSpeakResult
  data object BlankText : TtsSpeakResult
  data class SpeakFailed(val code: Int) : TtsSpeakResult
}

class SurveillanceTtsController(context: Context) {
  private var ready = false
  private var tts: TextToSpeech? = null
  private var unavailableReason: String? = null
  private var statusListener: ((TtsState) -> Unit)? = null

  var currentState: TtsState = TtsState.Initializing
    private set

  init {
    updateState(TtsState.Initializing)
    tts = TextToSpeech(context.applicationContext) { status ->
      ready = status == TextToSpeech.SUCCESS
      if (!ready) {
        unavailableReason = "TTS initialization failed with status $status"
        updateState(TtsState.Unavailable(unavailableReason!!))
        return@TextToSpeech
      }

      val locale = Locale.getDefault()
      val languageResult = tts?.setLanguage(locale) ?: TextToSpeech.ERROR
      if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
        ready = false
        unavailableReason = "TTS language is not supported: ${locale.toLanguageTag()}"
        updateState(TtsState.Unavailable(unavailableReason!!))
        return@TextToSpeech
      }

      tts?.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String) {
            updateState(TtsState.Speaking(utteranceId))
          }

          override fun onDone(utteranceId: String) {
            updateState(TtsState.Done(utteranceId))
          }

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String) {
            updateState(TtsState.Error("TTS error for $utteranceId"))
          }

          override fun onError(utteranceId: String, errorCode: Int) {
            updateState(TtsState.Error("TTS error $errorCode for $utteranceId"))
          }
        }
      )
      unavailableReason = null
      updateState(TtsState.Ready(locale.toLanguageTag()))
    }
  }

  fun setStatusListener(listener: ((TtsState) -> Unit)?) {
    statusListener = listener
    listener?.invoke(currentState)
  }

  fun speak(text: String): TtsSpeakResult {
    if (text.isBlank()) return TtsSpeakResult.BlankText
    if (!ready) return TtsSpeakResult.NotReady(unavailableReason ?: "TTS is not ready")
    val utteranceId = "surveillance_${System.currentTimeMillis()}"
    val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) ?: TextToSpeech.ERROR
    return if (result == TextToSpeech.SUCCESS) {
      updateState(TtsState.SpeakQueued(text))
      TtsSpeakResult.Queued(utteranceId)
    } else {
      updateState(TtsState.Error("TTS speak failed with code $result"))
      TtsSpeakResult.SpeakFailed(result)
    }
  }

  fun shutdown() {
    tts?.stop()
    tts?.shutdown()
    tts = null
    ready = false
    updateState(TtsState.Unavailable("TTS shutdown"))
    statusListener = null
  }

  private fun updateState(state: TtsState) {
    currentState = state
    statusListener?.invoke(state)
  }
}
