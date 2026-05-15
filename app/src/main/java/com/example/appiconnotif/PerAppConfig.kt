package com.example.appiconnotif

import android.content.Context
import de.robv.android.xposed.XSharedPreferences
import java.io.File
import java.io.FileOutputStream
import java.util.*

object PerAppConfig {
    private const val PREFS_NAME = "app_icon_notif_per_app.xml"
    private const val MODULE_PACKAGE_NAME = "com.example.appiconnotif"
    private var xsp: XSharedPreferences? = null

    fun init() {
        xsp = XSharedPreferences(MODULE_PACKAGE_NAME, PREFS_NAME.replace(".xml", ""))
        xsp?.makeWorldReadable()
    }

    /**
     * 读取指定包名的开关状态（供 SystemUI 使用）
     */
    fun isReplacementEnabled(pkgName: String): Boolean {
        val prefs = xsp ?: return true
        prefs.reload()
        return prefs.getBoolean("enable_$pkgName", true)
    }

    /**
     * 写入指定包名的开关状态（供 SettingsActivity 使用）
     * 直接操作 XML 文件并设置全局可读权限
     */
    fun setReplacementEnabled(pkgName: String, enabled: Boolean, context: Context) {
        // 获取 SharedPreferences 文件路径
        val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME")
        val prefsDir = prefsFile.parentFile
        if (!prefsDir.exists()) prefsDir.mkdirs()

        // 读取现有内容或创建新内容
        val map = mutableMapOf<String, Any>()
        if (prefsFile.exists()) {
            try {
                // 简单解析 XML（这里为了简化，使用 Android 的 SharedPreferences 读取再写入）
                // 但为了避免冲突，直接使用原生的 getSharedPreferences 读取并修改
            } catch (e: Exception) { }
        }

        // 使用标准方式写入（但最后会修改权限）
        val prefs = context.getSharedPreferences(PREFS_NAME.replace(".xml", ""), Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enable_$pkgName", enabled).apply()

        // 强制设置文件权限为 0644（全局可读）
        try {
            prefsFile.setReadable(true, false)
            prefsFile.setWritable(true, true)  // 仅自己可写
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 同时尝试让目录也可读
        prefsDir.setReadable(true, false)
        prefsDir.setExecutable(true, false)
    }
}