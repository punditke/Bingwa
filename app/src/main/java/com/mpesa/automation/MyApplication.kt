package com.mpesa.automation

import android.app.Application

class MyApplication : Application() {
    
    companion object {
        lateinit var instance: MyApplication
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
