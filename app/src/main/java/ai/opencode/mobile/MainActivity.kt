package ai.opencode.mobile

import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ai.opencode.mobile.ui.OpenCodeApp
import ai.opencode.mobile.ui.theme.OpenCodeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        setContent {
            OpenCodeTheme {
                OpenCodeApp()
            }
        }
    }
}
