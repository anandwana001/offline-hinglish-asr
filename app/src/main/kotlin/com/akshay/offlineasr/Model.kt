package com.akshay.offlineasr

import android.os.Build
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import java.util.Locale

/**
 * Locales offered in the switcher. ML Kit picks the locale via `speechRecognizerOptions { locale = … }`.
 * Note: as of the alpha2 docs, hi-IN is listed (beta) for both BASIC and ADVANCED, but en-IN is not
 * listed for either — the docs only say "additional locales are available". We let checkStatus() decide.
 */
enum class AsrLocale(val tag: String, val locale: Locale, val documented: Boolean) {
  HI_IN("hi-IN", Locale.forLanguageTag("hi-IN"), documented = true),
  EN_IN("en-IN", Locale.forLanguageTag("en-IN"), documented = false),
}

enum class Screen { HOME, TEST, RESULTS }

val TEST_SENTENCES = listOf(
  "Kal office nahi aa raha, mera laptop crash ho gaya.",
  "Bhai, meeting ko 4 baje shift kar do please.",
  "Mummy ne bola hai ki aaj Bhai Dooj pe ghar jaldi aana.",
  "Ye bug sirf release build mein aa raha hai, debug mein nahi.",
  "Mujhe Udaipur se Jaipur ka train ticket book karna hai.",
  "Ek kaam karo, PR merge karne se pehle tests chala lo.",
  "Chhath ke liye Bihar ki saari trains full hain yaar.",
  "Mera UPI payment fail ho gaya but paisa kat gaya.",
  "Compose mein recomposition bahut zyada ho raha hai.",
  "Weekend pe Android Engineers ka meetup hai, aa jaana.",
)

data class Judgement(val transcript: String, val survived: Boolean)

data class TestRun(
  val locale: AsrLocale,
  val modeLabel: String,
  val answers: List<Judgement?> = List(TEST_SENTENCES.size) { null },
) {
  val judged get() = answers.count { it != null }
  val correct get() = answers.count { it?.survived == true }
  val finished get() = judged == TEST_SENTENCES.size
  val nextIndex get() = answers.indexOfFirst { it == null }.let { if (it < 0) TEST_SENTENCES.size else it }
}

data class DownloadUi(val bytesDone: Long = 0, val bytesTotal: Long = 0, val failed: String? = null)

data class TtsUi(val busy: Boolean = false, val engineLabel: String? = null, val note: String? = null)

data class UiState(
  val locale: AsrLocale = AsrLocale.HI_IN,
  val checking: Boolean = true,
  val featureStatus: Int? = null,
  val mode: Int? = null,
  /** One line per (mode → status) attempt, shown on the UNAVAILABLE screen. */
  val attempts: List<String> = emptyList(),
  val download: DownloadUi? = null,
  val listening: Boolean = false,
  val audioLevel: Int = 0,
  val partialText: String = "",
  val finalText: String = "",
  val recognitionError: String? = null,
  /** True once a mic session has finished (so the judge buttons can show even for an empty result). */
  val sessionEnded: Boolean = false,
  val online: Boolean = true,
  val screen: Screen = Screen.HOME,
  val runs: Map<AsrLocale, TestRun> = emptyMap(),
  val tts: TtsUi = TtsUi(),
) {
  val modeLabel get() = mode?.let(::modeName) ?: "—"
  val currentRun get() = runs[locale]
}

fun modeName(mode: Int) = when (mode) {
  SpeechRecognizerOptions.Mode.MODE_ADVANCED -> "ADVANCED"
  SpeechRecognizerOptions.Mode.MODE_BASIC -> "BASIC"
  else -> "MODE($mode)"
}

fun statusName(status: Int?) = when (status) {
  FeatureStatus.AVAILABLE -> "AVAILABLE"
  FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
  FeatureStatus.DOWNLOADING -> "DOWNLOADING"
  FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
  null -> "CHECKING…"
  else -> "STATUS($status)"
}

