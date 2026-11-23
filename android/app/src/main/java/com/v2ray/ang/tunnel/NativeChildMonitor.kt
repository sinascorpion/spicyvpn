package com.v2ray.ang.tunnel

import android.app.Service
import android.os.Handler
import android.os.Looper
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.util.MessageUtil

internal fun monitorNativeChild(service: Service, child: Process, name: String, stopped: () -> Boolean) {
    Thread({
        child.waitFor()
        if (!stopped()) Handler(Looper.getMainLooper()).post {
            if (!stopped()) {
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "$name tunnel stopped")
                CoreServiceManager.serviceControl?.get()?.stopService()
            }
        }
    }, "spicyvpn-$name-monitor").apply { isDaemon = true; start() }
}
