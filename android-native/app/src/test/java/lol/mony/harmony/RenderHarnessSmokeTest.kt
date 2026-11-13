package lol.mony.harmony

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30])
class RenderHarnessSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rendersPixels() {
        compose.setContent { MaterialTheme { Surface { Text("Render harness") } } }
        compose.waitForIdle()
        val image = compose.onNodeWithText("Render harness").captureToImage().asAndroidBitmap()
        val out = File(System.getenv("NATIVE_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"), "smoke.png")
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var nonBackground = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getPixel(x, y) != image.getPixel(0, 0)) nonBackground++
        }
        assertTrue("expected drawn text pixels in $out", nonBackground > 50)
    }
}
