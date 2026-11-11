package com.akshay.offlineasr.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akshay.offlineasr.AsrActions
import com.akshay.offlineasr.AsrLocale
import com.akshay.offlineasr.AsrViewModel
import com.akshay.offlineasr.MIN_SPEECH_SERVICES_VERSION
import com.akshay.offlineasr.SPEECH_SERVICES_PACKAGE
import com.akshay.offlineasr.Screen
import com.akshay.offlineasr.SpeechServicesInfo
import com.akshay.offlineasr.TEST_SENTENCES
import com.akshay.offlineasr.TestRun
import com.akshay.offlineasr.UiState
import com.akshay.offlineasr.androidLabel
import com.akshay.offlineasr.buildMarkdown
import com.akshay.offlineasr.deviceLabel
import com.akshay.offlineasr.statusName
import com.akshay.offlineasr.ui.theme.Mint
import com.akshay.offlineasr.ui.theme.Miss
import com.akshay.offlineasr.ui.theme.NearBlack
import com.akshay.offlineasr.ui.theme.Outline
import com.akshay.offlineasr.ui.theme.Panel
import com.akshay.offlineasr.ui.theme.PanelHigh
import com.akshay.offlineasr.ui.theme.Recording
import com.akshay.offlineasr.ui.theme.TextMuted
import com.akshay.offlineasr.ui.theme.TextPartial
import com.akshay.offlineasr.ui.theme.Warn
import com.google.mlkit.genai.common.FeatureStatus

@Composable
fun AppRoot(vm: AsrViewModel) {
  val state by vm.state.collectAsStateWithLifecycle()
  AppContent(state, vm)
}

/** Stateless UI: everything comes from [state], every event goes to [vm]. */
@Composable
fun AppContent(state: UiState, vm: AsrActions) {
  val context = LocalContext.current

  // ---- RECORD_AUDIO runtime permission flow
  fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
  var hasMic by remember { mutableStateOf(granted()) }
  var micDenied by remember { mutableStateOf(false) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
    hasMic = ok; micDenied = !ok
    if (ok) vm.toggleMic()
  }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasMic = granted(); if (hasMic) micDenied = false }
  val onMic = { if (hasMic) vm.toggleMic() else launcher.launch(Manifest.permission.RECORD_AUDIO) }

  BackHandler(enabled = state.screen != Screen.HOME) { vm.goHome() }

  Column(
    Modifier.fillMaxSize().background(NearBlack).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp),
  ) {
    Header(state)
    if (micDenied) PermissionBanner(onRetry = { launcher.launch(Manifest.permission.RECORD_AUDIO) }, onSettings = {
      context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    })
    Box(Modifier.weight(1f).fillMaxWidth()) {
      when {
        state.featureStatus == FeatureStatus.UNAVAILABLE && state.screen != Screen.RESULTS ->
          UnavailableScreen(state, onLocale = vm::setLocale, onRetry = vm::initRecognizer)
        state.screen == Screen.HOME -> HomeScreen(state, vm, onMic)
        state.screen == Screen.TEST -> TestScreen(state, vm, onMic)
        state.screen == Screen.RESULTS -> ResultsScreen(state, vm)
      }
    }
    Text(
      "$deviceLabel  ·  $androidLabel",
      style = MaterialTheme.typography.bodySmall, color = TextMuted,
      modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), textAlign = TextAlign.Center,
    )
  }
}

// ------------------------------------------------------------------ header

@Composable
private fun Header(state: UiState) {
  Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
    OneLine(
      when { state.checking -> "Mode: …"; state.mode == null -> "Mode: NONE"; else -> "Mode: ${state.modeLabel}" },
      MaterialTheme.typography.displaySmall.copy(color = Mint), maxSize = 34.sp,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Locale: ${state.locale.tag}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
      NetworkBadge(state.online)
    }
    if (state.screen != Screen.HOME) ScoreOverlay(state)
  }
}

