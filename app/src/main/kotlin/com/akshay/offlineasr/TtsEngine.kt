package com.akshay.offlineasr

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.texttospeech.SpeechSynthesis
import com.google.mlkit.genai.texttospeech.SpeechSynthesizer
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerRequest
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerResponse
import com.google.mlkit.genai.texttospeech.VoiceInfo
import com.google.mlkit.genai.texttospeech.speechSynthesizerOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Reads transcripts back. Tries ML Kit GenAI TTS first (checkStatus → download if DOWNLOADABLE →
 * synthesize, which plays the audio itself), then falls back to the platform TextToSpeech API.
 * Every call reports which engine actually spoke so the UI can label it.
 */
class TtsEngine(private val context: Context) {

  private var mlkit: SpeechSynthesizer? = null
  private var platform: TextToSpeech? = null
  private var platformReady: CompletableDeferred<Boolean>? = null

  private val audioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

  /** ML Kit TTS documents hi-IN but no en-IN voice, so en-IN falls back to other English voices. */
  private fun candidateLocales(asr: AsrLocale) = when (asr) {
    AsrLocale.HI_IN -> listOf(Locale.forLanguageTag("hi-IN"))
    AsrLocale.EN_IN -> listOf(Locale.forLanguageTag("en-IN"), Locale.UK, Locale.US)
  }

  suspend fun speak(text: String, asr: AsrLocale, onUpdate: (TtsUi) -> Unit) {
    val reason = try {
      speakMlKit(text, asr, onUpdate) ?: return
    } catch (e: Throwable) {
      if (e is kotlinx.coroutines.CancellationException) throw e
      "ML Kit TTS threw ${e.describe()}"
    }
    // Fallback path.
    onUpdate(TtsUi(busy = true, engineLabel = "Platform TextToSpeech", note = "Fallback — $reason"))
    val ok = speakPlatform(text, asr.locale)
    onUpdate(
      TtsUi(
        busy = false,
        engineLabel = "Platform TextToSpeech",
        note = if (ok) "Fallback — $reason" else "Platform TTS failed too — $reason",
      ),
    )
  }

  /** Returns null on success, or a human-readable reason to fall back. */
  private suspend fun speakMlKit(text: String, asr: AsrLocale, onUpdate: (TtsUi) -> Unit): String? {
    val client = mlkit ?: SpeechSynthesis.getClient(speechSynthesizerOptions(asr.locale)).also { mlkit = it }

    var voice: VoiceInfo? = null
    for (loc in candidateLocales(asr)) {
      voice = client.getSupportedVoices(loc).firstOrNull()
      if (voice != null) break
    }
    if (voice == null) return "ML Kit TTS: no voices for ${asr.tag} (getSupportedVoices empty → device unsupported or Speech Services app outdated)"

    val label = "ML Kit GenAI TTS · ${voice.name} · ${voice.locale.toLanguageTag()}"
    var status = client.checkStatus(voice)
    onUpdate(TtsUi(busy = true, engineLabel = label, note = "Voice status: ${statusName(status)}"))

    if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
      var total = 0L
      var failure: String? = null
      client.download(voice).collect { d ->
        when (d) {
          is DownloadStatus.DownloadStarted -> total = d.bytesToDownload
          is DownloadStatus.DownloadProgress -> onUpdate(
            TtsUi(busy = true, engineLabel = label, note = "Downloading voice… ${mb(d.totalBytesDownloaded)} / ${mb(total)}"),
          )
          is DownloadStatus.DownloadFailed -> failure = d.e.describe()
          is DownloadStatus.DownloadCompleted -> Unit
        }
      }
      if (failure != null) return "ML Kit TTS voice download failed: $failure"
      status = client.checkStatus(voice)
    }
    if (status != FeatureStatus.AVAILABLE) return "ML Kit TTS status ${statusName(status)}"

    val request = SpeechSynthesizerRequest(
      input = SpeechSynthesizerRequest.Input.Text(text, voice),
      audioAttributes = audioAttributes,
      queueMode = SpeechSynthesizerRequest.QueueMode.QUEUE_FLUSH,
    )
    var error: Int? = null
    onUpdate(TtsUi(busy = true, engineLabel = label, note = "Speaking…"))
    client.synthesize(request).collect { r ->
      if (r is SpeechSynthesizerResponse.Error) error = r.errorCode
    }
    if (error != null) return "ML Kit TTS synthesize() error code $error"
    onUpdate(TtsUi(busy = false, engineLabel = label, note = "Voice status: AVAILABLE"))
    return null
  }

  private suspend fun speakPlatform(text: String, locale: Locale): Boolean {
    val ready = platformReady ?: CompletableDeferred<Boolean>().also { d ->
      platformReady = d
      platform = TextToSpeech(context) { status -> d.complete(status == TextToSpeech.SUCCESS) }
    }
    if (!ready.await()) return false
    val tts = platform ?: return false
    tts.setAudioAttributes(audioAttributes)
    tts.language = locale
    return suspendCancellableCoroutine { cont ->
      val id = "utt-${System.nanoTime()}"
      tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(true) }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(false) }
        override fun onError(utteranceId: String?, errorCode: Int) { if (cont.isActive) cont.resume(false) }
      })
      if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
      cont.invokeOnCancellation { tts.stop() }
    }
  }

  fun stop() {
    runCatching { mlkit?.stop() }
    runCatching { platform?.stop() }
  }

  fun close() {
    runCatching { mlkit?.close() }
    runCatching { platform?.shutdown() }
  }

  private fun mb(b: Long) = if (b <= 0) "?" else "%.1f MB".format(b / 1_048_576.0)
}
