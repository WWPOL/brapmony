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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.Server
import lol.mony.harmony.ui.components.AvatarUrl
import lol.mony.harmony.ui.components.ServerBranding
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
 * A server's cover and icon are pictures, from the buckets the reference actually uses.
 *
 * The reported bug was a header with the server's name and no branding at all. Two causes are
 * pinned here, because either one alone leaves the header bare:
 *
 * - **The bucket names.** `servers.icon` and `servers.banner` hold bare object paths
 *   (`<server-id>/banner-<ms>.webp`), and this client joined them to `server-icons` and
 *   `server-banners`. The buckets are `server_icons` and `server_banners`
 *   (`src/utils/serverUtils.ts`), so every one of those requests was a 404.
 * - **The picture really drawing.** A test that only checks a URL string passes while the
 *   image branch draws nothing, so this fetches over HTTP from a local server and asserts the
 *   marker colour is on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class ServerBrandingRenderTest {
    @get:Rule val compose = createComposeRule()

    /** A solid magenta block, so a drawn pixel cannot be confused with a theme colour. */
    private fun markerPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(255, 0, 170))
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /**
     * Serves one PNG at whatever path is asked for, recording the paths it was asked for.
     *
     * The bucket is part of the path, so the recorded requests are how this test tells a
     * `server_banners` fetch from a `server-banners` one -- the actual difference between the
     * bug and the fix.
     */
    private class ImageServer(private val body: ByteArray) : AutoCloseable {
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val socket = java.net.ServerSocket(0)
        val port: Int get() = socket.localPort
        private val thread = Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (closed: Exception) { break }
                client.use {
                    val reader = it.getInputStream().bufferedReader()
                    val request = reader.readLine() ?: continue
                    requested += request.split(' ').getOrElse(1) { "" }
                    // Drain the headers so the client is not left waiting on us.
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                    it.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: " +
                            body.size + "\r\nConnection: close\r\n\r\n").toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close() }
    }

    private fun hasMarker(bitmap: Bitmap): Boolean {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.red(pixel) > 220 && Color.green(pixel) < 60 && Color.blue(pixel) > 140) return true
        }
        return false
    }

    // ---- resolution: the buckets the reference uses ---------------------------------------

    @Test fun aServerBannerResolvesIntoTheServerBannersBucket() {
        assertEquals(
            "https://example.test/storage/v1/object/public/server_banners/srv-1/banner-1.webp",
            AvatarUrl.resolveServerBanner("https://example.test", "srv-1/banner-1.webp"),
        )
    }

    @Test fun aServerIconResolvesIntoTheServerIconsBucket() {
        assertEquals(
            "https://example.test/storage/v1/object/public/server_icons/srv-1/icon-1.webp",
            AvatarUrl.resolveServerIcon("https://example.test", "srv-1/icon-1.webp"),
        )
    }

    /** The hyphenated names this client used are not buckets, and must not come back. */
    @Test fun theHyphenatedBucketNamesAreNotUsed() {
        val banner = AvatarUrl.resolveServerBanner("https://example.test", "srv-1/banner-1.webp")
        val icon = AvatarUrl.resolveServerIcon("https://example.test", "srv-1/icon-1.webp")
        assertTrue("banner URL was $banner", banner!!.contains("/server_banners/"))
        assertTrue("icon URL was $icon", icon!!.contains("/server_icons/"))
        assertTrue("banner URL was $banner", !banner.contains("server-banners"))
        assertTrue("icon URL was $icon", !icon.contains("server-icons"))
    }

    @Test fun anAbsoluteBrandingUrlIsLeftAlone() {
        assertEquals("https://cdn.test/b.png",
            AvatarUrl.resolveServerBanner("https://example.test", "https://cdn.test/b.png"))
        assertEquals("https://cdn.test/i.png",
            AvatarUrl.resolveServerIcon("https://example.test", "https://cdn.test/i.png"))
    }

    /** The schema's bundled placeholder is not a picture of this server. */
    @Test fun theBundledServerPlaceholderIsNotFetched() {
        assertNull(AvatarUrl.resolveServerIcon("https://example.test", "/default_server.webp"))
        assertNull(AvatarUrl.resolveServerBanner("https://example.test", "default_server.webp"))
    }

    // ---- rendering: the picture actually paints -------------------------------------------

    /**
     * The header draws the cover and the icon, and asks for both in the right bucket.
     *
     * This is the end-to-end shape of the report: a server whose columns hold bare object
     * paths, resolved against the instance, fetched over HTTP, and painted.
     */
    @Test fun theHeaderDrawsTheCoverAndTheIcon() {
        val png = markerPng()
        ImageServer(png).use { server ->
            val base = "http://127.0.0.1:" + server.port
            val branded = Server(id = "srv-1", name = "E2E Server", icon = "srv-1/icon-1.png",
                banner = "srv-1/banner-1.png")
            compose.setContent {
                HarmonyTheme(VisualThemeSettings()) {
                    Surface {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            ServerBranding(branded, baseUrl = base)
                        }
                    }
                }
            }
            var image: Bitmap? = null
            compose.waitUntil(60_000) {
                image = compose.onRoot().captureToImage().asAndroidBitmap()
                hasMarker(image!!)
            }
            // Unmerged: the icon and cover carry their own tags inside the header's row.
            compose.onNodeWithTag("server-banner", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("server-icon-image", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("server-icon-monogram", useUnmergedTree = true).assertDoesNotExist()

            val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"),
                "server-branding.png")
            out.parentFile?.mkdirs()
            out.outputStream().use { image!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("the branding drew in $out", hasMarker(image!!))
            // The requests name the buckets the reference uses, not the hyphenated ones.
            val paths = server.requested.toList()
            assertTrue("asked for $paths", paths.any { it.startsWith("/storage/v1/object/public/server_banners/") })
            assertTrue("asked for $paths", paths.any { it.startsWith("/storage/v1/object/public/server_icons/") })
            assertTrue("asked for $paths", paths.none { it.contains("server-banners") || it.contains("server-icons") })
        }
    }

    /**
     * A server with no branding draws its name and monogram, and no empty strip.
     *
     * The header must not reserve a cover's height for a cover that does not exist, which is
     * what left a band of nothing above the channel list.
     */
    @Test fun aServerWithoutBrandingDrawsItsMonogram() {
        val plain = Server(id = "srv-2", name = "Plain Server")
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface { Column(Modifier.fillMaxWidth().padding(12.dp)) { ServerBranding(plain, baseUrl = null) } }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("server-banner-absent", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("server-icon-monogram", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("server-icon-image", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodesWithTag("server-banner", useUnmergedTree = true).fetchSemanticsNodes().let {
            assertEquals("no cover strip for a server without a cover", 0, it.size)
        }
    }
}