/** Single-line label that shrinks to fit instead of wrapping — keeps the recording's top area stable. */
@Composable
private fun OneLine(text: String, style: TextStyle, maxSize: TextUnit, modifier: Modifier = Modifier) {
  BasicText(
    text, style = style, maxLines = 1, modifier = modifier,
    autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = maxSize, stepSize = 1.sp),
  )
}

@Composable
private fun NetworkBadge(online: Boolean) {
  val bg = if (online) Warn else Mint
  Text(
    if (online) "ONLINE" else "OFFLINE ✈️",
    color = NearBlack, fontWeight = FontWeight.Black, fontSize = 20.sp,
    modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 14.dp, vertical = 8.dp),
  )
}

@Composable
private fun ScoreOverlay(state: UiState) {
  Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    AsrLocale.entries.forEach { loc ->
      val run = state.runs[loc]
      val active = loc == state.locale
      Box(
        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
          .background(if (active) Mint else Panel)
          .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
      ) {
        OneLine(
          "${loc.tag} · ${run?.let { "${it.correct}/${it.judged}" } ?: "–"}",
          TextStyle(fontWeight = FontWeight.Black, color = if (active) NearBlack else TextMuted),
          maxSize = 30.sp, modifier = Modifier.padding(horizontal = 10.dp),
        )
      }
    }
  }
}

// ------------------------------------------------------------------ shared pieces

