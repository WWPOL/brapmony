package lol.mony.harmony

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.Emoji
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.ui.components.MessageContent
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A custom emoji draws as its **image**, not as its `:shortcode:`.
 *
 * `MessagePartRenderTest` proves every part kind reaches the tree; this proves the picture
 * branch of `EmojiPart` really paints. The emoji's `url` is a `file://` URI to a PNG this test
 * writes, so nothing depends on a network the JVM runner does not have -- and the assertion is
 * on pixels, so an `AsyncImage` that never resolves (a wrong `data` type, a missing loader, a
 * mis-sized box) fails here instead of passing on the alt text alone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class EmojiImageRenderTest {
    @get:Rule val compose = createComposeRule()

    /** A solid block of one colour, so a drawn pixel is unmistakable. */
    private fun markerPng(): File {
        val file = File.createTempFile("emoji", ".png")
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(255, 0, 170))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun message(url: String) = Message(
        id = "emoji-message",
        createdAt = "2026-11-13T05:39:18+00:00",
        content = listOf(
            ContentPart.text("shipping "),
            ContentPart(type = "emoji", emoji = Emoji(id = "e1", name = "blobcat", url = url)),
            ContentPart.text(" today"),
        ),
    )

    @Test fun aCustomEmojiDrawsItsImage() {
        val png = markerPng()
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        MessageContent(message(png.toURI().toString()), 18.0)
                    }
                }
            }
        }
        compose.onNodeWithTag("emoji-blobcat").assertExists()

        // Coil decodes off the main thread, so the picture arrives a frame or two later.
        var image: Bitmap? = null
        compose.waitUntil(60_000) {
            image = compose.onRoot().captureToImage().asAndroidBitmap()
            hasMarker(image!!)
        }
        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"),
            "message-emoji-image.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the emoji's own image drew in $out", hasMarker(image!!))
    }

    /** True when a pixel of the marker colour is in the bitmap. */
    private fun hasMarker(bitmap: Bitmap): Boolean {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.red(pixel) > 200 && Color.green(pixel) < 80 && Color.blue(pixel) in 130..210) return true
        }
        return false
    }
}
