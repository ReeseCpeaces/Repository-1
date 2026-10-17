package com.grove.app

import android.app.Application
import com.grove.app.notify.Notifier

class GroveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}
