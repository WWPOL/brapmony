package lol.mony.harmony.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import lol.mony.harmony.data.model.Server
import lol.mony.harmony.ui.theme.LocalHarmonyColors

/**
 * A server's cover and icon, as the web client's channel sidebar header draws them.
 *
 * The cover is a wide strip at the top and the icon sits on its lower edge, half over it, with
 * the name beside. A server that has neither still gets its name and its monogram, so the
 * header is never empty space -- and it does not reserve a cover's height for a cover that does
 * not exist, which is what left a band of nothing above the channel list.
 *
 * Both columns hold whatever the uploader wrote: an absolute URL, a site-relative path, or a
 * bare object path in `server_icons` / `server_banners`. Resolving them here, rather than
 * handing the raw value to the image loader, is what makes the picture appear at all.
 */
@Composable
fun ServerBranding(
    server: Server,
    baseUrl: String?,
    modifier: Modifier = Modifier,
) {
    val banner = AvatarUrl.resolveServerBanner(baseUrl, server.banner)
    val icon = AvatarUrl.resolveServerIcon(baseUrl, server.icon)
    val harmony = LocalHarmonyColors.current
    Column(modifier.fillMaxWidth().testTag("server-header")) {
        if (banner != null) {
            Box(Modifier.fillMaxWidth().height(96.dp).testTag("server-banner")) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(banner).crossfade(true).build(),
                    contentDescription = server.name + " cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // The name sits over the cover, so a light cover cannot wash it out.
                Box(Modifier.matchParentSize().background(
                    Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent,
                        MaterialTheme.colorScheme.background.copy(alpha = 0.55f)))))
            }
        } else {
            // A marker rather than an empty box, so a caller (and a test) can tell the two
            // headers apart without measuring pixels.
            Box(Modifier.fillMaxWidth().height(0.dp).testTag("server-banner-absent"))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No test tag here: `ServerAvatar` already names the branch it drew
            // (`server-icon-image` or `server-icon-monogram`), and a second tag on the same
            // node would replace it rather than add to it.
            ServerAvatar(
                name = server.name,
                url = server.icon,
                size = 40.dp,
                selected = true,
                baseUrl = baseUrl,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (server.memberCount > 0) Text(
                    if (server.memberCount == 1) "1 member" else server.memberCount.toString() + " members",
                    style = MaterialTheme.typography.labelSmall,
                    color = harmony.textSuccess.copy(alpha = 0.9f),
                )
            }
        }
    }
}
