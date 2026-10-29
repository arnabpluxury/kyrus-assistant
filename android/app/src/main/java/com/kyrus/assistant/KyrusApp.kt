package com.kyrus.assistant

import android.app.Application
import com.kyrus.assistant.runtime.RuntimePaths

class KyrusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimePaths.init(this)
    }
}
