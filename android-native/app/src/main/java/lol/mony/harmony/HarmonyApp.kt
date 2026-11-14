package lol.mony.harmony

import android.app.Application
import lol.mony.harmony.data.repo.NotificationRepository
import lol.mony.harmony.notifications.HarmonyNotifications
import lol.mony.harmony.notifications.NotificationCoordinator
import lol.mony.harmony.ui.AppState

class HarmonyApp : Application() {
    val container by lazy { AppContainer(this) }
    val state by lazy { AppState(container) }

    /**
     * Notification channels and the inbox poller.
     *
     * Channels are registered here, before anything is signed in, so system settings shows a
     * complete, meaningful list from the first launch -- which is what was missing when the app
     * had no channels at all.
     */
    val notifications: NotificationCoordinator by lazy {
        NotificationCoordinator(this, container.auth) { rest -> NotificationRepository(rest) }
    }

    override fun onCreate() {
        super.onCreate()
        HarmonyNotifications.ensureChannels(this)
    }
}
