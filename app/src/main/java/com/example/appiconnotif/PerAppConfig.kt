package com.example.appiconnotif

import android.content.Context
import de.robv.android.xposed.XSharedPreferences

object PerAppConfig {
    private const val PREFS_NAME = "app_icon_notif_per_app"
    // 直接使用模块包名，不依赖 BuildConfig
    private const val MODULE_PACKAGE_NAME = "com.example.appiconnotif"

    private var xsp: XSharedPreferences? = null

    fun init() {
        xsp = XSharedPreferences(MODULE_PACKAGE_NAME, PREFS_NAME)
        xsp?.makeWorldReadable()
    }

    fun isReplacementEnabled(pkgName: String): Boolean {
        val prefs = xsp ?: return true
        prefs.reload()
        return prefs.getBoolean("enable_$pkgName", true)
    }

    fun setReplacementEnabled(pkgName: String, enabled: Boolean, context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
        prefs.edit().putBoolean("enable_$pkgName", enabled).apply()
    }
}