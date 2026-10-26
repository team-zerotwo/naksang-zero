package kr.zerotwo.naksang

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import kr.zerotwo.naksang.ui.NaksangRoot
import kr.zerotwo.naksang.ui.preview.DesignPreview
import kr.zerotwo.naksang.ui.theme.NaksangTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val preview = if (BuildConfig.DEBUG) intent?.getStringExtra("preview") else null
        val container = (application as NaksangApp).container
        setContent {
            NaksangTheme {
                if (preview != null) DesignPreview(preview) else NaksangRoot(container)
            }
        }
    }
}