val deviceLabel: String get() = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
val androidLabel: String get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

fun Throwable.describe(): String {
  val ge = this as? com.google.mlkit.genai.common.GenAiException
  val codes = ge?.let { " [errorCode=${it.errorCode}, aiCoreType=${it.aiCoreErrorType}, aiCoreCode=${it.aiCoreErrorCode}]" } ?: ""
  return "${this::class.simpleName}: ${message ?: "(no message)"}$codes"
}

fun buildMarkdown(state: UiState): String = buildString {
  appendLine("## Offline Hinglish ASR — ML Kit GenAI Speech Recognition")
  appendLine()
  appendLine("- **Device:** $deviceLabel")
  appendLine("- **OS:** $androidLabel")
  appendLine("- **Network during test:** ${if (state.online) "ONLINE" else "OFFLINE (airplane mode)"}")
  appendLine()
  appendLine("| Locale | Mode | Score |")
  appendLine("|---|---|---|")
  AsrLocale.entries.forEach { loc ->
    val run = state.runs[loc]
    appendLine("| ${loc.tag} | ${run?.modeLabel ?: "—"} | ${run?.let { "${it.correct}/${it.judged}" } ?: "not run"} |")
  }
  state.runs.values.forEach { run ->
    appendLine()
    appendLine("### ${run.locale.tag} · ${run.modeLabel} · ${run.correct}/${run.judged}")
    appendLine()
    appendLine("| # | Said | Transcript | Result |")
    appendLine("|---|---|---|---|")
    TEST_SENTENCES.forEachIndexed { i, s ->
      val a = run.answers[i]
      val t = a?.transcript?.ifBlank { "(empty)" }?.replace("|", "\\|") ?: "—"
      appendLine("| ${i + 1} | $s | $t | ${when (a?.survived) { true -> "✓"; false -> "✗"; null -> "—" }} |")
    }
  }
}

/** Everything the UI can ask for. Implemented by [AsrViewModel]; tests can supply their own. */
interface AsrActions {
  fun initRecognizer()
  fun setLocale(locale: AsrLocale)
  fun downloadModel()
  fun toggleMic()
  fun speakFinal()
  fun openTest()
  fun judge(survived: Boolean)
  fun retake()
  fun showResults()
  fun goHome()
  fun runTestIn(locale: AsrLocale)
}

// ---- Pure state transitions for test mode (shared by the ViewModel and tests).

private val UiState.resolvedModeLabel get() = if (checking || mode == null) "…" else modeLabel

private fun UiState.clearedTranscript() =
  copy(partialText = "", finalText = "", recognitionError = null, sessionEnded = false, tts = TtsUi())

fun UiState.withTestOpened(): UiState {
  val existing = runs[locale]
  val run = if (existing == null || existing.finished) TestRun(locale, resolvedModeLabel) else existing
  return clearedTranscript().copy(screen = Screen.TEST, runs = runs + (locale to run))
}

fun UiState.withFreshRun(locale: AsrLocale): UiState =
  clearedTranscript().copy(screen = Screen.TEST, runs = runs + (locale to TestRun(locale, resolvedModeLabel)))

fun UiState.withJudgement(survived: Boolean): UiState {
  val run = currentRun ?: return this
  val idx = run.nextIndex
  if (idx >= TEST_SENTENCES.size) return this
  val updated = run.copy(answers = run.answers.toMutableList().also { it[idx] = Judgement(finalText, survived) })
  return clearedTranscript().copy(
    runs = runs + (locale to updated),
    screen = if (updated.finished) Screen.RESULTS else Screen.TEST,
  )
}

fun UiState.withRetake(): UiState = copy(partialText = "", finalText = "", recognitionError = null, sessionEnded = false)

fun UiState.withHome(): UiState = copy(screen = Screen.HOME, partialText = "", finalText = "", sessionEnded = false)