@Composable
private fun StatusAndDownload(state: UiState, onDownload: () -> Unit) {
  val color = when (state.featureStatus) {
    FeatureStatus.AVAILABLE -> Mint
    FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> Warn
    FeatureStatus.UNAVAILABLE -> Miss
    else -> TextMuted
  }
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(14.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Feature status: ", style = MaterialTheme.typography.titleMedium, color = TextMuted)
      Text(statusName(state.featureStatus), style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Black)
      if (state.checking) { Spacer(Modifier.width(10.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
    }
    if (state.featureStatus == FeatureStatus.DOWNLOADABLE && state.download == null) {
      Spacer(Modifier.height(10.dp))
      Button(onClick = onDownload, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("⬇  Download model", style = MaterialTheme.typography.labelLarge) }
    }
    state.download?.let { d ->
      Spacer(Modifier.height(10.dp))
      if (d.failed != null) {
        Text("Download failed: ${d.failed}", color = Miss, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Retry download") }
      } else {
        if (d.bytesTotal > 0) {
          LinearProgressIndicator(progress = { (d.bytesDone.toFloat() / d.bytesTotal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(10.dp))
          Text("${mb(d.bytesDone)} / ${mb(d.bytesTotal)}  (${(100 * d.bytesDone / d.bytesTotal).coerceIn(0, 100)}%)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        } else {
          LinearProgressIndicator(Modifier.fillMaxWidth().height(10.dp))
          Text("Downloading… ${if (d.bytesDone > 0) mb(d.bytesDone) else ""}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocaleSwitcher(state: UiState, onLocale: (AsrLocale) -> Unit) {
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
    AsrLocale.entries.forEachIndexed { i, loc ->
      SegmentedButton(
        selected = state.locale == loc,
        onClick = { onLocale(loc) },
        enabled = !state.listening,
        shape = SegmentedButtonDefaults.itemShape(i, AsrLocale.entries.size),
        colors = SegmentedButtonDefaults.colors(
          activeContainerColor = Mint, activeContentColor = NearBlack, activeBorderColor = Mint,
          inactiveContainerColor = Panel, inactiveContentColor = Color.White, inactiveBorderColor = Outline,
        ),
        modifier = Modifier.height(52.dp),
      ) { Text(loc.tag, style = MaterialTheme.typography.labelLarge) }
    }
  }
  if (!state.locale.documented) {
    Text(
      "${state.locale.tag} isn't in ML Kit's documented locale list — the status above is what this device reports.",
      style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(top = 4.dp),
    )
  }
}

@Composable
private fun TranscriptCard(state: UiState, placeholder: String, onSpeak: () -> Unit) {
  Column(
    Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(16.dp)).background(Panel)
      .border(1.dp, if (state.listening) Recording else Outline, RoundedCornerShape(16.dp)).padding(16.dp),
  ) {
    val empty = state.finalText.isBlank() && state.partialText.isBlank()
    if (empty) {
      Text(if (state.listening) "Listening…" else placeholder, color = TextPartial, style = MaterialTheme.typography.bodyLarge)
    } else {
      Text(
        buildAnnotatedString {
          withStyle(SpanStyle(color = Color.White)) { append(state.finalText) }
          if (state.partialText.isNotBlank()) {
            if (state.finalText.isNotBlank()) append(" ")
            withStyle(SpanStyle(color = TextPartial)) { append(state.partialText) }
          }
        },
        fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold,
      )
    }
    state.recognitionError?.let {
      Text("Error: $it", color = Miss, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
    if (!state.listening && state.finalText.isNotBlank()) {
      Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onSpeak, enabled = !state.tts.busy) { Text("🔊  Read back", style = MaterialTheme.typography.labelMedium) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          state.tts.engineLabel?.let { Text("Engine: $it", style = MaterialTheme.typography.labelSmall, color = Mint) }
          state.tts.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = TextMuted) }
        }
      }
    }
  }
}

@Composable
private fun MicButton(state: UiState, onMic: () -> Unit) {
  val enabled = state.featureStatus == FeatureStatus.AVAILABLE
  val pulse by animateFloatAsState(if (state.listening) 1f + (state.audioLevel.coerceIn(0, 100) / 100f) * 0.18f else 1f, label = "pulse")
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Surface(
      onClick = onMic, enabled = enabled, shape = CircleShape,
      color = when { !enabled -> PanelHigh; state.listening -> Recording; else -> Mint },
      modifier = Modifier.size(132.dp).scale(pulse),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text(if (state.listening) "■" else "🎤", fontSize = if (state.listening) 54.sp else 56.sp, color = NearBlack)
      }
    }
    Text(
      when { !enabled -> "Mic disabled — model ${statusName(state.featureStatus)}"; state.listening -> "Listening… tap to stop"; else -> "Tap to speak" },
      style = MaterialTheme.typography.titleMedium, color = TextMuted, modifier = Modifier.padding(top = 8.dp),
    )
  }
}

@Composable
private fun PermissionBanner(onRetry: () -> Unit, onSettings: () -> Unit) {
  Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(Miss.copy(alpha = 0.18f)).padding(12.dp)) {
    Text("Microphone permission is required to transcribe speech.", style = MaterialTheme.typography.bodyMedium)
    Row { TextButton(onClick = onRetry) { Text("Grant") }; TextButton(onClick = onSettings) { Text("Open settings") } }
  }
}

@Composable
private fun ScrollColumn(content: @Composable ColumnScope.() -> Unit) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

// ------------------------------------------------------------------ screens

@Composable
private fun HomeScreen(state: UiState, vm: AsrActions, onMic: () -> Unit) = ScrollColumn {
  StatusAndDownload(state, vm::downloadModel)
  LocaleSwitcher(state, vm::setLocale)
  TranscriptCard(state, "Tap the mic and speak Hinglish…", vm::speakFinal)
  MicButton(state, onMic)
  Button(
    onClick = vm::openTest, enabled = !state.listening && state.featureStatus == FeatureStatus.AVAILABLE,
    modifier = Modifier.fillMaxWidth().height(60.dp),
  ) { Text("▶  Start 10-sentence test (${state.locale.tag})", style = MaterialTheme.typography.labelLarge) }
  if (state.runs.isNotEmpty()) {
    OutlinedButton(onClick = vm::showResults, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("View results") }
  }
}

@Composable
private fun TestScreen(state: UiState, vm: AsrActions, onMic: () -> Unit) = ScrollColumn {
  val run = state.currentRun ?: return@ScrollColumn
  val idx = run.nextIndex.coerceAtMost(TEST_SENTENCES.lastIndex)
  if (state.featureStatus != FeatureStatus.AVAILABLE) StatusAndDownload(state, vm::downloadModel)
  Row(verticalAlignment = Alignment.CenterVertically) {
    TextButton(onClick = vm::goHome) { Text("← Home") }
    Spacer(Modifier.weight(1f))
    Text("Sentence ${idx + 1} / ${TEST_SENTENCES.size}", style = MaterialTheme.typography.titleLarge)
  }
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PanelHigh).padding(16.dp)) {
    Text("Say this:", style = MaterialTheme.typography.titleMedium, color = Mint)
    Text(TEST_SENTENCES[idx], fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
  }
  MicButton(state, onMic)
  TranscriptCard(state, "Transcript will appear here", vm::speakFinal)
  if (!state.listening && state.sessionEnded) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(
        onClick = { vm.judge(true) }, modifier = Modifier.weight(1f).height(76.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = NearBlack),
      ) { Text("✓ Meaning\nsurvived", fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center) }
      Button(
        onClick = { vm.judge(false) }, modifier = Modifier.weight(1f).height(76.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Miss, contentColor = Color.White),
      ) { Text("✗ Miss", fontSize = 22.sp, fontWeight = FontWeight.Black) }
    }
    TextButton(onClick = vm::retake, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("↺ Retake this sentence") }
  }
}

@Composable
private fun ResultsScreen(state: UiState, vm: AsrActions) = ScrollColumn {
  val context = LocalContext.current
  Row(verticalAlignment = Alignment.CenterVertically) {
    TextButton(onClick = vm::goHome) { Text("← Home") }
    Spacer(Modifier.weight(1f))
    Text("Results", style = MaterialTheme.typography.headlineMedium)
  }
  Button(
    onClick = {
      val cm = context.getSystemService(ClipboardManager::class.java)
      cm.setPrimaryClip(ClipData.newPlainText("Offline Hinglish ASR results", buildMarkdown(state)))
      Toast.makeText(context, "Results copied as Markdown", Toast.LENGTH_SHORT).show()
    },
    modifier = Modifier.fillMaxWidth().height(56.dp),
  ) { Text("📋  Copy results as Markdown", style = MaterialTheme.typography.labelLarge) }
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    AsrLocale.entries.forEach { loc ->
      OutlinedButton(onClick = { vm.runTestIn(loc) }, modifier = Modifier.weight(1f).height(52.dp)) {
        Text(if (state.runs[loc] == null) "Run ${loc.tag}" else "Re-run ${loc.tag}", style = MaterialTheme.typography.labelMedium)
      }
    }
  }
  AsrLocale.entries.mapNotNull { state.runs[it] }.forEach { ResultCard(it) }
}

