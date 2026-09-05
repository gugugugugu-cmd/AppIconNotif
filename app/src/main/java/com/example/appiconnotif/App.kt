package com.example.appiconnotif

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 模块 App 端 Application。
 *
 * 本地 SharedPreferences 用于保证界面状态持久化；XposedService 远程配置
 * 用于 SystemUI Hook 读取。服务绑定是异步的，因此需要通知当前 Activity
 * 及时同步本地配置到框架。
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        @Volatile
        var xposedService: XposedService? = null
            private set

        private val serviceListeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

        fun addServiceListener(listener: (XposedService?) -> Unit) {
            serviceListeners.add(listener)
            listener(xposedService)
        }

        fun removeServiceListener(listener: (XposedService?) -> Unit) {
            serviceListeners.remove(listener)
        }

        private fun notifyServiceChanged(service: XposedService?) {
            serviceListeners.forEach { it(service) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        notifyServiceChanged(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService == service) {
            xposedService = null
            notifyServiceChanged(null)
        }
    }
}
