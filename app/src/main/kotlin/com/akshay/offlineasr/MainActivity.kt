package com.akshay.offlineasr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.akshay.offlineasr.ui.AppRoot
import com.akshay.offlineasr.ui.theme.OfflineHinglishASRTheme

class MainActivity : ComponentActivity() {
  private val vm: AsrViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    // Dark app → light system-bar icons regardless of system theme.
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    super.onCreate(savedInstanceState)
    setContent {
      OfflineHinglishASRTheme {
        // Surface sets LocalContentColor = onBackground (white); without it un-coloured Text defaults to black.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppRoot(vm) }
      }
    }
  }
}