@Composable
private fun ResultCard(run: TestRun) {
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp)) {
    Text("${run.locale.tag} · ${run.correct}/${run.judged}", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Mint)
    Text("Mode: ${run.modeLabel}   Locale: ${run.locale.tag}", style = MaterialTheme.typography.titleMedium)
    Text("Device: $deviceLabel · $androidLabel", style = MaterialTheme.typography.bodySmall, color = TextMuted)
    if (!run.finished) Text("In progress (${run.judged}/${TEST_SENTENCES.size} judged)", color = Warn, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(10.dp))
    TEST_SENTENCES.forEachIndexed { i, s ->
      val a = run.answers[i]
      Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
          when (a?.survived) { true -> "✓"; false -> "✗"; null -> "·" },
          color = when (a?.survived) { true -> Mint; false -> Miss; null -> TextMuted },
          fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(32.dp),
        )
        Column(Modifier.weight(1f)) {
          Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
          Text(a?.transcript?.ifBlank { "(empty transcript)" } ?: "—", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
      }
    }
  }
}

@Composable
private fun UnavailableScreen(state: UiState, onLocale: (AsrLocale) -> Unit, onRetry: () -> Unit) = ScrollColumn {
  Spacer(Modifier.height(8.dp))
  Text("⛔", fontSize = 64.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
  Text(
    "Speech recognition\nUNAVAILABLE",
    style = MaterialTheme.typography.displaySmall, color = Miss, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
  )
  Text(
    "checkStatus() returned FeatureStatus.UNAVAILABLE for ${state.locale.tag} in every mode tried on $deviceLabel ($androidLabel).",
    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
  )
  SpeechServicesCard(state.speechServices)
  if (state.attempts.any { "PERMISSION_DENIED" in it }) {
    Column(
      Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Warn.copy(alpha = 0.16f))
        .border(2.dp, Warn, RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
      Text("Speech Services refused access", style = MaterialTheme.typography.titleLarge, color = Warn)
      Text(
        "ML Kit reached Speech Services, but the service answered PERMISSION_DENIED (\"Api access revoked\"). " +
          "Speech Services itself decides this, not this app. Right after an update it can take minutes to hours to settle, and a restart can help. " +
          "If it never clears, this device is probably not enabled for the alpha.",
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(14.dp)) {
    Text("What the API reported", style = MaterialTheme.typography.titleMedium, color = Mint)
    state.attempts.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
  }
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(14.dp)) {
    Text("Possible reasons (per ML Kit docs)", style = MaterialTheme.typography.titleMedium, color = Mint)
    listOf(
      "ADVANCED needs Pixel 10/11 or a Gemini Nano chipset (e.g. Snapdragon SM8750+, Dimensity MT6991+, Exynos 2600).",
      "BASIC needs Android API 31+.",
      "${state.locale.tag} may not be supported in this mode" + if (!state.locale.documented) " (it isn't in the documented list)." else ".",
      "Not supported on devices with an unlocked bootloader.",
      "AICore / Speech Services may still be setting up — can take minutes to hours after setup; a restart can help.",
    ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = TextMuted) }
  }
  LocaleSwitcher(state, onLocale)
  Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("↻ Check again") }
}

