package com.derfurt.deryurt

import android.app.Application
import com.derfurt.deryurt.data.StatusStore
import com.derfurt.deryurt.trigger.LoginWork
import com.derfurt.deryurt.trigger.NetworkWatch
import com.derfurt.deryurt.trigger.Notifier
import com.derfurt.deryurt.ui.AppTheme

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppTheme.applyNightMode(this)
        Notifier.createChannels(this)
        // Bu açılışta/sürümde kurulmadıysa wifi dinleyicisini kur (kurulduysa dokunmaz).
        NetworkWatch.arm(this)
        if (StatusStore(this).autoLogin) LoginWork.schedulePeriodic(this)
    }
}
