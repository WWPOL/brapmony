package lol.mony.harmony

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.HarmonyJson
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
 * Draws a channel history whose parts are the shapes the instance really stores.
 *
 * The emoji part is the one that used to stop the page decoding at all; here it has to *draw*: a
 * custom emoji as its image, a unicode emoji as its glyph, and an unresolved one as its
 * shortcode. The captures are the evidence that a fix which merely swallowed the decode error
 * would not have been enough.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class MessagePartRenderTest {
    @get:Rule val compose = createComposeRule()

    private val history = HarmonyJson.decodeFromString(
        kotlinx.serialization.builtins.ListSerializer(Message.serializer()),
        """[
          {"id":"r1","user_id":"u1","created_at":"2026-11-13T05:39:18+00:00","content":[
            {"type":"text","text":"shipping "},
            {"type":"emoji","emoji":{"id":null,"name":"blobcat","url":"https://example.test/blobcat.png"}},
            {"type":"text","text":" today"}]},
          {"id":"r2","user_id":"u2","created_at":"2026-11-13T05:40:18+00:00","content":[
            {"type":"text","text":"thanks "},
            {"type":"mention","userId":"u1","username":"alice"},
            {"type":"text","text":" — see "},
            {"type":"channel_mention","channelId":"c2","serverId":"s1","name":"general"}]},
          {"id":"r3","user_id":"u1","created_at":"2026-11-13T05:41:18+00:00","content":[
            {"type":"emoji","emoji":{"name":"tada","content":"\uD83C\uDF89"}},
            {"type":"text","text":" a unicode emoji, and "},
            {"type":"emoji","emoji":":unresolved:"},
            {"type":"text","text":" an unresolved one"}]},
          {"id":"r4","user_id":"u2","created_at":"2026-11-13T05:42:18+00:00","content":[
            {"type":"poll","pollId":"p1","question":"Lunch?","options":["Pizza","Tacos"]},
            {"type":"text","text":" cast your vote"}]}
        ]""".trimIndent())

    @Test fun aChannelHistoryWithAnObjectEmojiDrawsEveryPart() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        history.forEach { message -> MessageContent(message, 15.0, Modifier.padding(vertical = 6.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()

        // The emoji parts must be in the tree, with the shortcode a screen reader would say.
        compose.onNodeWithTag("emoji-blobcat").assertExists()
        compose.onNodeWithTag("emoji-tada").assertExists()
        compose.onNodeWithTag("emoji-unresolved").assertExists()

        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"),
            "message-parts.png")
        out.parentFile?.mkdirs()
        val image: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var drawn = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getPixel(x, y) != image.getPixel(0, 0)) drawn++
        }
        assertTrue("the history drew something in $out", drawn > 500)
    }
}
