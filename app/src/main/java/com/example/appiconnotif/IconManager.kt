package com.example.appiconnotif

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.util.LruCache

object IconManager {
    
    // 设置最大缓存 60 个应用的图标，防止频繁跨进程 IPC 造成 SystemUI 卡顿
    private val iconCache = LruCache<String, Drawable>(60)
    // 缓存包名判定结果，避免重复进行位运算与 PackageManager 查询
    private val thirdPartyAppCache = HashMap<String, Boolean>()

    /**
     * 判断是否为第三方应用（带内存缓存防止重复查询）
     */
    fun isThirdPartyApp(context: Context, pkgName: String): Boolean {
        thirdPartyAppCache[pkgName]?.let { return it }
        return try {
            val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val result = !isSystemApp && !isUpdatedSystemApp
            thirdPartyAppCache[pkgName] = result
            result
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 线程安全、带缓存的高效获取应用原生图标方法
     */
    fun getCachedAppIcon(context: Context, pkgName: String): Drawable? {
        iconCache.get(pkgName)?.let { return it.constantState?.newDrawable() ?: it }
        return try {
            // 始终使用 SystemUI 自身的 Context 获取 PackageManager，防范内存泄露
            val sysContext = context.applicationContext ?: context
            val icon = sysContext.packageManager.getApplicationIcon(pkgName)
            iconCache.put(pkgName, icon)
            icon
        } catch (_: Throwable) {
            null
        }
    }
}