@Composable
private fun SpeechServicesCard(info: SpeechServicesInfo?) {
  val context = LocalContext.current
  val bad = info == null || info.tooOld
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (bad) Warn.copy(alpha = 0.16f) else Panel)
      .border(2.dp, if (bad) Warn else Outline, RoundedCornerShape(16.dp)).padding(14.dp),
  ) {
    Text(if (bad) "Likely root cause" else "Speech Services by Google", style = MaterialTheme.typography.titleLarge, color = Warn)
    when {
      info == null -> Text("\"Speech Recognition & Synthesis from Google\" ($SPEECH_SERVICES_PACKAGE) is not installed. ML Kit needs it.", style = MaterialTheme.typography.bodyLarge)
      info.tooOld -> {
        Text("\"Speech Recognition & Synthesis from Google\" is too old for ML Kit.", style = MaterialTheme.typography.bodyLarge)
        Text("Installed: ${info.versionCode}", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Miss, modifier = Modifier.padding(top = 6.dp))
        Text("ML Kit needs: ≥ $MIN_SPEECH_SERVICES_VERSION", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Mint)
        Text(info.versionName, style = MaterialTheme.typography.bodySmall, color = TextMuted)
      }
      else -> Text("Version ${info.versionCode} meets ML Kit's minimum ($MIN_SPEECH_SERVICES_VERSION) — not the cause.", style = MaterialTheme.typography.bodyMedium)
    }
    if (bad) {
      Spacer(Modifier.height(10.dp))
      Button(
        onClick = {
          val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SPEECH_SERVICES_PACKAGE"))
          val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SPEECH_SERVICES_PACKAGE"))
          runCatching { context.startActivity(market) }.onFailure { context.startActivity(web) }
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
      ) { Text("Update in Play Store", style = MaterialTheme.typography.labelLarge) }
      Text("Then come back and tap ↻ Check again.", style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(top = 4.dp))
    }
  }
}

private fun mb(b: Long) = "%.1f MB".format(b / 1_048_576.0)
