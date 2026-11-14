package lol.mony.harmony.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.ui.theme.LocalSizes

/**
 * A message's body: every part the instance can store, drawn the way the web client's
 * `UnifiedMessageContent.vue` draws it, rather than only the text.
 *
 * An emoji part is an image, not a shortcode -- `EmojiContent.emoji` carries the `emojis` row's
 * `url`, and that is what a person sees. When the row has no image (a native unicode emoji, or a
 * bridged emoji whose file was purged) the shortcode shows instead, so nothing disappears.
 * Mentions, channel references and hashtags read as they do on the web. Anything this build does
 * not draw yet says so on its own line; it never throws.
 */
@Composable
fun MessageContent(
    message: Message,
    fontSize: Double,
    modifier: Modifier = Modifier,
    encryptedNotice: String? = null,
    /** The instance an attachment's relative URL resolves against. */
    baseUrl: String? = null,
) {
    val sizes = LocalSizes.current
    val style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val color = if (message.isSystem) muted else MaterialTheme.colorScheme.onSurface

    if (encryptedNotice != null) {
        SelectionContainer { Text(encryptedNotice, style = style, color = muted, modifier = modifier) }
        return
    }
    if (message.content.isEmpty()) {
        SelectionContainer { Text("This message has no content.", style = style, color = muted, modifier = modifier) }
        return
    }

    // Text and the inline parts between it share one flow, so `shipping :blobcat: today` is
    // one sentence with the emoji in it, as the web client draws it. Only a part that stands on
    // its own -- an attachment, a sticker, a poll, a system event -- breaks the line.
    Column(modifier = modifier) {
        for (run in runs(message.content)) {
            if (run.block) {
                BlockPart(run.parts.first(), style, muted, baseUrl)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    for (part in run.parts) InlinePart(message.id, part, style, color, sizes.messageSp, muted)
                }
            }
        }
    }
}

private class Run(val parts: List<ContentPart>, val block: Boolean)

/** Groups the body into flowing runs and the parts that stand alone. */
private fun runs(content: List<ContentPart>): List<Run> {
    val out = mutableListOf<Run>()
    val flowing = mutableListOf<ContentPart>()
    fun flush() { if (flowing.isNotEmpty()) { out += Run(flowing.toList(), false); flowing.clear() } }
    for (part in content) {
        if (part.isBlock) { flush(); out += Run(listOf(part), true) } else flowing += part
    }
    flush()
    return out
}

/** An emoji image, a mention or a hashtag: drawn inside the line it belongs to. */
@Composable
private fun InlinePart(messageId: String, part: ContentPart, style: androidx.compose.ui.text.TextStyle,
                       color: androidx.compose.ui.graphics.Color, messageSp: Double,
                       muted: androidx.compose.ui.graphics.Color) {
    when (part.type) {
        "text" -> SelectionContainer {
            Text(part.text.orEmpty(), style = style, color = color,
                modifier = Modifier.testTag("message-" + messageId))
        }
        "emoji" -> EmojiPart(part, messageSp)
        "mention" -> Text(buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) {
                append("@" + (part.username ?: part.displayName ?: "someone"))
            }
        }, style = style)
        "role_mention" -> Text(buildAnnotatedString {
            val tint = part.roleColor?.let(::parseHexColor) ?: MaterialTheme.colorScheme.primary
            withStyle(SpanStyle(color = tint, fontWeight = FontWeight.Medium)) {
                append("@" + (part.roleName ?: part.roleId ?: "role"))
            }
        }, style = style)
        "channel_mention" -> Text("#" + (part.name ?: "channel"), style = style,
            color = MaterialTheme.colorScheme.primary)
        "hashtag" -> Text("#" + (part.name ?: "tag"), style = style, color = MaterialTheme.colorScheme.primary)
        // A masked link reads as its label and links nowhere by itself; the text is what
        // the sender wrote, which is what the web shows too.
        "url" -> Text(part.asText, style = style, color = MaterialTheme.colorScheme.primary)
        else -> Text(part.asText, style = style, color = color)
    }
}

/**
 * One emoji: its image when the row has a file, its own glyph when it wraps a unicode codepoint,
 * and its shortcode when there is neither.
 *
 * The description is the `:name:` shortcode, which is what a screen reader says and what the web
 * puts in the image's `alt`.
 */
