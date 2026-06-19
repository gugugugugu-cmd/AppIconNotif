package com.example.appiconnotif

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.util.LruCache
import de.robv.android.xposed.XSharedPreferences

object IconManager {

    private const val MODULE_PACKAGE = "com.example.appiconnotif"
    private const val PREF_NAME = "app_icon_notif_config"
    private const val KEY_TARGET_PACKAGES = "target_packages"

    // 设置最大缓存 60 个应用的图标，防止频繁跨进程 IPC 造成 SystemUI 卡顿
    private val iconCache = LruCache<String, Drawable>(60)
    // 缓存包名判定结果，避免重复进行位运算与 PackageManager 查询
    private val thirdPartyAppCache = HashMap<String, Boolean>()

    @Volatile
    private var prefs: XSharedPreferences? = null

    private fun getPrefs(): XSharedPreferences {
        val current = prefs
        if (current != null) {
            current.reload()
            return current
        }

        return XSharedPreferences(MODULE_PACKAGE, PREF_NAME).also {
            it.makeWorldReadable()
            prefs = it
        }
    }

    /**
     * 判断是否需要替换该应用的通知图标（需同时满足：用户选中 + 第三方应用）
     */
    fun shouldReplaceApp(context: Context, pkgName: String): Boolean {
        val targets = try {
            getPrefs().getStringSet(KEY_TARGET_PACKAGES, emptySet()) ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }
        return targets.contains(pkgName) && isThirdPartyApp(context, pkgName)
    }

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