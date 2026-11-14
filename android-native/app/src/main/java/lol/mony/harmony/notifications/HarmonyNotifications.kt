package lol.mony.harmony.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import lol.mony.harmony.MainActivity
import lol.mony.harmony.R
import lol.mony.harmony.data.model.Notification
import lol.mony.harmony.data.model.NotificationChannelIds
import lol.mony.harmony.data.model.NotificationDestination
import lol.mony.harmony.data.model.NotificationFormatter
import lol.mony.harmony.data.model.NotificationPermissionState
import lol.mony.harmony.data.model.NotificationRouter

/**
 * Android notifications for Harmony activity, with the platform's own rules honoured.
 *
 * Two things the previous build got wrong and this one fixes: the app never asked for
 * `POST_NOTIFICATIONS` (so on Android 13+ the system silently dropped everything), and it
 * registered no channels at all (so system settings had nothing to show). Both are required
 * before a notification can appear on a modern Android.
 *
 * The channels are created unconditionally at startup, because a person can open system
 * settings and tune them before any notification arrives; creating them lazily would show an
 * empty settings page until the first message.
 */
object HarmonyNotifications {
    /**
     * Registers every channel. Safe to call on every launch: the system keeps the person's own
     * importance and sound choices, and an existing channel is left exactly as they set it.
     */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        fun create(id: String, name: Int, description: Int, importance: Int) {
            if (manager.getNotificationChannel(id) != null) return
            manager.createNotificationChannel(NotificationChannel(id, context.getString(name), importance).apply {
                this.description = context.getString(description)
            })
        }
        create(NotificationChannelIds.MESSAGES, R.string.channel_messages_name,
            R.string.channel_messages_description, NotificationManager.IMPORTANCE_HIGH)
        create(NotificationChannelIds.MENTIONS, R.string.channel_mentions_name,
            R.string.channel_mentions_description, NotificationManager.IMPORTANCE_HIGH)
        create(NotificationChannelIds.SOCIAL, R.string.channel_social_name,
            R.string.channel_social_description, NotificationManager.IMPORTANCE_DEFAULT)
        create(NotificationChannelIds.CALLS, R.string.channel_calls_name,
            R.string.channel_calls_description, NotificationManager.IMPORTANCE_HIGH)
    }

    /**
     * What the system currently allows.
     *
     * `Denied` means the app must not ask again: Android stops showing the prompt after two
     * refusals, so asking again is nagging with no effect. The settings route is the way back,
     * and [openNotificationSettings] is how the app offers it.
     */
    fun permissionState(context: Context): NotificationPermissionState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Before Android 13 there is no runtime permission; the only way notifications are
            // off is the person turning them off in settings.
            val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            return if (enabled) NotificationPermissionState.Granted else NotificationPermissionState.Denied
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            val asked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ASKED, false)
            return if (asked) NotificationPermissionState.Denied else NotificationPermissionState.NotAsked
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val anyChannelOff = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            listOf(NotificationChannelIds.MESSAGES, NotificationChannelIds.MENTIONS,
                NotificationChannelIds.SOCIAL, NotificationChannelIds.CALLS)
                .any { id -> manager.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE }
        } else false
        return if (anyChannelOff) NotificationPermissionState.ChannelsDisabled else NotificationPermissionState.Granted
    }

    /** Records that the runtime prompt was shown, so a refusal is not asked about twice. */
    fun rememberAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }

    /** Opens this app's notification settings page, which is where a refusal is undone. */
    fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + context.packageName))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Shows one inbox row as a system notification, if the system allows it.
     *
     * A refused permission is not an error here: the inbox is still the source of truth, and
     * the notification is an extra. The method returns whether it was actually posted so a
     * caller -- and a test -- can tell the difference between "delivered" and "dropped".
     */
    fun post(context: Context, notification: Notification): Boolean {
        if (permissionState(context) != NotificationPermissionState.Granted) return false
        ensureChannels(context)
        val copy = NotificationFormatter.format(notification)
        val channel = NotificationChannelIds.of(notification.type)
        val destination = NotificationRouter.destination(notification)
        val open = deepLinkIntent(context, notification.id, destination)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(copy.title)
            .setContentText(copy.preview ?: copy.context ?: "New activity")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                listOfNotNull(copy.preview, copy.context).joinToString("\n").ifBlank { copy.title }))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setWhen(createdAtMillis(notification.createdAt))
            .setCategory(if (notification.type == "dm" || notification.type == "mention") {
                NotificationCompat.CATEGORY_MESSAGE
            } else NotificationCompat.CATEGORY_SOCIAL)
        copy.context?.let { builder.setSubText(it) }
        return runCatching {
            NotificationManagerCompat.from(context).notify(tagOf(notification), notification.id.hashCode(), builder.build())
            true
        }.getOrDefault(false)
    }

    /** Withdraws a notification that was read or deleted elsewhere, so the tray stays truthful. */
    fun cancel(context: Context, notification: Notification) {
        NotificationManagerCompat.from(context).cancel(tagOf(notification), notification.id.hashCode())
    }

    fun cancelAll(context: Context) { NotificationManagerCompat.from(context).cancelAll() }

    /**
     * The intent a tapped notification opens: the app's own activity carrying the destination.
     *
     * It is a deep link into this app, never an arbitrary URL. The activity resolves it against
     * the signed-in account, so a notification from another account or instance cannot open
     * something that account may not see.
     */
    fun deepLinkIntent(context: Context, notificationId: String, destination: NotificationDestination): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("brapmony://open").buildUpon()
                .appendQueryParameter("notification", notificationId)
                .apply {
                    when (destination) {
                        is NotificationDestination.Conversation -> {
                            appendQueryParameter("conversation", destination.conversationId)
                            destination.messageId?.let { appendQueryParameter("message", it) }
                        }
                        is NotificationDestination.Channel -> {
                            appendQueryParameter("channel", destination.channelId)
                            destination.serverId?.let { appendQueryParameter("server", it) }
                            destination.messageId?.let { appendQueryParameter("message", it) }
                        }
                        is NotificationDestination.Thread -> {
                            appendQueryParameter("thread", destination.threadId)
                            destination.channelId?.let { appendQueryParameter("channel", it) }
                            destination.messageId?.let { appendQueryParameter("message", it) }
                        }
                        is NotificationDestination.Post -> appendQueryParameter("post", destination.postId)
                        is NotificationDestination.Profile -> {
                            destination.profileId?.let { appendQueryParameter("profile", it) }
                        }
                        NotificationDestination.Security -> appendQueryParameter("screen", "security")
                        NotificationDestination.FollowRequests -> appendQueryParameter("screen", "follow-requests")
                        NotificationDestination.Chats, is NotificationDestination.Unavailable ->
                            appendQueryParameter("screen", "chats")
                    }
                }.build()
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, notificationId.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun tagOf(notification: Notification): String = "harmony:" + notification.type

    private fun createdAtMillis(iso: String): Long =
        runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(System.currentTimeMillis())

    private const val PREFS = "harmony-notifications"
    private const val KEY_ASKED = "asked-post-notifications"
}
