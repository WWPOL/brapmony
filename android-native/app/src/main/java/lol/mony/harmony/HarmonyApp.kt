package lol.mony.harmony

import android.app.Application
import lol.mony.harmony.ui.AppState

class HarmonyApp : Application() {
    val container by lazy { AppContainer(this) }
    val state by lazy { AppState(container) }
}
