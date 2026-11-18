package com.kimes.daq

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
    }
}
