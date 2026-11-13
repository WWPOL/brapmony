package lol.mony.harmony.ui.screens.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.model.NotificationPostPreview
import lol.mony.harmony.data.model.NotificationTarget
import lol.mony.harmony.data.model.Profile
import lol.mony.harmony.data.model.notificationContentText
import lol.mony.harmony.data.model.notificationText
import lol.mony.harmony.data.repo.NotificationRepository

/** Actual target rows, not notification payload snapshots. Removed/private targets stay errors. */
@Composable
internal fun NotificationTargetPreview(
    repository: NotificationRepository,
    endpoint: Endpoint,
    token: suspend () -> String,
    target: NotificationTarget,
    onDismiss: () -> Unit,
) {
    var attempt by remember(target) { mutableStateOf(0) }
    var loading by remember(target) { mutableStateOf(true) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    var post by remember(target) { mutableStateOf<NotificationPostPreview?>(null) }
    var profile by remember(target) { mutableStateOf<Profile?>(null) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(target, attempt) {
        loading = true
        error = null
        try {
            when (target) {
                is NotificationTarget.Post -> post = repository.fetchPostPreview(endpoint, token(), target.id)
                is NotificationTarget.Profile -> profile = repository.fetchProfilePreview(endpoint, token(), target.id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Couldn't load this target. Check your connection and retry."
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target is NotificationTarget.Post) "Post" else "Profile") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("Loading ${if (target is NotificationTarget.Post) "post" else "profile"}")
                    }
                    error != null -> NotificationError(error!!, enabled = true, onRetry = { attempt++ })
                    else -> {
                        profile?.let { person ->
                            NotificationPerson(person, endpoint.baseUrl)
                            person.pronouns?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            person.bio?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                        post?.let { preview ->
                            NotificationPerson(preview.author, endpoint.baseUrl)
                            Text(notificationTime(preview.post.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider()
                            var reveal by remember(preview.post.id) { mutableStateOf(false) }
                            val warning = preview.post.contentWarning?.takeIf { it.isNotBlank() }
                            if (warning != null) {
                                Text("Content warning: $warning", fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide content" else "Show content") }
                            }
                            if (warning == null || reveal) {
                                notificationContentText(preview.post.content)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                                val media = (preview.post.mediaAttachments as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                                media.forEach { attachment ->
                                    val url = notificationAvatarUrl(endpoint.baseUrl, attachment.notificationText("url", "remote_url"))
                                    val image = notificationAvatarUrl(endpoint.baseUrl, attachment.notificationText("preview_url"))
                                        ?: url?.takeIf { attachment.notificationText("type", "mime_type")?.startsWith("image") == true }
                                    if (image != null) AsyncImage(
                                        model = image,
                                        contentDescription = attachment.notificationText("description", "name") ?: "Post attachment",
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                                    )
                                    if (url != null) TextButton(onClick = { uriHandler.openUri(url) }) { Text("Open attachment") }
                                }
                            }
                            notificationAvatarUrl(endpoint.baseUrl, preview.post.url)?.let { original ->
                                TextButton(onClick = { uriHandler.openUri(original) }) { Text("Open original post") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun NotificationPerson(profile: Profile, baseUrl: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AvatarForNotificationPerson(profile, baseUrl)
        Column {
            Text(profile.shownName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(profile.handle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AvatarForNotificationPerson(profile: Profile, baseUrl: String) {
    lol.mony.harmony.ui.components.Avatar(profile.shownName, notificationAvatarUrl(baseUrl, profile.avatarUrl), size = 48.dp)
}
