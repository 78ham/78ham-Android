package com.ham78.app

import android.app.Application
import android.util.Log

class HamApplication : Application() {
    
    companion object {
        private const val TAG = "HamApplication"
        lateinit var instance: HamApplication
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "78HAM Application started")
    }
}
