package lol.mony.harmony

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import lol.mony.harmony.data.model.Bot
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.model.Profile
import lol.mony.harmony.data.model.author
import lol.mony.harmony.ui.screens.chat.showsAuthorHeader
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The header rule that makes a busy channel readable: one header per author per group.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w360dp-h800dp-xhdpi")
class AuthorHeaderTest {
    @get:Rule val compose = createComposeRule()

    private fun message(id: String, at: String, user: String? = null, bot: String? = null,
                        reply: String? = null): Message = Message(
        id = id, createdAt = at, userId = user, botId = bot, replyTo = reply,
        content = listOf(lol.mony.harmony.data.model.ContentPart.text("message " + id)),
    )

    @Test fun theFirstMessageAlwaysShowsAHeader() {
        assertTrue(showsAuthorHeader(message("m1", "2026-01-01T00:00:00Z", user = "p1"), null))
    }

    @Test fun oneAuthorWithinFiveMinutesGroups() {
        val a = message("m1", "2026-01-01T00:00:00Z", user = "p1")
        val b = message("m2", "2026-01-01T00:04:00Z", user = "p1")
        assertFalse(showsAuthorHeader(b, a))
    }

    @Test fun aSixMinuteGapShowsTheHeaderAgain() {
        val a = message("m1", "2026-01-01T00:00:00Z", user = "p1")
        val b = message("m2", "2026-01-01T00:06:00Z", user = "p1")
        assertTrue(showsAuthorHeader(b, a))
    }

    @Test fun aDifferentAuthorShowsTheHeader() {
        val a = message("m1", "2026-01-01T00:00:00Z", user = "p1")
        val b = message("m2", "2026-01-01T00:01:00Z", user = "p2")
        assertTrue(showsAuthorHeader(b, a))
    }

    @Test fun aReplyShowsTheHeaderEvenFromTheSameAuthor() {
        val a = message("m1", "2026-01-01T00:00:00Z", user = "p1")
        val b = message("m2", "2026-01-01T00:01:00Z", user = "p1", reply = "m0")
        assertTrue(showsAuthorHeader(b, a))
    }

    @Test fun aSystemEventBreaksTheGroup() {
        val a = message("m1", "2026-01-01T00:00:00Z", user = "p1")
        val system = message("m2", "2026-01-01T00:01:00Z").copy(isSystem = true)
        assertTrue(showsAuthorHeader(system, a))
    }

    @Test fun theAuthorOfAGroupedRowIsStillTheSamePerson() {
        val people = mapOf("p1" to Profile(id = "p1", username = "noah", displayName = "Noah"))
        val a = message("m1", "2026-01-01T00:00:00Z", user = "p1")
        val b = message("m2", "2026-01-01T00:04:00Z", user = "p1")
        assertEquals(a.author(people).name, b.author(people).name)
        assertFalse(showsAuthorHeader(b, a))
    }

    @Test fun aChannelOfMixedAuthorsDrawsAndIsCaptured() {
        val people = mapOf(
            "p1" to Profile(id = "p1", username = "noah", displayName = "Noah"),
            "p2" to Profile(id = "p2", username = "rae", displayName = "Rae"),
        )
        val bots = mapOf("b1" to Bot(id = "b1", username = "weather", displayName = "Weather"))
        val rows = listOf(
            message("m1", "2026-01-01T00:00:00Z", user = "p1"),
            message("m2", "2026-01-01T00:01:00Z", user = "p1"),
            message("m3", "2026-01-01T00:02:00Z", user = "p2"),
            message("m4", "2026-01-01T00:03:00Z", bot = "b1"),
        )
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    Column(Modifier.fillMaxWidth()) {
                        for ((index, row) in rows.withIndex()) {
                            val author = row.author(people, bots)
                            HeaderPreview(
                                name = author.name,
                                showHeader = showsAuthorHeader(row, rows.getOrNull(index - 1)),
                                isBot = author.isBot,
                            )
                        }
                    }
                }
            }
        }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"), "author-header-grouping.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the channel drew in $out", image.width > 0 && image.height > 0)
    }
}

/**
 * One row of the preview: the avatar and name a grouped or ungrouped row draws.
 *
 * A private copy rather than the real `MessageRow`, which needs a session, a scope and a
 * repository; what this captures is the header rule's effect on layout and weight.
 */
@androidx.compose.runtime.Composable
private fun HeaderPreview(name: String, showHeader: Boolean, isBot: Boolean) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top,
    ) {
        if (showHeader) {
            lol.mony.harmony.ui.components.Avatar(name, null, size = 40.dp)
        } else {
            androidx.compose.foundation.layout.Spacer(Modifier.size(40.dp))
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
        Column {
            if (showHeader) androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.Text(name, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 16.sp)
                if (isBot) androidx.compose.material3.Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
                ) {
                    androidx.compose.material3.Text("BOT", Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary)
                }
            }
            androidx.compose.material3.Text("a message", fontSize = 16.sp)
        }
    }
}
