package com.akshay.offlineasr

import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Diagnostic only: logs (tag "Probe") what this device reports. Never fails. */
@RunWith(AndroidJUnit4::class)
class DeviceProbeTest {
  private fun log(msg: String) = Log.i("Probe", msg)

  @Test
  fun probe(): Unit = runBlocking {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    log("device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} soc=${Build.SOC_MODEL}")

    for (tag in listOf("en-US", "hi-IN", "en-IN", "es-ES", "ja-JP")) {
      for (mode in listOf(SpeechRecognizerOptions.Mode.MODE_BASIC, SpeechRecognizerOptions.Mode.MODE_ADVANCED)) {
        val r = runCatching {
          SpeechRecognition.getClient(speechRecognizerOptions { locale = Locale.forLanguageTag(tag); preferredMode = mode })
        }.getOrElse { log("mlkit $tag ${modeName(mode)} getClient threw ${it.describe()}"); continue }
        val s = runCatching { statusName(r.checkStatus()) }.getOrElse { "THREW ${it.describe()}" }
        log("mlkit $tag ${modeName(mode)} -> $s")
        r.close()
      }
    }

    // Platform on-device recognizer (API 31+) — BASIC is documented as "similar to the SpeechRecognizer API".
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      log("platform isRecognitionAvailable=${SpeechRecognizer.isRecognitionAvailable(ctx)} " +
        "isOnDeviceRecognitionAvailable=${SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)}")
    }
    val latch = CountDownLatch(1)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching {
        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
          .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        rec.checkRecognitionSupport(intent, Executors.newSingleThreadExecutor(), object : RecognitionSupportCallback {
          override fun onSupportResult(s: RecognitionSupport) {
            log("platform onDevice installed=${s.installedOnDeviceLanguages}")
            log("platform onDevice pending=${s.pendingOnDeviceLanguages}")
            log("platform onDevice supported(downloadable)=${s.supportedOnDeviceLanguages}")
            log("platform online=${s.onlineLanguages}")
            latch.countDown()
          }
          override fun onError(error: Int) { log("platform checkRecognitionSupport error=$error"); latch.countDown() }
        })
      }.onFailure { log("platform on-device recognizer threw ${it.describe()}"); latch.countDown() }
    }
    latch.await(20, TimeUnit.SECONDS)
  }
}
