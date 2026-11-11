package com.akshay.offlineasr

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "OfflineAsr"

class AsrViewModel(app: Application) : AndroidViewModel(app), AsrActions {

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  private var recognizer: SpeechRecognizer? = null
  private var initJob: Job? = null
  private var downloadJob: Job? = null
  private var recognitionJob: Job? = null
  private var ttsJob: Job? = null
  private val tts = TtsEngine(app)

  private val connectivity = app.getSystemService(ConnectivityManager::class.java)
  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
      _state.update { it.copy(online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) }
    }
    override fun onLost(network: Network) = _state.update { it.copy(online = false) }
    override fun onUnavailable() = _state.update { it.copy(online = false) }
  }

  init {
    val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    _state.update { it.copy(online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) }
    connectivity.registerDefaultNetworkCallback(networkCallback)
    initRecognizer()
  }

  // ---------------------------------------------------------------- recognizer setup

  /**
   * Creates the recognizer and checks feature status. Tries MODE_ADVANCED first and falls back to
   * MODE_BASIC when ADVANCED reports UNAVAILABLE (or throws). Note: the alpha API only exposes
   * `preferredMode` — there is no getter for the mode actually in use — so the label shows the
   * mode we requested on the client whose checkStatus() wasn't UNAVAILABLE.
   */
  override fun initRecognizer() {
    initJob?.cancel(); downloadJob?.cancel(); stopListeningInternal()
    recognizer?.close(); recognizer = null
    val loc = _state.value.locale
    val sbg = readSpeechServices()
    Log.i(TAG, "Speech Services: $sbg (ML Kit minimum $MIN_SPEECH_SERVICES_VERSION)")
    _state.update {
      it.copy(speechServices = sbg, checking = true, featureStatus = null, mode = null, attempts = emptyList(), download = null,
        partialText = "", finalText = "", recognitionError = null, sessionEnded = false)
    }
    initJob = viewModelScope.launch {
      val attempts = mutableListOf<String>()
      for (mode in listOf(SpeechRecognizerOptions.Mode.MODE_ADVANCED, SpeechRecognizerOptions.Mode.MODE_BASIC)) {
        val client = try {
          SpeechRecognition.getClient(speechRecognizerOptions { locale = loc.locale; preferredMode = mode })
        } catch (e: Throwable) {
          if (e is CancellationException) throw e
          attempts += "${modeName(mode)} · ${loc.tag}: getClient() threw ${e.describe()}"; continue
        }
        val status = try {
          client.checkStatus()
        } catch (e: Throwable) {
          if (e is CancellationException) { client.close(); throw e }
          attempts += "${modeName(mode)} · ${loc.tag}: checkStatus() threw ${e.describe()}"
          client.close(); continue
        }
        attempts += "${modeName(mode)} · ${loc.tag} → ${statusName(status)}"
        Log.i(TAG, attempts.last())
        if (status != FeatureStatus.UNAVAILABLE) {
          recognizer = client
          _state.update { s ->
            val run = s.runs[loc]?.takeIf { it.modeLabel == "…" }?.copy(modeLabel = modeName(mode))
            s.copy(checking = false, featureStatus = status, mode = mode, attempts = attempts.toList(),
              runs = if (run != null) s.runs + (loc to run) else s.runs)
          }
          if (status == FeatureStatus.DOWNLOADING) downloadModel() // attach to the in-flight download for progress
          return@launch
        }
        client.close()
      }
      _state.update {
        it.copy(checking = false, featureStatus = FeatureStatus.UNAVAILABLE, mode = null, attempts = attempts.toList())
      }
    }
  }

  private fun readSpeechServices(): SpeechServicesInfo? = runCatching {
    val pi = getApplication<Application>().packageManager.getPackageInfo(SPEECH_SERVICES_PACKAGE, 0)
    SpeechServicesInfo(pi.longVersionCode, pi.versionName ?: "?")
  }.getOrNull()

  override fun setLocale(locale: AsrLocale) {
    if (locale == _state.value.locale) return
    _state.update { it.copy(locale = locale) }
    initRecognizer()
  }

  override fun downloadModel() {
    val r = recognizer ?: return
    if (downloadJob?.isActive == true) return
    _state.update { it.copy(download = DownloadUi(), featureStatus = FeatureStatus.DOWNLOADING) }
    downloadJob = viewModelScope.launch {
      try {
        r.download().collect { d ->
          when (d) {
            is DownloadStatus.DownloadStarted ->
              _state.update { it.copy(download = DownloadUi(bytesTotal = d.bytesToDownload)) }
            is DownloadStatus.DownloadProgress ->
              _state.update { it.copy(download = (it.download ?: DownloadUi()).copy(bytesDone = d.totalBytesDownloaded)) }
            is DownloadStatus.DownloadFailed ->
              _state.update { it.copy(download = (it.download ?: DownloadUi()).copy(failed = d.e.describe())) }
            is DownloadStatus.DownloadCompleted -> Unit
          }
        }
        val status = r.checkStatus()
        _state.update { it.copy(featureStatus = status, download = if (status == FeatureStatus.AVAILABLE) null else it.download) }
      } catch (e: Throwable) {
        if (e is CancellationException) throw e
        _state.update { it.copy(download = (it.download ?: DownloadUi()).copy(failed = e.describe()), featureStatus = FeatureStatus.DOWNLOADABLE) }
      }
    }
  }

  // ---------------------------------------------------------------- recognition

  override fun toggleMic() = if (_state.value.listening) stopListening() else startListening()

  private fun startListening() {
    val r = recognizer ?: return
    if (_state.value.featureStatus != FeatureStatus.AVAILABLE) return
    tts.stop(); ttsJob?.cancel()
    _state.update { it.copy(listening = true, sessionEnded = false, partialText = "", finalText = "", recognitionError = null, audioLevel = 0, tts = TtsUi()) }
    recognitionJob = viewModelScope.launch {
      val source = AudioSource.fromMic()
      try {
        r.startRecognition(speechRecognizerRequest { audioSource = source }).collect { resp ->
          when (resp) {
            is SpeechRecognizerResponse.PartialTextResponse ->
              _state.update { it.copy(partialText = resp.text) }
            is SpeechRecognizerResponse.FinalTextResponse ->
              _state.update {
                it.copy(finalText = listOf(it.finalText, resp.text).filter(String::isNotBlank).joinToString(" "), partialText = "")
              }
            is SpeechRecognizerResponse.AudioLevelResponse ->
              _state.update { it.copy(audioLevel = resp.audioLevel) }
            is SpeechRecognizerResponse.ErrorResponse ->
              _state.update { it.copy(recognitionError = resp.e.describe()) }
            is SpeechRecognizerResponse.CompletedResponse -> Log.i(TAG, "CompletedResponse")
          }
        }
      } catch (e: Throwable) {
        if (e !is CancellationException) _state.update { it.copy(recognitionError = e.describe()) }
      } finally {
        runCatching { source.close() }
        _state.update {
          // If the session ended with only a partial hypothesis, promote it so nothing is lost.
          val promoted = if (it.finalText.isBlank() && it.partialText.isNotBlank()) it.partialText else it.finalText
          it.copy(listening = false, sessionEnded = true, audioLevel = 0, finalText = promoted, partialText = "")
        }
      }
    }
  }

  private fun stopListening() {
    val r = recognizer ?: return
    // Ask the recognizer to finish; it emits the remaining FinalTextResponse then CompletedResponse.
    viewModelScope.launch { runCatching { r.stopRecognition() } }
  }

  private fun stopListeningInternal() {
    recognitionJob?.cancel(); recognitionJob = null
    _state.update { it.copy(listening = false, audioLevel = 0) }
  }

  // ---------------------------------------------------------------- TTS

  override fun speakFinal() {
    val text = _state.value.finalText.ifBlank { return }
    ttsJob?.cancel()
    ttsJob = viewModelScope.launch {
      tts.speak(text, _state.value.locale) { t -> _state.update { it.copy(tts = t) } }
    }
  }

  // ---------------------------------------------------------------- test mode

  override fun openTest() { stopListeningInternal(); _state.update { it.withTestOpened() } }

  override fun judge(survived: Boolean) = _state.update { it.withJudgement(survived) }

  override fun retake() = _state.update { it.withRetake() }

  override fun showResults() { stopListeningInternal(); _state.update { it.copy(screen = Screen.RESULTS) } }

  override fun goHome() { stopListeningInternal(); _state.update { it.withHome() } }

  /** From the results card: switch locale (re-inits the recognizer) and start a fresh run there. */
  override fun runTestIn(locale: AsrLocale) {
    if (locale != _state.value.locale) setLocale(locale)
    _state.update { it.withFreshRun(locale) }
  }

  override fun onCleared() {
    runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
    recognitionJob?.cancel()
    recognizer?.close()
    tts.close()
  }
}
