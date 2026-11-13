package lol.mony.harmony

import android.app.Application

class HarmonyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: HarmonyApp
            private set
    }
}
