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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.ui.components.Avatar
import lol.mony.harmony.ui.components.MessageContent
import lol.mony.harmony.ui.components.humanSize
import lol.mony.harmony.ui.components.AvatarUrl
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A person's avatar draws as a picture, whatever shape their `avatar_url` is stored in.
 *
 * The reported bug was two-layered: no profile resolved at all (see `AuthorResolutionTest`),
 * and the URL that survived was handed to the image loader raw. `profiles.avatar_url` holds an
 * absolute URL, a site-relative path or a bare storage path depending on who uploaded it, so a
 * raw pass-through silently drew initials for most people. `AvatarUrl` resolves each form
 * against the instance; this proves the resolved form actually paints a pixel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class AvatarRenderTest {
    @get:Rule val compose = createComposeRule()

    /** A solid block of one colour, so a drawn pixel is unmistakable. */
    private fun markerPng(): File {
        val file = File.createTempFile("avatar", ".png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(0, 200, 255))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /** True when a pixel of the marker colour is in the bitmap. */
    private fun hasMarker(bitmap: Bitmap): Boolean {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.red(pixel) < 60 && Color.green(pixel) > 160 && Color.blue(pixel) > 210) return true
        }
        return false
    }

    @Test fun anAbsoluteAvatarUrlPaintsThePicture() {
        val png = markerPng()
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Avatar("Noah", png.toURI().toString(), size = 40.dp, baseUrl = "https://example.test")
                } }
            }
        }
        var image: Bitmap? = null
        compose.waitUntil(60_000) {
            image = compose.onRoot().captureToImage().asAndroidBitmap()
            hasMarker(image!!)
        }
        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"), "avatar-absolute.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the avatar's own picture drew in $out", hasMarker(image!!))
    }

    // ---- resolution of the forms the database actually holds -------------------------------

    @Test fun anAbsoluteUrlIsUsedAsItStands() {
        assertEquals("https://cdn.test/a.png",
            AvatarUrl.resolve("https://example.test", "https://cdn.test/a.png"))
    }

    @Test fun aSiteRelativePathResolvesAgainstTheInstance() {
        // `/default_avatar.webp` is the column's own DEFAULT; a custom upload looks the same.
        assertEquals("https://example.test/uploads/avatars/p1.webp",
            AvatarUrl.resolve("https://example.test", "/uploads/avatars/p1.webp"))
    }

    @Test fun aBareStoragePathBecomesAStorageUrl() {
        assertEquals("https://example.test/storage/v1/object/public/avatars/p1/a.png",
            AvatarUrl.resolve("https://example.test", "p1/a.png"))
    }

    @Test fun theBundledDefaultIsNotFetched() {
        // Asking the server for the app's own default costs a request and can 404; the app
        // draws its own initials instead.
        assertNull(AvatarUrl.resolve("https://example.test", "/default_avatar.webp"))
        assertNull(AvatarUrl.resolve("https://example.test", "/default_avatar.png"))
        assertNull(AvatarUrl.resolve("https://example.test", null))
        assertNull(AvatarUrl.resolve("https://example.test", "   "))
    }

    @Test fun anUnknownInstanceStillLeavesAbsoluteUrlsAlone() {
        assertEquals("https://cdn.test/a.png", AvatarUrl.resolve(null, "https://cdn.test/a.png"))
        assertNull("a relative path has no instance to resolve against",
            AvatarUrl.resolve(null, "/uploads/a.png"))
    }

    @Test fun anInstanceWithAPathPrefixKeepsIt() {
        // An instance served under a sub-path must not lose it.
        assertEquals("https://example.test/harmony/uploads/a.png",
            AvatarUrl.resolve("https://example.test/harmony", "/uploads/a.png"))
    }

    // ---- attachments ----------------------------------------------------------------------

    @Test fun anImageAttachmentDrawsThePicture() {
        val png = markerPng()
        val message = Message(
            id = "attach-1",
            createdAt = "2026-11-13T05:39:18+00:00",
            content = listOf(
                ContentPart.text("look at this"),
                ContentPart(type = "file", url = png.toURI().toString(), fileType = "image",
                    altText = "a bright block"),
            ),
        )
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    MessageContent(message, 16.0, baseUrl = "https://example.test")
                } }
            }
        }
        var image: Bitmap? = null
        compose.waitUntil(60_000) {
            image = compose.onRoot().captureToImage().asAndroidBitmap()
            hasMarker(image!!)
        }
        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"), "attachment-image.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the attachment picture drew in $out", hasMarker(image!!))
    }

    @Test fun aByteCountReadsAsAPersonWouldWriteIt() {
        assertEquals("1.5 KB", humanSize(1536))
        assertEquals("2.0 MB", humanSize(2L * 1024 * 1024))
        assertEquals("512 B", humanSize(512))
    }
}
