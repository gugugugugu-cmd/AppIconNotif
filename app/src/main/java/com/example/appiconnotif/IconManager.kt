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
            [span_9](start_span)[span_10](start_span)val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)[span_9](end_span)[span_10](end_span)
            [span_11](start_span)[span_12](start_span)val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0[span_11](end_span)[span_12](end_span)
            [span_13](start_span)[span_14](start_span)val isUpdatedSystemApp = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0[span_13](end_span)[span_14](end_span)
            [span_15](start_span)[span_16](start_span)val result = !isSystemApp && !isUpdatedSystemApp[span_15](end_span)[span_16](end_span)
            thirdPartyAppCache[pkgName] = result
            result
        } catch (_: Throwable) {
            [span_17](start_span)[span_18](start_span)false[span_17](end_span)[span_18](end_span)
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
