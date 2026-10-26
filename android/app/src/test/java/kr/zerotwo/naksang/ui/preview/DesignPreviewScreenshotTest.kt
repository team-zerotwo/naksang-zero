package kr.zerotwo.naksang.ui.preview

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import kr.zerotwo.naksang.ui.theme.NaksangTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders P1–P5 at the design frame (390×844dp) without a device.
 * Images are written when Gradle is invoked with -Proborazzi.test.record=true.
 * A plain unit-test run still composes each screen so a broken layout fails here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w390dp-h844dp-mdpi",
    application = android.app.Application::class,
)
class DesignPreviewScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun p1Connect() = render("p1")

    @Test fun p2Camera() = render("p2")

    @Test fun p3Standby() = render("p3")

    @Test fun p4Countdown() = render("p4")

    @Test fun p5Sent() = render("p5")

    private fun render(which: String) {
        // Native-graphics screenshots are opt-in so the APK workflow stays a JVM unit-test job.
        assumeTrue(System.getProperty("roborazzi.test.record") == "true")
        composeRule.setContent {
            NaksangTheme {
                DesignPreview(which)
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
