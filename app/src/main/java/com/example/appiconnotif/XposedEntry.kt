package com.example.appiconnotif

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * libxposed API 102 模块入口。
 *
 * 入口类通过 META-INF/xposed/java_init.list 声明，由框架实例化；
 * 作用域（com.android.systemui）通过 META-INF/xposed/scope.list 静态声明，
 * 目标 API 版本在 META-INF/xposed/module.prop 中声明为 102。
 */
class XposedEntry : XposedModule() {

    init {
        instance = this
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        log(
            Log.INFO, TAG,
            "onModuleLoaded: process=${param.processName}, " +
                "framework=$frameworkName $frameworkVersion($frameworkVersionCode), api=$apiVersion"
        )
        // 模块端远程配置：在 hooked 进程内为只读，由模块 App 端通过框架服务写入
        try {
            IconManager.initRemotePrefs(getRemotePreferences(Config.REMOTE_PREFS_GROUP))
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "remote preferences unavailable", t)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != Config.TARGET_SYSTEMUI) return

        log(Log.INFO, TAG, "SystemUI detected, installing hooks ...")
        NotificationIconHook.hook(this, param.classLoader)
    }

    companion object {
        const val TAG = "AppIconNotif"

        @Volatile
        var instance: XposedEntry? = null
            private set

        fun log(msg: String) {
            instance?.log(Log.INFO, TAG, msg)
        }

        fun log(msg: String, t: Throwable) {
            instance?.log(Log.ERROR, TAG, msg, t)
        }
    }
}
