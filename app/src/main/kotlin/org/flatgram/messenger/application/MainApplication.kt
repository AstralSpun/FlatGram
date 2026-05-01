package org.flatgram.messenger.application

import android.app.Application
import com.google.android.material.color.DynamicColors

internal class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}