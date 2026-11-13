package lol.mony.harmony.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.data.model.ServerWithMembership
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.theme.LocalHarmonyColors

/** Where the server rail is pointing: a server, or the home (DM) column. */
data class RailTarget(val serverId: String?)

/**
 * The server rail, as Discord-style Android clients have it: the home entry, one tile per
 * server with its unread dot, and a way to add one. Selecting a tile switches the middle
 * column, the way a tap on the web client's rail does.
 */
@Composable
fun ServerRail(
    state: AppState,
    modifier: Modifier = Modifier,
) {
    val signedIn = state.auth.state.collectAsState().value as? AuthState.SignedIn
    val harmony = LocalHarmonyColors.current
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<ServerWithMembership>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val selected = state.selectedServerId.collectAsState().value
    val unread by state.unread.collectAsState()
    val channelsByServer by state.channelsByServer.collectAsState()

    LaunchedEffect(signedIn?.user?.id, state.serversRevision.collectAsState().value) {
        if (signedIn == null) {
            servers = emptyList()
            loading = false
            return@LaunchedEffect
        }
        val token = state.auth.freshToken() ?: return@LaunchedEffect
        val authUserId = signedIn.profile?.id ?: return@LaunchedEffect
        loading = true
        servers = state.servers.servers(signedIn.endpoint, token, authUserId).getOrDefault(emptyList())
        loading = false
        // The first server opens on arrival, as the web client's rail does.
        if (state.selectedServerId.value == null && servers.isNotEmpty()) {
            state.selectedServerId.value = servers.first().server.id
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(76.dp)
            .background(harmony.surfaceSidebar),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RailTile(
                selected = selected == null,
                label = "Home",
                badge = unread.conversations.values.sum(),
                testTag = "rail-home",
                onClick = {
                    state.selectedServerId.value = null
                    state.showingDms.value = true
                },
            ) {
                Icon(Icons.Outlined.Home, contentDescription = "Home and direct messages")
            }
            Box(
                Modifier
                    .fillMaxWidth(0.6f)
                    .height(1.dp)
                    .background(LocalHarmonyColors.current.border),
            )
            servers.forEach { entry ->
                val channelIds = channelsByServer[entry.server.id].orEmpty().map { it.id }
                RailTile(
                    selected = selected == entry.server.id,
                    label = entry.server.name,
                    badge = unread.serverTotal(channelIds),
                    mentions = unread.mentionsIn(channelIds),
                    testTag = "rail-server-${entry.server.name}",
                    onClick = {
                        state.selectedServerId.value = entry.server.id
                        state.showingDms.value = false
                    },
                ) {
                    ServerAvatar(
                        name = entry.server.name,
                        url = entry.server.icon,
                        selected = selected == entry.server.id,
                        size = 48.dp,
                    )
                }
            }
            if (servers.isNotEmpty() || !loading) {
                Spacer(Modifier.height(2.dp))
                IconButton(
                    onClick = { state.showCreateServer.value = true },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(harmony.surfaceRaised, CircleShape)
                        .testTag("rail-add-server"),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Create a server",
                        tint = harmony.textSuccess.copy(alpha = 0.95f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RailTile(
    selected: Boolean,
    label: String,
    badge: Int,
    testTag: String,
    mentions: Int = 0,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val harmony = LocalHarmonyColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                    .then(
                        if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                        else Modifier,
                    )
                    .testTag(testTag)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
            if (badge > 0) {
                Badge(
                    containerColor = if (mentions > 0) harmony.badge else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp, bottom = 8.dp),
                ) {
                    Text(if (badge > 99) "99+" else "$badge", fontSize = 9.sp)
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(64.dp)
                .padding(top = 2.dp),
        )
    }
}

