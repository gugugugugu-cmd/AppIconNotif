package com.example.appiconnotif

import android.content.SharedPreferences
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.util.LruCache

object IconManager {

    // 设置最大缓存 60 个应用的图标，防止频繁跨进程 IPC 造成 SystemUI 卡顿
    private val iconCache = LruCache<String, Drawable>(60)
    // 缓存包名判定结果，避免重复进行位运算与 PackageManager 查询
    private val thirdPartyAppCache = HashMap<String, Boolean>()

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    /**
     * 由模块入口在 onModuleLoaded 时注入框架托管的远程配置。
     * libxposed API 102 中该配置存储于 Xposed 框架内，
     * App 端通过 io.github.libxposed:service 写入，此处只读。
     */
    fun initRemotePrefs(prefs: SharedPreferences) {
        synchronized(this) {
            remotePrefs = prefs
        }
    }

    /**
     * 判断是否需要替换该应用的通知图标（需同时满足：用户选中 + 第三方应用）
     */
    fun shouldReplaceApp(context: Context, pkgName: String): Boolean {
        val targets = try {
            remotePrefs?.getStringSet(Config.KEY_TARGET_PACKAGES, emptySet()) ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }
        return targets.contains(pkgName) && isThirdPartyApp(context, pkgName)
    }

    /**
     * 判断是否为第三方应用（带内存缓存防止重复查询）
     */
    fun isThirdPartyApp(context: Context, pkgName: String): Boolean {
        synchronized(thirdPartyAppCache) {
            thirdPartyAppCache[pkgName]?.let { return it }
        }
        return try {
            val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val result = !isSystemApp && !isUpdatedSystemApp
            synchronized(thirdPartyAppCache) {
                thirdPartyAppCache[pkgName] = result
            }
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
