package com.example.appiconnotif

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

object PerAppConfig {
    private const val CONFIG_FILE = "app_icon_config.json"
    private lateinit var configFile: File
    private var configCache: JSONObject? = null
    private var isInit = false

    // 在 SettingsActivity 中调用（写入模式）
    fun init(context: Context) {
        if (isInit) return
        configFile = File(context.applicationInfo.dataDir, CONFIG_FILE)
        if (!configFile.exists()) {
            try {
                configFile.createNewFile()
                configFile.setReadable(true, false)   // 全局可读
                configFile.setWritable(true, true)    // 仅自己可写
                configFile.writeText("{}")
            } catch (e: Exception) {
                Log.e("AppIconNotif", "创建配置文件失败", e)
            }
        }
        loadConfig()
        isInit = true
    }

    // 在 SystemUI 进程中调用（只读模式）
    fun initForRead() {
        if (isInit) return
        configFile = File("/data/data/com.example.appiconnotif/$CONFIG_FILE")
        if (configFile.exists()) {
            loadConfig()
        } else {
            configCache = JSONObject()
        }
        isInit = true
    }

    private fun loadConfig() {
        configCache = try {
            JSONObject(configFile.readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveConfig() {
        try {
            configFile.writeText(configCache.toString())
            configFile.setReadable(true, false) // 确保权限
        } catch (e: Exception) {
            Log.e("AppIconNotif", "保存配置失败", e)
        }
    }

    fun isReplacementEnabled(pkgName: String): Boolean {
        if (configCache == null) {
            // 尝试加载（若之前未加载）
            if (configFile.exists()) loadConfig()
            else configCache = JSONObject()
        }
        return configCache?.optBoolean(pkgName, true) ?: true
    }

    fun setReplacementEnabled(pkgName: String, enabled: Boolean, context: Context) {
        if (!isInit) init(context)
        if (configCache == null) loadConfig()
        configCache?.put(pkgName, enabled)
        saveConfig()
    }
}