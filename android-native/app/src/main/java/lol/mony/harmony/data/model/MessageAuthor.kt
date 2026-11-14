package lol.mony.harmony.data.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Who a message row is shown as, and where its picture comes from.
 *
 * One message can name its author in five different ways, and the web client resolves them in
 * a fixed order (`useMessageAuthorPresentation`, `utils/messageAuthor.ts`). Reading only
 * `user_id` is what left every other kind of author nameless and faceless on the phone:
 *
 *  1. `metadata.discord_user` - a Discord person a bridge relays (puppeting).
 *  2. `metadata.webhook` - a channel webhook's own name and picture.
 *  3. `metadata.remote_author` - a bot, webhook or bridged person in a federated copy.
 *  4. `bot_id` - a local `bots` row.
 *  5. `user_id` - a `profiles` row, possibly with a per-server nickname and avatar.
 *
 * A message with none of them is from an account that has been deleted; it says so rather than
 * pretending to be an unknown person.
 */
data class MessageAuthor(
    /** What the row shows in place of a name. */
    val name: String,
    /** The account behind the message, when one exists, for a profile tap. */
    val profileId: String? = null,
    /** The bot behind the message, when a bot wrote it. */
    val botId: String? = null,
    /** The avatar exactly as the row stores it: absolute, instance-relative, or a storage path. */
    val avatarPath: String? = null,
    /** True for a bot, webhook or bridged author, which the row marks. */
    val isBot: Boolean = false,
    /** True for a bridged Discord person, which the row marks with the bridge it came through. */
    val isBridged: Boolean = false,
    /** True when the account no longer exists, so the row can say so plainly. */
    val isDeleted: Boolean = false,
    /** The name a per-server nickname overrides, when the server gave one. */
    val accountName: String? = null,
) {
    /** The name a profile popover would show for the account behind this message. */
    val handleName: String get() = accountName ?: name
}

/** How a message names its author, before any lookup happens. */
enum class AuthorKind { Discord, Webhook, Remote, Bot, Profile, Deleted }

/**
 * The author a message row starts from: everything the message itself carries, with no network
 * lookup. [people] and [bots] then fill in a name and picture that the row did not carry.
 */
fun Message.authorBeforeLookup(): MessageAuthor {
    val meta = metadata as? JsonObject
    val discord = meta?.obj("discord_user")
    if (discord != null) {
        val name = discord.str("display_name") ?: discord.str("username") ?: "Discord user"
        return MessageAuthor(name, avatarPath = discord.str("avatar_url"), isBridged = true)
    }
    val webhook = meta?.obj("webhook")
    if (webhook != null) {
        val name = webhook.str("name") ?: "Webhook"
        return MessageAuthor(name, avatarPath = webhook.str("avatar_url"), isBot = true)
    }
    val remote = meta?.obj("remote_author")
    if (remote != null && userId == null && botId == null) {
        val name = remote.str("name") ?: "Remote author"
        return MessageAuthor(name, avatarPath = remote.str("avatar_url"), isBot = true)
    }
    if (botId != null) return MessageAuthor(name = "", botId = botId, isBot = true)
    if (userId != null) return MessageAuthor(name = "", profileId = userId)
    return MessageAuthor(name = "Deleted account", isDeleted = true)
}

/** The author as a row draws it once [people] and [bots] have been consulted. */
fun Message.author(people: Map<String, Profile>, bots: Map<String, Bot> = emptyMap()): MessageAuthor {
    val start = authorBeforeLookup()
    if (start.isDeleted) return start

    start.botId?.let { id ->
        val bot = bots[id]
        return start.copy(
            name = bot?.shownName ?: "Bot",
            avatarPath = bot?.avatarUrl,
        )
    }
    // A bridged, webhook or remote author carries its own name; only its picture is looked up.
    if (start.isBridged || start.isBot) return start

    val profile = start.profileId?.let { people[it] }
    if (profile != null) {
        return start.copy(name = profile.shownName, avatarPath = profile.avatarUrl, accountName = profile.shownName)
    }
    // The profile was asked for and is not there: the account is gone, not merely unnamed.
    return start.copy(name = "Deleted account", isDeleted = true)
}

/**
 * The profile an author popover opens, resolving an `auth_user_id` to its profile row.
 *
 * `messages.user_id` references `profiles.id`, but membership rows and the JWT speak
 * `auth_user_id`, and the two differ for an account created before the profile existed. Both
 * are indexed in [people] so either spelling finds the same person.
 */
fun authorProfile(author: MessageAuthor, people: Map<String, Profile>): Profile? {
    val id = author.profileId ?: return null
    return people[id] ?: people.values.firstOrNull { it.authUserId == id }
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
        ?.takeIf { it.isNotBlank() }

/** True when a message came from something other than a person's own account. */
val Message.isBotAuthored: Boolean
    get() = botId != null || (metadata as? JsonObject)?.obj("remote_author") != null

/** True when a bridge relayed the message from another chat network. */
val Message.isBridgedAuthored: Boolean
    get() = (metadata as? JsonObject)?.let { it.obj("discord_user") != null ||
        (it.str("bridge_source")?.takeIf { source -> source != "harmony" } != null) } == true

/** The key consecutive rows share when one author's messages group under a single header. */
fun Message.authorKey(): String {
    val meta = metadata as? JsonObject
    meta?.obj("discord_user")?.let { return "discord:" + (it.str("id") ?: it.str("username") ?: "") }
    meta?.obj("webhook")?.let { return "webhook:" + (it.str("id") ?: "") + "\n" + (it.str("name") ?: "") }
    meta?.obj("remote_author")?.let {
        if (userId == null && botId == null) return "remote:" + (it.str("id") ?: "") + "\n" + (it.str("name") ?: "")
    }
    if (botId != null) return "bot:" + botId
    return "user:" + (userId ?: "")
}
