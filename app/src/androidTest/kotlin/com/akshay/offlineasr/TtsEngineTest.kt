package com.akshay.offlineasr

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the REAL TtsEngine on the device: ML Kit GenAI TTS if available, platform fallback otherwise. */
@RunWith(AndroidJUnit4::class)
class TtsEngineTest {
  @Test
  fun speak_reportsWhichEngineWasUsed() = runBlocking {
    val engine = TtsEngine(InstrumentationRegistry.getInstrumentation().targetContext)
    val updates = mutableListOf<TtsUi>()
    for (loc in AsrLocale.entries) {
      updates.clear()
      withTimeout(90_000) { engine.speak("Kal office nahi aa raha, mera laptop crash ho gaya.", loc) { updates += it } }
      updates.forEach { Log.i("TtsEngineTest", "${loc.tag}: $it") }
      val last = updates.last()
      assertNotNull(last.engineLabel)
      assertTrue("finished", !last.busy)
    }
    engine.close()
  }
}
