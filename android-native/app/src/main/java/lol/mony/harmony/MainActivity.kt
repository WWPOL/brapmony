package lol.mony.harmony

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import lol.mony.harmony.data.model.NotificationDestination
import lol.mony.harmony.notifications.HarmonyNotifications
import lol.mony.harmony.ui.HarmonyRoot

/**
 * The app's one activity.
 *
 * It owns the notification-permission prompt and the deep link a tapped notification carries.
 * The permission is requested here, at the first moment the app can sensibly ask (once a person
 * is signed in and has something that could notify them), and never at launch: a prompt before
 * the app has done anything is what people refuse.
 */
class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Either way the question is settled; the app never asks again, which is what
            // Android's own guidance requires after a refusal.
            (application as HarmonyApp).notifications.refreshPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = (application as HarmonyApp).state
        handle(intent, state)
        setContent { HarmonyRoot(state) }
        maybeAskForNotifications()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent, (application as HarmonyApp).state)
    }

    override fun onResume() {
        super.onResume()
        // The person may have changed the permission in system settings while the app was away.
        (application as HarmonyApp).notifications.refreshPermission()
    }

    /**
     * Turns a tapped notification into the destination the shell opens.
     *
     * The intent carries ids only, never text: the app resolves them against the signed-in
     * account, so a notification from an account that has since signed out cannot open a room
     * it may not read.
     */
    private fun handle(intent: Intent?, state: lol.mony.harmony.ui.AppState) {
        val data = intent?.data ?: return
        if (data.scheme != "brapmony") return
        fun param(name: String): String? = data.getQueryParameter(name)?.takeIf { it.isNotBlank() }
        val destination: NotificationDestination = when {
            param("conversation") != null -> NotificationDestination.Conversation(
                param("conversation")!!, param("message"))
            param("thread") != null -> NotificationDestination.Thread(
                param("server"), param("channel"), param("thread")!!, param("message"))
            param("channel") != null -> NotificationDestination.Channel(
                param("server"), param("channel")!!, param("message"))
            param("post") != null -> NotificationDestination.Post(param("post")!!)
            param("profile") != null -> NotificationDestination.Profile(param("profile"), null)
            param("screen") == "security" -> NotificationDestination.Security
            param("screen") == "follow-requests" -> NotificationDestination.FollowRequests
            else -> NotificationDestination.Chats
        }
        state.pendingDestination.value = destination
        // The intent is consumed, so a configuration change cannot replay it.
        intent.data = null
    }

    /**
     * Asks for notification permission once, when it can be explained, and only on Android 13+
     * where the runtime permission exists.
     *
     * A refusal is final for the app: the settings screen offers the route back to system
     * settings, and nothing here asks a second time.
     */
    private fun maybeAskForNotifications() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val coordinator = (application as HarmonyApp).notifications
        if (coordinator.permission.value != NotificationPermissionStateNotAsked) return
        HarmonyNotifications.rememberAsked(this)
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private val NotificationPermissionStateNotAsked
        get() = lol.mony.harmony.data.model.NotificationPermissionState.NotAsked
}
