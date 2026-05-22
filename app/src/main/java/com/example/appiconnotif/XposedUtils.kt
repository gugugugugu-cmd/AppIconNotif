package com.example.appiconnotif

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.util.LruCache

object XposedUtils {
    // 缓存应用图标，避免频繁 IPC 请求 PackageManager，设置最大容量为 50 个应用
    private val iconCache = LruCache<String, Drawable>(50)

    /**
     * 判断是否为第三方应用
     */
    fun isThirdPartyApp(context: Context, pkgName: String): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            !isSystemApp && !isUpdatedSystemApp
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 带缓存地获取应用原生彩色图标
     */
    fun getCachedAppIcon(context: Context, pkgName: String): Drawable? {
        iconCache.get(pkgName)?.let { return it }
        return try {
            val icon = context.packageManager.getApplicationIcon(pkgName)
            iconCache.put(pkgName, icon)
            icon
        } catch (_: Throwable) {
            null
        }
    }
}
