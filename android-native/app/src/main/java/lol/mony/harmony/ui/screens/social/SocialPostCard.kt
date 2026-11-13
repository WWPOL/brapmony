package lol.mony.harmony.ui.screens.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lol.mony.harmony.data.model.SocialPost
import lol.mony.harmony.data.model.socialContentParts
import lol.mony.harmony.data.model.socialContentText
import lol.mony.harmony.ui.components.Avatar

/** The feed and detail share exactly the same post actions and content-warning behavior. */
@Composable
internal fun SocialPostCard(
    row: SocialPost,
    profileId: String,
    baseUrl: String,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onProfile: (String) -> Unit,
    onHashtag: (String) -> Unit,
    onReply: (SocialPost) -> Unit,
    onEdit: (SocialPost) -> Unit,
    onDelete: (SocialPost) -> Unit,
    onFavorite: (SocialPost) -> Unit,
    onBoost: (SocialPost) -> Unit,
    onBookmark: (SocialPost) -> Unit,
) {
    val post = row.actionPost
    val author = post.author
    var menu by remember { mutableStateOf(false) }
    var revealed by remember(row.id, post.contentWarning, post.isSensitive) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (row.isBoost) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Repeat, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text("${row.author?.shownName ?: "An account"} boosted", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onProfile(row.authorId) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(author?.shownName ?: "Account", socialMediaUrl(baseUrl, author?.avatarUrl), size = 42.dp,
                modifier = Modifier.clickable { onProfile(author?.id ?: post.authorId) })
            Column(Modifier.weight(1f).clickable { onProfile(author?.id ?: post.authorId) }) {
                Text(author?.shownName ?: "Account unavailable", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(author?.handle ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Outlined.MoreHoriz, contentDescription = "Post options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open post") }, onClick = { menu = false; onOpen(post.id) })
                    if (post.authorId == profileId && !row.isBoost) {
                        DropdownMenuItem(text = { Text("Edit post") }, onClick = { menu = false; onEdit(post) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                        DropdownMenuItem(text = { Text("Delete post", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete(post) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) })
                    }
                }
            }
        }
        if (post.inReplyTo != null) {
            TextButton(onClick = { onOpen(post.inReplyTo) }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.AutoMirrored.Outlined.Reply, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp)); Text("In reply to a post", style = MaterialTheme.typography.labelMedium)
            }
        }
        val hidden = (!post.contentWarning.isNullOrBlank() || post.isSensitive) && !revealed
        if (!post.contentWarning.isNullOrBlank() || post.isSensitive) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text("Content warning", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(post.contentWarning?.takeIf(String::isNotBlank) ?: "Sensitive content", style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { revealed = !revealed }) { Text(if (hidden) "Show" else "Hide") }
                }
            }
        }
        if (!hidden) {
            SocialRichText(post, onProfile, onHashtag)
            for (media in post.mediaAttachments) {
                val uri = socialMediaUrl(baseUrl, media.url)
                val uriHandler = LocalUriHandler.current
                if (media.type == "image" || media.type == "gifv" || media.previewUrl != null) {
                    AsyncImage(model = socialMediaUrl(baseUrl, media.previewUrl) ?: uri, contentDescription = media.description ?: media.filename ?: "Post attachment",
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp).clickable { if (uri != null) uriHandler.openUri(uri) }, contentScale = ContentScale.Fit)
                }
                if (media.type != "image" || !media.description.isNullOrBlank()) {
                    TextButton(onClick = { if (uri != null) uriHandler.openUri(uri) }) {
                        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text(media.description ?: media.filename ?: "Open ${media.type}")
                    }
                }
            }
            if (row.isQuote && row.reblog != null) {
                OutlinedCard(onClick = { onOpen(row.reblog.id) }, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.reblogAuthor?.shownName ?: row.reblog.author?.shownName ?: "Quoted post", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                        if (!row.reblog.contentWarning.isNullOrBlank()) Text("Content warning: ${row.reblog.contentWarning}", style = MaterialTheme.typography.bodySmall)
                        else Text(row.reblog.plainText, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().clickable { onOpen(post.id) }, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(socialTime(post.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(post.visibility.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onReply(post) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Icon(Icons.AutoMirrored.Outlined.Reply, "Reply", Modifier.size(20.dp)); Spacer(Modifier.width(5.dp)); Text(post.repliesCount.toString())
            }
            TextButton(onClick = { onBoost(post) }, enabled = !busy && post.visibility in setOf("public", "unlisted"), contentPadding = PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = if (post.isReblogged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                Icon(Icons.Outlined.Repeat, if (post.isReblogged) "Undo boost" else "Boost", Modifier.size(20.dp)); Spacer(Modifier.width(5.dp)); Text(post.reblogsCount.toString())
            }
            TextButton(onClick = { onFavorite(post) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = if (post.isFavorited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                Icon(if (post.isFavorited) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, if (post.isFavorited) "Remove favorite" else "Favorite", Modifier.size(20.dp)); Spacer(Modifier.width(5.dp)); Text(post.favoritesCount.toString())
            }
            IconToggleButton(checked = post.isBookmarked, onCheckedChange = { onBookmark(post) }, enabled = !busy) {
                Icon(if (post.isBookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, if (post.isBookmarked) "Remove bookmark" else "Bookmark")
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Suppress("DEPRECATION")
@Composable
private fun SocialRichText(post: SocialPost, onProfile: (String) -> Unit, onHashtag: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(post.content, linkColor) {
        buildAnnotatedString {
            for (part in socialContentParts(post.content)) {
                val start = length
                val label = socialContentText(kotlinx.serialization.json.JsonArray(listOf(part)))
                append(label)
                val type = (part["type"] as? JsonPrimitive)?.contentOrNull
                val value = when (type) {
                    "hashtag" -> (part["name"] as? JsonPrimitive)?.contentOrNull
                    "mention" -> (part["userId"] as? JsonPrimitive)?.contentOrNull
                    "url", "link" -> (part["url"] as? JsonPrimitive)?.contentOrNull
                    else -> null
                }
                if (value != null) {
                    addStringAnnotation(type.orEmpty(), value, start, length)
                    addStyle(SpanStyle(color = linkColor), start, length)
                }
            }
        }
    }
    if (text.isNotEmpty()) SelectionContainer {
        ClickableText(text = text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), onClick = { offset ->
            text.getStringAnnotations(offset, offset).firstOrNull()?.let {
                when (it.tag) {
                    "hashtag" -> onHashtag(it.item)
                    "mention" -> if (runCatching { java.util.UUID.fromString(it.item) }.isSuccess) onProfile(it.item)
                    "url", "link" -> if (it.item.startsWith("https://") || it.item.startsWith("http://")) uriHandler.openUri(it.item)
                }
            }
        })
    }
}

internal fun socialMediaUrl(baseUrl: String, value: String?): String? = value?.takeIf(String::isNotBlank)?.let {
    when {
        it.startsWith("https://") || it.startsWith("http://") -> it
        it.startsWith("/") && !it.startsWith("//") -> baseUrl.trimEnd('/') + it
        else -> null
    }
}

internal fun socialTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