@Composable
fun EmojiPart(part: ContentPart, messageSp: Double, modifier: Modifier = Modifier) {
    val emoji = part.emoji
    val shortcode = emoji?.shortcode ?: ":emoji:"
    val size = (messageSp + 6.0).dp
    // The tag is on every branch so a test -- and a screenshot reader -- finds the same
    // node whichever way this particular emoji draws.
    val tag = "emoji-" + (emoji?.name ?: shortcode.trim(':'))
    val tagged = modifier.testTag(tag)
    when {
        emoji != null && emoji.hasImage -> AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(emoji.url).crossfade(true).build(),
            contentDescription = shortcode,
            contentScale = ContentScale.Fit,
            modifier = tagged.size(size),
        )
        emoji != null && !emoji.content.isNullOrBlank() -> Text(emoji.content, fontSize = (messageSp + 4.0).sp,
            modifier = tagged)
        else -> Text(shortcode, fontSize = (messageSp + 1.0).sp, modifier = tagged)
    }
}

/** A part that stands on its own line: an attachment, a sticker, a poll, a system event. */
@Composable
private fun BlockPart(part: ContentPart, style: androidx.compose.ui.text.TextStyle,
                       muted: androidx.compose.ui.graphics.Color, baseUrl: String?) {
    val caption = MaterialTheme.typography.bodySmall.copy(color = muted)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        when (part.type) {
            "file" -> FilePart(part, style, muted, baseUrl)
            "sticker" -> {
                if (!part.url.isNullOrBlank()) {
                    AsyncImage(model = part.url, contentDescription = part.description ?: part.name ?: "Sticker",
                        contentScale = ContentScale.Fit, modifier = Modifier.size(96.dp))
                } else Text(":" + (part.name ?: "sticker") + ":", style = style, color = muted)
            }
            "poll" -> Text("Poll: " + (part.question ?: ""), style = style, color = muted)
            "system" -> Text(part.asText, style = style, color = muted)
            // A link preview is a card on the web; here it names what it points at, so a
            // person can tell there is one without the client pretending to draw it.
            "embed" -> Text("Link preview: " + (part.url ?: part.provider ?: "link"),
                style = style, color = muted, modifier = Modifier.testTag("embed"))
            else -> Text("This message contains " + part.type + " content this client cannot draw yet.",
                style = caption)
        }
    }
}

/**
 * An attachment: the picture itself when it is an image, a labelled card for anything else.
 *
 * The web client draws images inline, sized to a maximum, with the alt text beneath; a video
 * or a document gets a card naming it. Announcing every attachment as a sentence -- which this
 * client used to do -- makes a channel of pictures unreadable.
 */
@Composable
private fun FilePart(part: ContentPart, style: androidx.compose.ui.text.TextStyle,
                     muted: androidx.compose.ui.graphics.Color, baseUrl: String?) {
    val url = AvatarUrl.resolve(baseUrl, part.url)
    val isImage = part.fileType == "image" || part.mimeType?.startsWith("image/") == true ||
        part.url?.let { path -> IMAGE_EXTENSIONS.any { ext -> path.substringBefore('?').endsWith(ext, ignoreCase = true) } } == true
    val alt = part.altText?.takeIf { it.isNotBlank() }

    if (url != null && isImage) {
        Column(Modifier.padding(vertical = 4.dp)) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                contentDescription = alt ?: part.fileName ?: "Image attachment",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .heightIn(max = 320.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .testTag("attachment-image"),
            )
            if (alt != null) Text(alt, style = MaterialTheme.typography.bodySmall.copy(color = muted),
                modifier = Modifier.widthIn(max = 280.dp).padding(top = 2.dp))
        }
        return
    }
    val label = part.fileName ?: alt ?: (part.fileType ?: "file")
    val kind = when {
        part.fileType == "video" || part.mimeType?.startsWith("video/") == true -> "Video"
        part.fileType == "audio" || part.mimeType?.startsWith("audio/") == true -> "Audio"
        else -> "File"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(kind + ": " + label, style = style, color = muted,
            modifier = Modifier.testTag("attachment-" + label))
        part.fileSize?.let { Text("  " + humanSize(it), style = MaterialTheme.typography.labelSmall, color = muted) }
    }
}

/** The extensions an image attachment may carry when the part does not say it is one. */
private val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp")

/** A byte count as a person reads it. */
internal fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** `#RRGGBB` or `#RGB` as a role colour; anything else leaves the caller's own colour in place. */
internal fun parseHexColor(value: String): androidx.compose.ui.graphics.Color? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 3) return null
    val full = if (hex.length == 3) hex.map { "" + it + it }.joinToString("") else hex
    val rgb = full.toLongOrNull(16) ?: return null
    return androidx.compose.ui.graphics.Color(0xFF000000 or rgb)
}
