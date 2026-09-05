package com.example.appiconnotif

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 端 Application。
 *
 * libxposed API 102 不再使用 RemotePreferences 跨进程共享配置，
 * 而是由 Xposed 框架统一托管配置文件：App 端通过 io.github.libxposed:service
 * 绑定框架服务后调用 XposedService.getRemotePreferences() 写入，
 * 模块端（SystemUI 进程内）通过 XposedModule.getRemotePreferences() 只读访问。
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        @Volatile
        var xposedService: XposedService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService == service) {
            xposedService = null
        }
    }
}
