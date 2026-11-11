package com.akshay.offlineasr

import android.Manifest
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.akshay.offlineasr.ui.AppContent
import com.akshay.offlineasr.ui.theme.OfflineHinglishASRTheme
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the real UI and the real test-mode state transitions with a scripted recognizer, because
 * ML Kit GenAI speech recognition is UNAVAILABLE on the emulator. Nothing here ships in the app.
 */
@RunWith(AndroidJUnit4::class)
class TestModeFlowTest {

  @get:Rule val compose = createComposeRule()
  @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

  /** Scripted stand-in for the ViewModel: same reducers, fake mic that "hears" a fixed transcript. */
  private class ScriptedActions : AsrActions {
    var state by mutableStateOf(
      UiState(checking = false, featureStatus = FeatureStatus.AVAILABLE, mode = SpeechRecognizerOptions.Mode.MODE_ADVANCED, online = false),
    )
    var heard = ""
    override fun toggleMic() {
      state = if (!state.listening) {
        state.copy(listening = true, sessionEnded = false, partialText = heard.substringBefore(',').lowercase(), finalText = "")
      } else {
        state.copy(listening = false, sessionEnded = true, partialText = "", finalText = heard)
      }
    }
    override fun speakFinal() { state = state.copy(tts = TtsUi(engineLabel = "Platform TextToSpeech", note = "Fallback — scripted")) }
    override fun openTest() { state = state.withTestOpened() }
    override fun judge(survived: Boolean) { state = state.withJudgement(survived) }
    override fun retake() { state = state.withRetake() }
    override fun showResults() { state = state.copy(screen = Screen.RESULTS) }
    override fun goHome() { state = state.withHome() }
    override fun runTestIn(locale: AsrLocale) { state = state.copy(locale = locale).withFreshRun(locale) }
    override fun setLocale(locale: AsrLocale) { state = state.copy(locale = locale) }
    override fun initRecognizer() = Unit
    override fun downloadModel() = Unit
  }

  private val shots = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("shots")!!

  private fun shot(name: String) {
    compose.waitForIdle()
    val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
    File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  private fun speakAndJudge(a: ScriptedActions, transcript: String, survived: Boolean, shotPrefix: String? = null) {
    a.heard = transcript
    compose.onNodeWithText("🎤").performScrollTo().performClick()
    if (shotPrefix != null) shot("${shotPrefix}_listening")
    compose.onNodeWithText("■").performScrollTo().performClick()
    compose.onNodeWithText(transcript).assertExists()
    if (shotPrefix != null) shot("${shotPrefix}_judge")
    compose.onNodeWithText(if (survived) "Meaning" else "✗ Miss", substring = true).performScrollTo().performClick()
  }

  @Test
  fun fullTestBothLocales_scoresAndMarkdown() {
    val a = ScriptedActions()
    compose.setContent {
      OfflineHinglishASRTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppContent(a.state, a) }
      }
    }
    compose.onNodeWithText("Mode: ADVANCED").assertExists()
    compose.onNodeWithText("OFFLINE ✈️").assertExists()
    shot("01_home")

    // Home: one free-form utterance + read-back label.
    a.heard = "Kal office nahi aa raha, mera laptop crash ho gaya"
    compose.onNodeWithText("🎤").performScrollTo().performClick()
    compose.onNodeWithText("■").performScrollTo().performClick()
    compose.onNodeWithText("🔊  Read back").performScrollTo().performClick()
    compose.onNodeWithText("Engine: Platform TextToSpeech").assertExists()
    shot("02_home_transcript_tts")

    // hi-IN run: miss sentences 3, 6, 9 → 7/10.
    compose.onNodeWithText("Start 10-sentence test", substring = true).performScrollTo().performClick()
    compose.onNodeWithText("Say this:").assertExists()
    TEST_SENTENCES.forEachIndexed { i, s ->
      compose.onNodeWithText("Sentence ${i + 1} / 10").assertExists()
      compose.onNodeWithText(s).assertExists()
      val miss = i % 3 == 2
      speakAndJudge(a, if (miss) "garbled ${i + 1}" else s.lowercase(), survived = !miss, shotPrefix = if (i == 0) "03_test" else null)
      if (i < 9) compose.onAllNodesWithText("hi-IN · ${(0..i).count { it % 3 != 2 }}/${i + 1}").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }
    assertEquals(Screen.RESULTS, a.state.screen)
    assertTrue(compose.onAllNodesWithText("hi-IN · 7/10").fetchSemanticsNodes().isNotEmpty())
    shot("04_results_hi")

    // en-IN run: all correct → 10/10, both scores side by side.
    compose.onNodeWithText("Run en-IN").performScrollTo().performClick()
    assertEquals(AsrLocale.EN_IN, a.state.locale)
    TEST_SENTENCES.forEach { s -> speakAndJudge(a, s.removeSuffix("."), survived = true) }
    assertTrue(compose.onAllNodesWithText("en-IN · 10/10").fetchSemanticsNodes().isNotEmpty())
    assertTrue(compose.onAllNodesWithText("hi-IN · 7/10").fetchSemanticsNodes().isNotEmpty())
    shot("05_results_both")

    // Copy as Markdown → clipboard.
    compose.onNodeWithText("📋  Copy results as Markdown").performScrollTo().performClick()
    compose.waitForIdle()
    var clip = ""
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val cm = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
      clip = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
    }
    File(shots, "results.md").writeText(clip)
    assertTrue(clip, clip.contains("| hi-IN | ADVANCED | 7/10 |"))
    assertTrue(clip, clip.contains("| en-IN | ADVANCED | 10/10 |"))
    assertTrue(clip, clip.contains("| 3 | ${TEST_SENTENCES[2]} | garbled 3 | ✗ |"))
    assertTrue(clip, clip.contains("OFFLINE"))
  }

  @Test
  fun unavailable_showsFullScreenReason() {
    val a = ScriptedActions().apply {
      state = UiState(checking = false, featureStatus = FeatureStatus.UNAVAILABLE, attempts = listOf("ADVANCED · hi-IN → UNAVAILABLE", "BASIC · hi-IN → UNAVAILABLE"))
    }
    compose.setContent { OfflineHinglishASRTheme { Surface { AppContent(a.state, a) } } }
    compose.onNodeWithText("Speech recognition\nUNAVAILABLE").assertExists()
    compose.onNodeWithText("Mode: NONE").assertExists()
    compose.onNodeWithText("• BASIC · hi-IN → UNAVAILABLE").assertExists()
  }

  @Test
  fun downloadable_showsDownloadButtonAndProgress() {
    val a = ScriptedActions().apply { state = state.copy(featureStatus = FeatureStatus.DOWNLOADABLE) }
    compose.setContent { OfflineHinglishASRTheme { Surface { AppContent(a.state, a) } } }
    compose.onNodeWithText("⬇  Download model").assertExists()
    compose.onNodeWithText("Mic disabled", substring = true).assertExists()
    a.state = a.state.copy(featureStatus = FeatureStatus.DOWNLOADING, download = DownloadUi(bytesDone = 52_428_800, bytesTotal = 104_857_600))
    compose.onNodeWithText("50.0 MB / 100.0 MB  (50%)").assertExists()
    shot("06_downloading")
  }
}
