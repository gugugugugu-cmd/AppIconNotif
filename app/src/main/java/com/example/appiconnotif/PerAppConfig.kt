package com.example.appiconnotif

import android.content.Context
import de.robv.android.xposed.XSharedPreferences

object PerAppConfig {
    private const val PREFS_NAME = "app_icon_notif_per_app"
    private var xsp: XSharedPreferences? = null

    fun init() {
        xsp = XSharedPreferences(BuildConfig.APPLICATION_ID, PREFS_NAME)
        xsp?.makeWorldReadable()
    }

    /**
     * 检查指定包名是否启用替换（默认 true，即替换为应用图标）
     */
    fun isReplacementEnabled(pkgName: String): Boolean {
        val prefs = xsp ?: return true
        prefs.reload()
        return prefs.getBoolean("enable_$pkgName", true)
    }

    /**
     * 设置指定包名的开关状态
     */
    fun setReplacementEnabled(pkgName: String, enabled: Boolean, context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
        prefs.edit().putBoolean("enable_$pkgName", enabled).apply()
    }
}